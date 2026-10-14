// JNI bridge between com.offlineagent.engine.llamacpp.LlamaNative and llama.cpp.
//
// Threading contract: every function except nativeAbort() is called from ONE dedicated
// Kotlin thread ("llm-engine"). nativeAbort() may be called from any thread; it only flips
// an atomic flag that llama.cpp's abort callback and the token loop poll.
//
// Written against llama.cpp's public C API only (llama.h), not the unstable `common` lib,
// so upgrading the vendored tag rarely breaks this file. Design references
// third_party/llama.cpp/examples/llama.android/lib/src/main/cpp/ai_chat.cpp.

#include <jni.h>
#include <android/log.h>

#include <algorithm>
#include <atomic>
#include <cstdint>
#include <cstring>
#include <string>
#include <vector>

#include <cmath>

#include "llama.h"
#include "ggml-backend.h"
#include "mtmd.h"
#include "mtmd-helper.h"

#define TAG "OfflineAgentLlama"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

// Must match LlamaNative.kt
constexpr int BEGIN_ERR_TOO_LONG = -1;
constexpr int BEGIN_ERR_ABORTED  = -2;
constexpr int BEGIN_ERR_DECODE   = -3;
constexpr int BEGIN_ERR_TOKENIZE = -4;
constexpr int BEGIN_ERR_GRAMMAR  = -5;

constexpr int STOP_NONE         = 0;
constexpr int STOP_END_OF_TURN  = 1;
constexpr int STOP_MAX_TOKENS   = 2;
constexpr int STOP_CONTEXT_FULL = 3;
constexpr int STOP_CANCELLED    = 4;
constexpr int STOP_ERROR        = 5;

struct Session {
    llama_model *        model   = nullptr;
    llama_context *      ctx     = nullptr;
    const llama_vocab *  vocab   = nullptr;
    llama_sampler *      sampler = nullptr;
    llama_batch          batch{};
    int                  n_batch = 512;
    int                  n_ctx   = 2048;

    std::vector<llama_token> kv_tokens;   // exactly what is in the KV cache (seq 0) when !kv_dirty
    std::atomic<bool>        abort{false};

    // Grammar-constrained sampling (GBNF). Kept OUTSIDE the chain: we sample normally, then check the
    // token against the grammar and only fall back to a full grammar pass when it is rejected. This is
    // the same trick llama.cpp's common sampler uses; it avoids running the grammar over 150k candidates
    // on every token.
    llama_sampler *          grammar = nullptr;
    std::vector<llama_token_data> cur;  // scratch candidates for the grammar fallback

    // Multimodal projector (optional): set by nativeAttachMmproj.
    mtmd_context *           mtmd = nullptr;
    llama_pos                n_past = 0;   // next position (== kv_tokens.size() for text-only prompts)
    bool                     kv_dirty = false; // KV holds image embeddings: no prefix reuse next turn

    int generated  = 0;
    int max_new    = 0;
    int stop       = STOP_NONE;
    bool has_logits = false;
};

std::string g_last_error;
bool g_backend_ready = false;

void log_callback(ggml_log_level level, const char * text, void *) {
    int prio = ANDROID_LOG_DEBUG;
    switch (level) {
        case GGML_LOG_LEVEL_ERROR: prio = ANDROID_LOG_ERROR; break;
        case GGML_LOG_LEVEL_WARN:  prio = ANDROID_LOG_WARN;  break;
        case GGML_LOG_LEVEL_INFO:  prio = ANDROID_LOG_INFO;  break;
        default: return; // drop debug/cont spam
    }
    __android_log_write(prio, "llama.cpp", text);
}

bool abort_callback(void * data) {
    auto * s = static_cast<Session *>(data);
    return s->abort.load(std::memory_order_relaxed);
}

std::string jstr(JNIEnv * env, jstring s) {
    if (!s) return {};
    const char * c = env->GetStringUTFChars(s, nullptr);
    std::string out(c ? c : "");
    if (c) env->ReleaseStringUTFChars(s, c);
    return out;
}

