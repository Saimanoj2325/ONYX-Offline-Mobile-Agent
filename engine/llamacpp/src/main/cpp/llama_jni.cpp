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

#include "llama.h"
#include "ggml-backend.h"

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

    std::vector<llama_token> kv_tokens;   // exactly what is in the KV cache (seq 0)
    std::atomic<bool>        abort{false};

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

JNIEXPORT jint JNICALL
Java_com_offlineagent_engine_llamacpp_LlamaNative_nativeBegin(JNIEnv * env, jobject, jlong h, jbyteArray jprompt,
                                                             jfloat temp, jint top_k, jfloat top_p, jfloat min_p,
                                                             jfloat repeat_penalty, jint seed, jint max_new) {
    auto * s = as_session(h);
    s->abort.store(false);
    s->generated = 0;
    s->max_new = max_new;
    s->stop = STOP_NONE;
    s->has_logits = false;

    std::vector<llama_token> tokens;
    if (!tokenize(s->vocab, jbytes(env, jprompt), tokens) || tokens.empty()) {
        g_last_error = "Tokenization failed";
        return BEGIN_ERR_TOKENIZE;
    }
    if ((int) tokens.size() + 8 > s->n_ctx) {
        g_last_error = "Prompt does not fit in the context window";
        return BEGIN_ERR_TOO_LONG;
    }

    // Sampler chain (re-created per request; cheap).
    if (s->sampler) llama_sampler_free(s->sampler);
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

    // KV-cache prefix reuse: multi-turn chat only evaluates the new turn.
    size_t common = 0;
    while (common < s->kv_tokens.size() && common < tokens.size() && s->kv_tokens[common] == tokens[common]) common++;
    if (common == tokens.size()) common--;  // must evaluate >= 1 token to get fresh logits
    llama_memory_t mem = llama_get_memory(s->ctx);
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
            if (rc == 2 || s->abort.load()) return BEGIN_ERR_ABORTED;
            g_last_error = "llama_decode failed during prompt evaluation (code " + std::to_string(rc) + ")";
            return BEGIN_ERR_DECODE;
        }
        s->kv_tokens.insert(s->kv_tokens.end(), tokens.begin() + i, tokens.begin() + end);
    }
    s->has_logits = true;
    return (jint) tokens.size();
}

// Returns the UTF-8 bytes of the next piece, or null when generation ended (see nativeStopReason).
JNIEXPORT jbyteArray JNICALL
Java_com_offlineagent_engine_llamacpp_LlamaNative_nativeNext(JNIEnv * env, jobject, jlong h) {
    auto * s = as_session(h);
    if (s->stop != STOP_NONE) return nullptr;
    if (s->abort.load()) { s->stop = STOP_CANCELLED; return nullptr; }
    if (!s->has_logits) { s->stop = STOP_ERROR; return nullptr; }
    if (s->generated >= s->max_new) { s->stop = STOP_MAX_TOKENS; return nullptr; }
    if ((int) s->kv_tokens.size() >= s->n_ctx - 1) { s->stop = STOP_CONTEXT_FULL; return nullptr; }

    const llama_token tok = llama_sampler_sample(s->sampler, s->ctx, -1);
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
    batch_add(s->batch, tok, (llama_pos) s->kv_tokens.size(), true);
    const int rc = llama_decode(s->ctx, s->batch);
    if (rc != 0) {
        s->has_logits = false;
        s->stop = (rc == 2 || s->abort.load()) ? STOP_CANCELLED : STOP_ERROR;
        if (s->stop == STOP_ERROR) g_last_error = "llama_decode failed during generation (code " + std::to_string(rc) + ")";
        // The sampled token is still returned below so the visible text stays complete;
        // its KV entry may be missing, so drop it from our mirror (next prompt re-evaluates).
        llama_memory_seq_rm(llama_get_memory(s->ctx), 0, (llama_pos) s->kv_tokens.size(), -1);
    } else {
        s->kv_tokens.push_back(tok);
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