// JNI's GetStringUTFChars is "modified UTF-8" (emoji become surrogate pairs). Prompts can contain
// any language, so get standard UTF-8 via String.getBytes("UTF-8") semantics from a byte[] instead.
std::string jbytes(JNIEnv * env, jbyteArray arr) {
    if (!arr) return {};
    const jsize n = env->GetArrayLength(arr);
    std::string out(static_cast<size_t>(n), '\0');
    env->GetByteArrayRegion(arr, 0, n, reinterpret_cast<jbyte *>(out.data()));
    return out;
}

Session * as_session(jlong h) { return reinterpret_cast<Session *>(h); }

void free_session(Session * s) {
    if (!s) return;
    if (s->sampler) llama_sampler_free(s->sampler);
    if (s->grammar) llama_sampler_free(s->grammar);
    if (s->mtmd) mtmd_free(s->mtmd);
    if (s->batch.token) llama_batch_free(s->batch);
    if (s->ctx) llama_free(s->ctx);
    if (s->model) llama_model_free(s->model);
    delete s;
}

void batch_clear(llama_batch & b) { b.n_tokens = 0; }

void batch_add(llama_batch & b, llama_token t, llama_pos pos, bool logits) {
    const int i = b.n_tokens;
    b.token[i] = t;
    b.pos[i] = pos;
    b.n_seq_id[i] = 1;
    b.seq_id[i][0] = 0;
    b.logits[i] = logits ? 1 : 0;
    b.n_tokens++;
}

bool tokenize(const llama_vocab * vocab, const std::string & text, std::vector<llama_token> & out) {
    int n = -llama_tokenize(vocab, text.data(), (int32_t) text.size(), nullptr, 0,
                            /*add_special*/ true, /*parse_special*/ true);
    if (n <= 0) { out.clear(); return n == 0; }
    out.resize(n);
    const int got = llama_tokenize(vocab, text.data(), (int32_t) text.size(), out.data(), n, true, true);
    if (got < 0) return false;
    out.resize(got);
    return true;
}

} // namespace

extern "C" {

JNIEXPORT void JNICALL
Java_com_offlineagent_engine_llamacpp_LlamaNative_nativeInit(JNIEnv * env, jobject, jstring jlibdir) {
    if (g_backend_ready) return;
    llama_log_set(log_callback, nullptr);
    mtmd_helper_log_set(log_callback, nullptr);
    const std::string dir = jstr(env, jlibdir);
    // Loads libggml-cpu-android_armv*.so variants and keeps the best one this CPU supports.
    LOGI("Loading ggml backends from %s", dir.c_str());
    ggml_backend_load_all_from_path(dir.c_str());
    llama_backend_init();
    g_backend_ready = true;
}

JNIEXPORT jlong JNICALL
Java_com_offlineagent_engine_llamacpp_LlamaNative_nativeLoad(JNIEnv * env, jobject, jstring jpath,
                                                            jint n_ctx, jint n_threads, jint n_batch,
                                                            jboolean use_mmap) {
    g_last_error.clear();
    const std::string path = jstr(env, jpath);

    if (ggml_backend_dev_count() == 0) {
        g_last_error = "No CPU backend could be loaded for this device (unsupported CPU?)";
        return 0;
    }

    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0;                                  // CPU only in Phase 1
    mp.load_mode = use_mmap ? LLAMA_LOAD_MODE_MMAP : LLAMA_LOAD_MODE_NONE;

    auto * s = new Session();
    s->model = llama_model_load_from_file(path.c_str(), mp);
    if (!s->model) {
        g_last_error = "llama.cpp could not load the model file (corrupt, incomplete, or unsupported GGUF)";
        free_session(s);
        return 0;
    }
    s->vocab = llama_model_get_vocab(s->model);

    const int trained = llama_model_n_ctx_train(s->model);
    s->n_ctx = std::max(256, std::min((int) n_ctx, trained > 0 ? trained : (int) n_ctx));
    s->n_batch = std::max(32, std::min((int) n_batch, s->n_ctx));

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = (uint32_t) s->n_ctx;
    cp.n_batch = (uint32_t) s->n_batch;
    cp.n_ubatch = (uint32_t) s->n_batch;
    cp.n_seq_max = 1;
    cp.n_threads = n_threads;
    cp.n_threads_batch = n_threads;
    cp.abort_callback = abort_callback;
    cp.abort_callback_data = s;

    s->ctx = llama_init_from_model(s->model, cp);
    if (!s->ctx) {
        g_last_error = "Could not create the inference context (likely out of memory; try a smaller context)";
        free_session(s);
        return 0;
    }
    s->batch = llama_batch_init(s->n_batch, 0, 1);
    LOGI("Model loaded: ctx=%d batch=%d threads=%d mmap=%d", s->n_ctx, s->n_batch, n_threads, (int) use_mmap);
    return reinterpret_cast<jlong>(s);
}

JNIEXPORT jstring JNICALL
Java_com_offlineagent_engine_llamacpp_LlamaNative_nativeLastError(JNIEnv * env, jobject) {
    return env->NewStringUTF(g_last_error.c_str());
}

// Returns [description, sizeBytes, paramCount, trainedCtx, ctx, chatTemplate]
JNIEXPORT jobjectArray JNICALL
Java_com_offlineagent_engine_llamacpp_LlamaNative_nativeModelInfo(JNIEnv * env, jobject, jlong h) {
    auto * s = as_session(h);
    char desc[256] = {0};
    llama_model_desc(s->model, desc, sizeof(desc));
    const char * tmpl = llama_model_chat_template(s->model, nullptr);
    std::string fields[6] = {
        desc,
        std::to_string(llama_model_size(s->model)),
        std::to_string(llama_model_n_params(s->model)),
        std::to_string(llama_model_n_ctx_train(s->model)),
        std::to_string(llama_n_ctx(s->ctx)),
        tmpl ? std::string(tmpl) : std::string(),
    };
    jobjectArray arr = env->NewObjectArray(6, env->FindClass("java/lang/String"), nullptr);
    for (int i = 0; i < 6; i++) {
        jstring v = env->NewStringUTF(fields[i].c_str());
        env->SetObjectArrayElement(arr, i, v);
        env->DeleteLocalRef(v);
    }
    return arr;
}

// Builds the sampler chain (+ optional grammar) for one request. Returns false if the grammar fails to parse.
static bool setup_sampling(Session * s, float temp, int top_k, float top_p, float min_p, float repeat_penalty, int seed,
                           const std::string & grammar) {
    if (s->sampler) { llama_sampler_free(s->sampler); s->sampler = nullptr; }
    if (s->grammar) { llama_sampler_free(s->grammar); s->grammar = nullptr; }
    auto sp = llama_sampler_chain_default_params();
    sp.no_perf = true;
    s->sampler = llama_sampler_chain_init(sp);
    if (repeat_penalty > 1.0f) {
        llama_sampler_chain_add(s->sampler,
            llama_sampler_init_penalties(llama_vocab_n_tokens(s->vocab), 64, repeat_penalty, 0.0f, 0.0f));
    }
    if (temp <= 0.0f) {
        llama_sampler_chain_add(s->sampler, llama_sampler_init_greedy());
    } else {
        if (top_k > 0) llama_sampler_chain_add(s->sampler, llama_sampler_init_top_k(top_k));
        if (top_p < 1.0f) llama_sampler_chain_add(s->sampler, llama_sampler_init_top_p(top_p, 1));
        if (min_p > 0.0f) llama_sampler_chain_add(s->sampler, llama_sampler_init_min_p(min_p, 1));
        llama_sampler_chain_add(s->sampler, llama_sampler_init_temp(temp));
        llama_sampler_chain_add(s->sampler, llama_sampler_init_dist(seed < 0 ? LLAMA_DEFAULT_SEED : (uint32_t) seed));
    }
    if (!grammar.empty()) {
        s->grammar = llama_sampler_init_grammar(s->vocab, grammar.c_str(), "root");
        if (!s->grammar) { g_last_error = "Invalid GBNF grammar"; return false; }
    }
    return true;
}

// Text-only prompt evaluation with KV prefix reuse.
static jint eval_text(Session * s, const std::vector<llama_token> & tokens) {
    llama_memory_t mem = llama_get_memory(s->ctx);
    size_t common = 0;
    if (s->kv_dirty) {
        llama_memory_clear(mem, false);
        s->kv_tokens.clear();
        s->kv_dirty = false;
    }
    while (common < s->kv_tokens.size() && common < tokens.size() && s->kv_tokens[common] == tokens[common]) common++;
    if (common == tokens.size()) common--;  // must evaluate >= 1 token to get fresh logits
    if (!llama_memory_seq_rm(mem, 0, (llama_pos) common, -1)) {
        llama_memory_clear(mem, false);
        common = 0;
    }
    s->kv_tokens.resize(common);

    for (size_t i = common; i < tokens.size(); i += s->n_batch) {
        const size_t end = std::min(tokens.size(), i + (size_t) s->n_batch);
        batch_clear(s->batch);
        for (size_t j = i; j < end; j++) batch_add(s->batch, tokens[j], (llama_pos) j, j == tokens.size() - 1);
        const int rc = llama_decode(s->ctx, s->batch);
        if (rc != 0) {
            // Whatever partially landed in the cache is unknown: drop it all to stay consistent.
            llama_memory_clear(mem, false);
            s->kv_tokens.clear();
            s->n_past = 0;
            if (rc == 2 || s->abort.load()) return BEGIN_ERR_ABORTED;
            g_last_error = "llama_decode failed during prompt evaluation (code " + std::to_string(rc) + ")";
            return BEGIN_ERR_DECODE;
        }
        s->kv_tokens.insert(s->kv_tokens.end(), tokens.begin() + i, tokens.begin() + end);
    }
    s->n_past = (llama_pos) s->kv_tokens.size();
    return (jint) tokens.size();
}

// Prompt with images: the prompt contains one media marker per image; images are packed RGB (w*h*3).
static jint eval_multimodal(JNIEnv * env, Session * s, const std::string & prompt, jobjectArray jimgs,
                            jintArray jw, jintArray jh) {
    if (!s->mtmd) { g_last_error = "This model has no vision projector (mmproj) attached"; return BEGIN_ERR_TOKENIZE; }
    const jsize n = env->GetArrayLength(jimgs);
    std::vector<jint> ws(n), hs(n);
    env->GetIntArrayRegion(jw, 0, n, ws.data());
    env->GetIntArrayRegion(jh, 0, n, hs.data());
    std::vector<mtmd_bitmap *> bitmaps;
    for (jsize i = 0; i < n; i++) {
        auto arr = (jbyteArray) env->GetObjectArrayElement(jimgs, i);
        std::string rgb = jbytes(env, arr);
        env->DeleteLocalRef(arr);
        if ((size_t) ws[i] * hs[i] * 3 != rgb.size()) {
            for (auto * b : bitmaps) mtmd_bitmap_free(b);
            g_last_error = "Image buffer size mismatch";
            return BEGIN_ERR_TOKENIZE;
        }
        bitmaps.push_back(mtmd_bitmap_init((uint32_t) ws[i], (uint32_t) hs[i], (const unsigned char *) rgb.data()));
    }
    mtmd_input_chunks * chunks = mtmd_input_chunks_init();
    mtmd_input_text text{prompt.c_str(), prompt.size(), /*add_special*/ true, /*parse_special*/ true};
    const int32_t trc = mtmd_tokenize(s->mtmd, chunks, &text, (const mtmd_bitmap **) bitmaps.data(), bitmaps.size());
    for (auto * b : bitmaps) mtmd_bitmap_free(b);
    if (trc != 0) {
        mtmd_input_chunks_free(chunks);
        g_last_error = trc == 1 ? "Number of images does not match image markers" : "Image preprocessing failed";
        return BEGIN_ERR_TOKENIZE;
    }
    const size_t n_tok = mtmd_helper_get_n_tokens(chunks);
    if ((int) n_tok + 8 > s->n_ctx) {
        mtmd_input_chunks_free(chunks);
        g_last_error = "Prompt with image does not fit in the context window";
        return BEGIN_ERR_TOO_LONG;
    }
    llama_memory_clear(llama_get_memory(s->ctx), false);
    s->kv_tokens.clear();
    s->kv_dirty = true;
    llama_pos new_past = 0;
    const int32_t rc = mtmd_helper_eval_chunks(s->mtmd, s->ctx, chunks, 0, 0, s->n_batch, true, &new_past);
    mtmd_input_chunks_free(chunks);
    if (rc != 0) {
        llama_memory_clear(llama_get_memory(s->ctx), false);
        s->n_past = 0;
        if (s->abort.load()) return BEGIN_ERR_ABORTED;
        g_last_error = "Multimodal prompt evaluation failed (code " + std::to_string(rc) + ")";
        return BEGIN_ERR_DECODE;
    }
    s->n_past = new_past;
    return (jint) n_tok;
}

JNIEXPORT jint JNICALL
Java_com_offlineagent_engine_llamacpp_LlamaNative_nativeBegin(JNIEnv * env, jobject, jlong h, jbyteArray jprompt,
                                                             jfloat temp, jint top_k, jfloat top_p, jfloat min_p,
                                                             jfloat repeat_penalty, jint seed, jint max_new,
                                                             jbyteArray jgrammar, jobjectArray jimgs,
                                                             jintArray jw, jintArray jh) {
    auto * s = as_session(h);
    s->abort.store(false);
    s->generated = 0;
    s->max_new = max_new;
    s->stop = STOP_NONE;
    s->has_logits = false;

    if (!setup_sampling(s, temp, top_k, top_p, min_p, repeat_penalty, seed, jbytes(env, jgrammar))) {
        return BEGIN_ERR_GRAMMAR;
    }
    const std::string prompt = jbytes(env, jprompt);
    jint rc;
    if (jimgs != nullptr && env->GetArrayLength(jimgs) > 0) {
        rc = eval_multimodal(env, s, prompt, jimgs, jw, jh);
    } else {
        std::vector<llama_token> tokens;
        if (!tokenize(s->vocab, prompt, tokens) || tokens.empty()) {
            g_last_error = "Tokenization failed";
            return BEGIN_ERR_TOKENIZE;
        }
        if ((int) tokens.size() + 8 > s->n_ctx) {
            g_last_error = "Prompt does not fit in the context window";
            return BEGIN_ERR_TOO_LONG;
        }
        rc = eval_text(s, tokens);
    }
    if (rc > 0) s->has_logits = true;
    return rc;
}

// Sample one token honouring the grammar (if any).
static llama_token sample_token(Session * s) {
    llama_token tok = llama_sampler_sample(s->sampler, s->ctx, -1);
    if (!s->grammar) return tok;
    // Fast path: is the freely sampled token allowed by the grammar?
    llama_token_data one{tok, 1.0f, 0.0f};
    llama_token_data_array single{&one, 1, -1, false};
    llama_sampler_apply(s->grammar, &single);
    if (std::isfinite(one.logit)) {
        llama_sampler_accept(s->grammar, tok);
        return tok;
    }
    // Slow path: mask the full vocabulary with the grammar, then run the normal chain on what is left.
    const float * logits = llama_get_logits_ith(s->ctx, -1);
    const int n_vocab = llama_vocab_n_tokens(s->vocab);
    s->cur.resize(n_vocab);
    for (int i = 0; i < n_vocab; i++) s->cur[i] = llama_token_data{i, logits[i], 0.0f};
    llama_token_data_array arr{s->cur.data(), (size_t) n_vocab, -1, false};
    llama_sampler_apply(s->grammar, &arr);
    llama_sampler_apply(s->sampler, &arr);
    if (arr.selected < 0 || arr.selected >= (int64_t) arr.size) {
        // greedy fallback over the grammar-masked candidates
        size_t best = 0;
        for (size_t i = 1; i < arr.size; i++) if (arr.data[i].logit > arr.data[best].logit) best = i;
        tok = arr.data[best].id;
    } else {
        tok = arr.data[arr.selected].id;
    }
    llama_sampler_accept(s->grammar, tok);
    return tok;
}

// Returns the UTF-8 bytes of the next piece, or null when generation ended (see nativeStopReason).
JNIEXPORT jbyteArray JNICALL
Java_com_offlineagent_engine_llamacpp_LlamaNative_nativeNext(JNIEnv * env, jobject, jlong h) {
    auto * s = as_session(h);
    if (s->stop != STOP_NONE) return nullptr;
    if (s->abort.load()) { s->stop = STOP_CANCELLED; return nullptr; }
    if (!s->has_logits) { s->stop = STOP_ERROR; return nullptr; }
    if (s->generated >= s->max_new) { s->stop = STOP_MAX_TOKENS; return nullptr; }
    if ((int) s->n_past >= s->n_ctx - 1) { s->stop = STOP_CONTEXT_FULL; return nullptr; }

    const llama_token tok = sample_token(s);
    if (llama_vocab_is_eog(s->vocab, tok)) { s->stop = STOP_END_OF_TURN; return nullptr; }

    char buf[256];
    int n = llama_token_to_piece(s->vocab, tok, buf, sizeof(buf), 0, /*special*/ false);
    std::string piece;
    if (n < 0) {
        piece.resize(-n);
        n = llama_token_to_piece(s->vocab, tok, piece.data(), (int32_t) piece.size(), 0, false);
        piece.resize(std::max(0, n));
    } else {
        piece.assign(buf, n);
    }

    batch_clear(s->batch);
    batch_add(s->batch, tok, s->n_past, true);
    const int rc = llama_decode(s->ctx, s->batch);
    if (rc != 0) {
        s->has_logits = false;
        s->stop = (rc == 2 || s->abort.load()) ? STOP_CANCELLED : STOP_ERROR;
        if (s->stop == STOP_ERROR) g_last_error = "llama_decode failed during generation (code " + std::to_string(rc) + ")";
        // The sampled token is still returned below so the visible text stays complete;
        // its KV entry may be missing, so drop it from our mirror (next prompt re-evaluates).
        llama_memory_seq_rm(llama_get_memory(s->ctx), 0, s->n_past, -1);
    } else {
        if (!s->kv_dirty) s->kv_tokens.push_back(tok);
        s->n_past++;
    }
    s->generated++;

    jbyteArray out = env->NewByteArray((jsize) piece.size());
    env->SetByteArrayRegion(out, 0, (jsize) piece.size(), reinterpret_cast<const jbyte *>(piece.data()));
    return out;
}

JNIEXPORT jint JNICALL
Java_com_offlineagent_engine_llamacpp_LlamaNative_nativeStopReason(JNIEnv *, jobject, jlong h) {
    return as_session(h)->stop;
}

JNIEXPORT void JNICALL
Java_com_offlineagent_engine_llamacpp_LlamaNative_nativeAbort(JNIEnv *, jobject, jlong h) {
    if (h) as_session(h)->abort.store(true);
}

JNIEXPORT void JNICALL
Java_com_offlineagent_engine_llamacpp_LlamaNative_nativeUnload(JNIEnv *, jobject, jlong h) {
    free_session(as_session(h));
}

// ---------------------------------------------------------------- multimodal projector

JNIEXPORT jboolean JNICALL
Java_com_offlineagent_engine_llamacpp_LlamaNative_nativeAttachMmproj(JNIEnv * env, jobject, jlong h, jstring jpath,
                                                                    jint n_threads) {
    auto * s = as_session(h);
    if (s->mtmd) { mtmd_free(s->mtmd); s->mtmd = nullptr; }
    const std::string path = jstr(env, jpath);
    mtmd_context_params p = mtmd_context_params_default();
    p.use_gpu = false;
    p.n_threads = n_threads;
    p.print_timings = false;
    p.warmup = false;
    // Phones: cap the visual tokens per image. Without a cap SmolVLM/Idefics3 upscales every photo to 2048 px and
    // encodes 16 tiles + overview (~1100 tokens, ~1 min on a mid-range CPU). 512 keeps detail for documents/charts.
    p.image_max_tokens = 512;
    s->mtmd = mtmd_init_from_file(path.c_str(), s->model, p);
    if (!s->mtmd) { g_last_error = "Could not load the vision projector (mmproj) - wrong file for this model?"; return JNI_FALSE; }
    if (!mtmd_support_vision(s->mtmd)) {
        mtmd_free(s->mtmd); s->mtmd = nullptr;
        g_last_error = "This mmproj file has no vision encoder";
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

JNIEXPORT jstring JNICALL
Java_com_offlineagent_engine_llamacpp_LlamaNative_nativeMediaMarker(JNIEnv * env, jobject) {
    return env->NewStringUTF(mtmd_default_marker());
}

// ---------------------------------------------------------------- built-in chat templates

// Formats a conversation with llama.cpp's built-in template matcher (for non-ChatML models). Returns null if unsupported.
JNIEXPORT jbyteArray JNICALL
Java_com_offlineagent_engine_llamacpp_LlamaNative_nativeApplyTemplate(JNIEnv * env, jobject, jlong h,
                                                                     jobjectArray jroles, jobjectArray jcontents) {
    auto * s = as_session(h);
    const char * tmpl = llama_model_chat_template(s->model, nullptr);
    if (!tmpl) return nullptr;
    const jsize n = env->GetArrayLength(jroles);
    std::vector<std::string> roles(n), contents(n);
    std::vector<llama_chat_message> msgs(n);
    for (jsize i = 0; i < n; i++) {
        auto r = (jstring) env->GetObjectArrayElement(jroles, i);
        auto c = (jbyteArray) env->GetObjectArrayElement(jcontents, i);
        roles[i] = jstr(env, r);
        contents[i] = jbytes(env, c);
        env->DeleteLocalRef(r); env->DeleteLocalRef(c);
    }
    size_t total = 0;
    for (jsize i = 0; i < n; i++) { msgs[i] = {roles[i].c_str(), contents[i].c_str()}; total += contents[i].size(); }
    std::vector<char> buf(total * 2 + 1024);
    int32_t len = llama_chat_apply_template(tmpl, msgs.data(), msgs.size(), true, buf.data(), (int32_t) buf.size());
    if (len < 0) return nullptr;
    if ((size_t) len > buf.size()) {
        buf.resize(len + 1);
        len = llama_chat_apply_template(tmpl, msgs.data(), msgs.size(), true, buf.data(), (int32_t) buf.size());
        if (len < 0) return nullptr;
    }
    jbyteArray out = env->NewByteArray(len);
    env->SetByteArrayRegion(out, 0, len, reinterpret_cast<const jbyte *>(buf.data()));
    return out;
}

// ---------------------------------------------------------------- embeddings (separate model + context)

struct Embedder {
    llama_model * model = nullptr;
    llama_context * ctx = nullptr;
    const llama_vocab * vocab = nullptr;
    int n_ctx = 512;
    int n_embd = 0;
    std::atomic<bool> abort{false};
};

static bool embed_abort_cb(void * data) { return static_cast<Embedder *>(data)->abort.load(); }

static void free_embedder(Embedder * e) {
    if (!e) return;
    if (e->ctx) llama_free(e->ctx);
    if (e->model) llama_model_free(e->model);
    delete e;
}

JNIEXPORT jlong JNICALL
Java_com_offlineagent_engine_llamacpp_LlamaNative_nativeLoadEmbedder(JNIEnv * env, jobject, jstring jpath, jint n_ctx,
                                                                    jint n_threads) {
    g_last_error.clear();
    if (ggml_backend_dev_count() == 0) { g_last_error = "No CPU backend"; return 0; }
    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0;
    mp.load_mode = LLAMA_LOAD_MODE_MMAP;
    auto * e = new Embedder();
    const std::string path = jstr(env, jpath);
    e->model = llama_model_load_from_file(path.c_str(), mp);
    if (!e->model) { g_last_error = "Could not load the embedding model"; free_embedder(e); return 0; }
    e->vocab = llama_model_get_vocab(e->model);
    const int trained = llama_model_n_ctx_train(e->model);
    e->n_ctx = std::max(64, std::min((int) n_ctx, trained > 0 ? trained : (int) n_ctx));
    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = e->n_ctx;
    cp.n_batch = e->n_ctx;
    cp.n_ubatch = e->n_ctx;   // non-causal models need the whole input in one ubatch
    cp.n_seq_max = 1;
    cp.n_threads = n_threads;
    cp.n_threads_batch = n_threads;
    cp.embeddings = true;
    cp.pooling_type = LLAMA_POOLING_TYPE_UNSPECIFIED; // use the model's own pooling (mean/cls/last)
    cp.abort_callback = embed_abort_cb;
    cp.abort_callback_data = e;
    e->ctx = llama_init_from_model(e->model, cp);
    if (!e->ctx) { g_last_error = "Could not create the embedding context"; free_embedder(e); return 0; }
    if (llama_pooling_type(e->ctx) == LLAMA_POOLING_TYPE_NONE) {
        g_last_error = "This GGUF is not a sentence-embedding model (no pooling)";
        free_embedder(e);
        return 0;
    }
    e->n_embd = llama_model_n_embd_out(e->model);
    if (e->n_embd <= 0) e->n_embd = llama_model_n_embd(e->model);
    return reinterpret_cast<jlong>(e);
}

// Returns an L2-normalised embedding, or null on failure. Input is truncated to the context size.
JNIEXPORT jfloatArray JNICALL
Java_com_offlineagent_engine_llamacpp_LlamaNative_nativeEmbed(JNIEnv * env, jobject, jlong h, jbyteArray jtext) {
    auto * e = reinterpret_cast<Embedder *>(h);
    e->abort.store(false);
    std::vector<llama_token> tokens;
    if (!tokenize(e->vocab, jbytes(env, jtext), tokens) || tokens.empty()) { g_last_error = "Tokenization failed"; return nullptr; }
    if ((int) tokens.size() > e->n_ctx) {
        // keep the trailing EOS/SEP token if the model uses one
        const llama_token last = tokens.back();
        tokens.resize(e->n_ctx);
        tokens.back() = last;
    }
    llama_batch b = llama_batch_init((int32_t) tokens.size(), 0, 1);
    for (size_t i = 0; i < tokens.size(); i++) batch_add(b, tokens[i], (llama_pos) i, true);
    llama_memory_t mem = llama_get_memory(e->ctx);
    if (mem) llama_memory_clear(mem, true);
    int rc = llama_decode(e->ctx, b);
    if (rc != 0 && llama_model_has_encoder(e->model)) rc = llama_encode(e->ctx, b);
    llama_batch_free(b);
    if (rc != 0) { g_last_error = "Embedding failed (code " + std::to_string(rc) + ")"; return nullptr; }
    const float * v = llama_get_embeddings_seq(e->ctx, 0);
    if (!v) { g_last_error = "No pooled embedding returned"; return nullptr; }
    std::vector<float> out(v, v + e->n_embd);
    double norm = 0;
    for (float x : out) norm += (double) x * x;
    norm = std::sqrt(norm);
    if (norm > 0) for (float & x : out) x = (float) (x / norm);
    jfloatArray arr = env->NewFloatArray(e->n_embd);
    env->SetFloatArrayRegion(arr, 0, e->n_embd, out.data());
    return arr;
}

JNIEXPORT jint JNICALL
Java_com_offlineagent_engine_llamacpp_LlamaNative_nativeEmbedderDim(JNIEnv *, jobject, jlong h) {
    return reinterpret_cast<Embedder *>(h)->n_embd;
}

JNIEXPORT void JNICALL
Java_com_offlineagent_engine_llamacpp_LlamaNative_nativeFreeEmbedder(JNIEnv *, jobject, jlong h) {
    free_embedder(reinterpret_cast<Embedder *>(h));
}

JNIEXPORT jstring JNICALL
Java_com_offlineagent_engine_llamacpp_LlamaNative_nativeSystemInfo(JNIEnv * env, jobject) {
    std::string info = llama_print_system_info();
    info += "\nBackends:";
    for (size_t i = 0; i < ggml_backend_reg_count(); i++) {
        info += " ";
        info += ggml_backend_reg_name(ggml_backend_reg_get(i));
    }
    for (size_t i = 0; i < ggml_backend_dev_count(); i++) {
        auto * dev = ggml_backend_dev_get(i);
        info += "\nDevice: ";
        info += ggml_backend_dev_name(dev);
        info += " - ";
        info += ggml_backend_dev_description(dev);
    }
    return env->NewStringUTF(info.c_str());
}

} // extern "C"
