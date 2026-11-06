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
#include <deque>
#include <fstream>
#include <sched.h>
#include <unistd.h>

#include "llama.h"
#include "ggml-backend.h"
#include "ggml-cpu.h"
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
    // Phase 4b: prompt-evaluation progress, read from another thread for the "Reading prompt… n/N" indicator.
    std::atomic<int>         pf_done{0};
    std::atomic<int>         pf_total{0};
    std::atomic<int>         pf_cached{0};

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
    std::vector<llama_logit_bias> logit_bias;  // 0.6.2.3: reasoning-ahead meta-word bias (empty = none)

    int generated  = 0;
    int max_new    = 0;
    int stop       = STOP_NONE;
    bool has_logits = false;

    // ---- Phase 3: long context + speed
    int  n_keep = 0;                 // tokens never discarded by context shifting (system prompt)
    bool can_shift = false;          // KV cache supports position shifting (false for some quantised/SWA caches)
    int  n_shifts = 0;               // context shifts performed in this request (reported to Kotlin)
    ggml_threadpool_t threadpool = nullptr;
    void (*tp_free)(ggml_threadpool_t) = nullptr;

    // Speculative decoding: a small draft model of the same family proposes n_draft tokens greedily, the target
    // verifies them in ONE batched decode and keeps the longest matching prefix (+1 own token). Output is exactly the
    // target model's distribution (each position is sampled from the target).
    llama_model *   draft_model = nullptr;
    llama_context * draft_ctx   = nullptr;
    llama_batch     draft_batch{};
    std::vector<llama_token> draft_kv;  // what is in the draft KV cache
    int  n_draft = 0;                   // 0 = speculation off
    bool spec_active = false;           // this request uses speculation (no grammar, no images)
    llama_token pending = -1;           // sampled by the target but not yet decoded
    std::deque<llama_token> out_queue;  // accepted tokens waiting to be returned by nativeNext
    long spec_drafted = 0, spec_accepted = 0;

    // Phase 6: n-gram / prompt-lookup self-speculation (no second model, no extra RAM). The last n tokens (n from
    // ngram_max down to ngram_min) are looked up earlier in the context; the tokens that followed are proposed as drafts
    // and verified exactly like model drafts. Output is identical to normal decoding (every position is still sampled
    // from the target). Works with grammars (JSON tool args are where it pays off most). Disabled for recurrent /
    // hybrid models (their state cannot be rolled back to reject drafts).
    int  ngram_min = 0, ngram_max = 0, ngram_draft = 0;  // ngram_draft 0 = off
    bool rollback_ok = true;            // KV can drop tail positions (false for recurrent / hybrid memory)
    bool ngram_req = false;             // n-gram drafting active for this request
    int  ngram_rounds = 0;              // rounds that proposed drafts in this request (adaptive auto-off)
    int  ngram_cur = 0;                 // adaptive draft length: grows after full acceptance, shrinks after a miss
    std::vector<int> cpus;              // performance cores the pool is pinned to (for live thread changes)
    int  n_threads = 4;
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
    if (s->draft_batch.token) llama_batch_free(s->draft_batch);
    if (s->draft_ctx) llama_free(s->draft_ctx);
    if (s->draft_model) llama_model_free(s->draft_model);
    if (s->ctx && s->threadpool) llama_detach_threadpool(s->ctx);
    if (s->sampler) llama_sampler_free(s->sampler);
    if (s->grammar) llama_sampler_free(s->grammar);
    if (s->mtmd) mtmd_free(s->mtmd);
    if (s->batch.token) llama_batch_free(s->batch);
    if (s->ctx) llama_free(s->ctx);
    if (s->model) llama_model_free(s->model);
    if (s->threadpool && s->tp_free) s->tp_free(s->threadpool);
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

// CPU backend variant actually loaded ("auto" = best for this CPU, else the forced compatibility variant).
static std::string g_cpu_variant = "none";

JNIEXPORT void JNICALL
Java_com_offlineagent_engine_llamacpp_LlamaNative_nativeInit(JNIEnv * env, jobject, jstring jlibdir, jstring jvariant) {
    if (g_backend_ready) return;
    llama_log_set(log_callback, nullptr);
    mtmd_helper_log_set(log_callback, nullptr);
    const std::string dir = jstr(env, jlibdir);
    const std::string variant = jvariant ? jstr(env, jvariant) : std::string();
    bool loaded = false;
    if (!variant.empty()) {
        // Compatibility mode (after a failed self-test): a conservative kernel set (e.g. android_armv8.2_1 = NEON +
        // dotprod, no SVE/SME/i8mm) instead of the "best" variant for this CPU.
        const std::string path = dir + "/libggml-cpu-" + variant + ".so";
        LOGI("Loading forced CPU backend variant %s", path.c_str());
        loaded = ggml_backend_load(path.c_str()) != nullptr;
        if (loaded) g_cpu_variant = variant; else LOGW("forced variant %s failed; falling back to auto", variant.c_str());
    }
    if (!loaded) {
        // Loads libggml-cpu-android_armv*.so variants and keeps the best one this CPU supports.
        LOGI("Loading ggml backends from %s", dir.c_str());
        ggml_backend_load_all_from_path(dir.c_str());
        g_cpu_variant = "auto";
    }
    llama_backend_init();
    g_backend_ready = true;
}

// Pins the CALLING thread (the Kotlin "llm-engine" thread) to the given CPUs. Threads it creates inherit the mask.
static void pin_current_thread(const std::vector<int> & cpus) {
    if (cpus.empty()) return;
    cpu_set_t set;
    CPU_ZERO(&set);
    for (int c : cpus) if (c >= 0 && c < CPU_SETSIZE) CPU_SET(c, &set);
    if (sched_setaffinity(0, sizeof(set), &set) != 0) LOGW("sched_setaffinity failed (ignored)");
}

// Persistent ggml thread pool pinned to the performance cores. Created through the CPU backend's proc-address table
// because with GGML_BACKEND_DL the CPU backend is a dlopen()ed variant library we cannot link against directly.
static void attach_threadpool(Session * s, int n_threads, const std::vector<int> & cpus, int poll) {
    ggml_backend_reg_t reg = ggml_backend_reg_by_name("CPU");
    if (!reg) return;
    auto tp_new = (ggml_threadpool_t (*)(ggml_threadpool_params *)) ggml_backend_reg_get_proc_address(reg, "ggml_threadpool_new");
    auto tp_free = (void (*)(ggml_threadpool_t)) ggml_backend_reg_get_proc_address(reg, "ggml_threadpool_free");
    if (!tp_new || !tp_free) return;
    ggml_threadpool_params tpp = ggml_threadpool_params_default(n_threads);
    for (int c : cpus) if (c >= 0 && c < GGML_MAX_N_THREADS) tpp.cpumask[c] = true;
    tpp.strict_cpu = false;          // threads may float within the mask (big cores), not one core each
    tpp.poll = (uint32_t) std::max(0, std::min(100, poll)); // short busy-poll between ops: lower token latency
    tpp.prio = GGML_SCHED_PRIO_NORMAL;
    ggml_threadpool_t tp = tp_new(&tpp);
    if (!tp) { LOGW("threadpool creation failed; using default"); return; }
    s->threadpool = tp;
    s->tp_free = tp_free;
    llama_attach_threadpool(s->ctx, tp, tp);
}

// opts: [n_ctx, n_threads, n_batch, n_ubatch, use_mmap, type_k, type_v, flash_attn(-1 auto,0,1), rope_scaling(-1 model,0 none,2 yarn),
//        yarn_orig_ctx, poll, warmup]
// fopts: [rope_freq_scale (0 = model)]
// cpus: performance-core ids to pin to (may be empty)
JNIEXPORT jlong JNICALL
Java_com_offlineagent_engine_llamacpp_LlamaNative_nativeLoad(JNIEnv * env, jobject, jstring jpath,
                                                            jintArray jopts, jfloatArray jfopts, jintArray jcpus) {
    g_last_error.clear();
    const std::string path = jstr(env, jpath);
    jint o[12] = {2048, 4, 512, 512, 1, GGML_TYPE_F16, GGML_TYPE_F16, -1, -1, 0, 0, 1};
    env->GetIntArrayRegion(jopts, 0, std::min<jsize>(12, env->GetArrayLength(jopts)), o);
    jfloat fo[1] = {0.0f};
    if (jfopts) env->GetFloatArrayRegion(jfopts, 0, std::min<jsize>(1, env->GetArrayLength(jfopts)), fo);
    std::vector<int> cpus;
    if (jcpus) { cpus.resize(env->GetArrayLength(jcpus)); env->GetIntArrayRegion(jcpus, 0, (jsize) cpus.size(), cpus.data()); }
    const int n_ctx = o[0], n_threads = o[1], n_batch = o[2], n_ubatch = o[3];
    const bool use_mmap = o[4] != 0;

    if (ggml_backend_dev_count() == 0) {
        g_last_error = "No CPU backend could be loaded for this device (unsupported CPU?)";
        return 0;
    }
    pin_current_thread(cpus);

    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0;                                  // CPU only
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
    const bool yarn = o[8] == LLAMA_ROPE_SCALING_TYPE_YARN;
    // Without RoPE scaling the context cannot exceed what the model was trained for; with YaRN it may (up to 4x).
    const int max_ctx = trained > 0 ? (yarn ? trained * 4 : trained) : n_ctx;
    s->n_ctx = std::max(256, std::min(n_ctx, max_ctx));
    s->n_batch = std::max(32, std::min(n_batch, s->n_ctx));

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = (uint32_t) s->n_ctx;
    cp.n_batch = (uint32_t) s->n_batch;
    cp.n_ubatch = (uint32_t) std::max(32, std::min(n_ubatch, s->n_batch));
    cp.n_seq_max = 1;
    cp.n_threads = n_threads;
    cp.n_threads_batch = n_threads;
    cp.abort_callback = abort_callback;
    cp.abort_callback_data = s;
    cp.no_perf = true;
    cp.type_k = (ggml_type) o[5];
    cp.type_v = (ggml_type) o[6];
    // A quantised V cache requires flash attention; FA on CPU is also faster and smaller at long contexts.
    const bool quant_v = cp.type_v != GGML_TYPE_F16 && cp.type_v != GGML_TYPE_F32;
    cp.flash_attn_type = quant_v ? LLAMA_FLASH_ATTN_TYPE_ENABLED : (llama_flash_attn_type) o[7];
    if (o[8] >= 0) cp.rope_scaling_type = (llama_rope_scaling_type) o[8];
    if (yarn) {
        cp.yarn_orig_ctx = (uint32_t) (o[9] > 0 ? o[9] : trained);
        cp.rope_freq_scale = fo[0] > 0 ? fo[0] : (float) cp.yarn_orig_ctx / (float) s->n_ctx;
    } else if (fo[0] > 0) {
        cp.rope_freq_scale = fo[0];
    }

    s->ctx = llama_init_from_model(s->model, cp);
    if (!s->ctx && cp.flash_attn_type == LLAMA_FLASH_ATTN_TYPE_ENABLED && !quant_v) {
        cp.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_AUTO;
        s->ctx = llama_init_from_model(s->model, cp);
    }
    if (!s->ctx) {
        g_last_error = "Could not create the inference context (likely out of memory; try a smaller context or a q4_0 KV cache)";
        free_session(s);
        return 0;
    }
    s->can_shift = llama_memory_can_shift(llama_get_memory(s->ctx));
    s->rollback_ok = !llama_model_is_recurrent(s->model) && !llama_model_is_hybrid(s->model);
    s->batch = llama_batch_init(s->n_batch, 0, 1);
    attach_threadpool(s, n_threads, cpus, o[10]);
    s->cpus = cpus;
    s->n_threads = n_threads;

    if (o[11]) {
        // Warm-up: one tiny decode pages the weights in (mmap) and builds the compute graph, so the first real
        // token of the first chat is not slowed down by page faults.
        llama_token t = llama_vocab_bos(s->vocab);
        if (t < 0) t = 0;
        batch_clear(s->batch);
        batch_add(s->batch, t, 0, true);
        llama_decode(s->ctx, s->batch);
        llama_memory_clear(llama_get_memory(s->ctx), true);
    }
    LOGI("Model loaded: ctx=%d batch=%d ubatch=%d threads=%d mmap=%d kv=%d/%d fa=%d yarn=%d shift=%d",
         s->n_ctx, s->n_batch, (int) cp.n_ubatch, n_threads, (int) use_mmap, (int) cp.type_k, (int) cp.type_v,
         (int) cp.flash_attn_type, (int) yarn, (int) s->can_shift);
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
    std::string fields[11] = {
        desc,
        std::to_string(llama_model_size(s->model)),
        std::to_string(llama_model_n_params(s->model)),
        std::to_string(llama_model_n_ctx_train(s->model)),
        std::to_string(llama_n_ctx(s->ctx)),
        tmpl ? std::string(tmpl) : std::string(),
        std::to_string(llama_model_n_layer(s->model)),
        std::to_string(llama_model_n_head_kv(s->model)),
        std::to_string(llama_model_n_embd(s->model)),
        std::to_string(llama_model_n_head(s->model)),
        s->can_shift ? "1" : "0",
    };
    jobjectArray arr = env->NewObjectArray(11, env->FindClass("java/lang/String"), nullptr);
    for (int i = 0; i < 11; i++) {
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
    if (!s->logit_bias.empty()) {
        llama_sampler_chain_add(s->sampler, llama_sampler_init_logit_bias(llama_vocab_n_tokens(s->vocab),
            (int32_t) s->logit_bias.size(), s->logit_bias.data()));
    }
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
    s->pf_cached.store((int) common);
    s->pf_total.store((int) (tokens.size() - common));
    s->pf_done.store(0);

    for (size_t i = common; i < tokens.size(); i += s->n_batch) {
        const size_t end = std::min(tokens.size(), i + (size_t) s->n_batch);
        batch_clear(s->batch);
        for (size_t j = i; j < end; j++) batch_add(s->batch, tokens[j], (llama_pos) j, j == tokens.size() - 1);
        const int rc = llama_decode(s->ctx, s->batch);
        if (rc != 0) {
            if (rc == 2 || s->abort.load()) {
                // 0.6.2.3 (typing-time prefill): an abort keeps every batch that finished (llama_decode leaves processed
                // ubatches in memory; we trim anything past our mirror), so a preempted read-ahead or a cancelled
                // prompt does not throw away the cached prefix.
                if (!llama_memory_seq_rm(mem, 0, (llama_pos) s->kv_tokens.size(), -1)) {
                    llama_memory_clear(mem, false);
                    s->kv_tokens.clear();
                }
                s->n_past = (llama_pos) s->kv_tokens.size();
                return BEGIN_ERR_ABORTED;
            }
            // Whatever partially landed in the cache is unknown: drop it all to stay consistent.
            llama_memory_clear(mem, false);
            s->kv_tokens.clear();
            s->n_past = 0;
            g_last_error = "llama_decode failed during prompt evaluation (code " + std::to_string(rc) + ")";
            return BEGIN_ERR_DECODE;
        }
        s->kv_tokens.insert(s->kv_tokens.end(), tokens.begin() + i, tokens.begin() + end);
        s->pf_done.store((int) (end - common));
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
    s->pf_cached.store(0); s->pf_total.store((int) n_tok); s->pf_done.store(0);
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
                                                             jintArray jw, jintArray jh, jint n_keep) {
    auto * s = as_session(h);
    s->abort.store(false);
    s->pf_done.store(0); s->pf_total.store(0); s->pf_cached.store(0);
    s->generated = 0;
    s->max_new = max_new;
    s->stop = STOP_NONE;
    s->has_logits = false;
    s->n_keep = std::max(0, (int) n_keep);
    s->n_shifts = 0;
    s->pending = -1;
    s->out_queue.clear();
    s->spec_drafted = s->spec_accepted = 0;

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
    s->n_keep = std::min(s->n_keep, std::max(0, (int) s->n_past - 1));
    const bool has_grammar = jgrammar != nullptr && env->GetArrayLength(jgrammar) > 0;
    s->ngram_rounds = 0;
    s->ngram_cur = std::max(1, s->ngram_draft / 2);
    s->ngram_req = rc > 0 && s->draft_ctx == nullptr && s->ngram_draft > 0 && s->rollback_ok && !s->kv_dirty;
    s->spec_active = (rc > 0 && s->draft_ctx != nullptr && s->n_draft > 0 && !has_grammar && !s->kv_dirty && s->rollback_ok) || s->ngram_req;
    return rc;
}

// Sample one token honouring the grammar (if any).
static llama_token sample_token(Session * s, int32_t idx = -1) {
    llama_token tok = llama_sampler_sample(s->sampler, s->ctx, idx);
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
    const float * logits = llama_get_logits_ith(s->ctx, idx);
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

static std::string piece_of(Session * s, llama_token tok) {
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
    return piece;
}

static jbyteArray to_jbytes(JNIEnv * env, const std::string & piece) {
    jbyteArray out = env->NewByteArray((jsize) piece.size());
    env->SetByteArrayRegion(out, 0, (jsize) piece.size(), reinterpret_cast<const jbyte *>(piece.data()));
    return out;
}

// Context shifting (llama.cpp "infinite generation"): when the window is full, keep the first n_keep tokens (system
// prompt), discard half of the rest and slide the remainder down. Applied to the draft model's cache too.
// Returns true when there is room for `need` more tokens afterwards.
static bool ensure_room(Session * s, int need) {
    if (s->n_past + need < s->n_ctx) return true;
    if (!s->can_shift || s->kv_dirty) return false;
    const int n_left = (int) s->n_past - s->n_keep;
    const int n_discard = std::max(n_left / 2, need);
    if (n_left <= 1 || n_discard >= n_left) return false;
    llama_memory_t mem = llama_get_memory(s->ctx);
    llama_memory_seq_rm(mem, 0, s->n_keep, s->n_keep + n_discard);
    llama_memory_seq_add(mem, 0, s->n_keep + n_discard, s->n_past, -n_discard);
    if ((int) s->kv_tokens.size() >= s->n_keep + n_discard)
        s->kv_tokens.erase(s->kv_tokens.begin() + s->n_keep, s->kv_tokens.begin() + s->n_keep + n_discard);
    s->n_past -= n_discard;
    s->n_shifts++;
    if (s->draft_ctx) {
        llama_memory_t dm = llama_get_memory(s->draft_ctx);
        if (llama_memory_can_shift(dm) && (int) s->draft_kv.size() >= s->n_keep + n_discard) {
            const int dn = (int) s->draft_kv.size();
            llama_memory_seq_rm(dm, 0, s->n_keep, s->n_keep + n_discard);
            llama_memory_seq_add(dm, 0, s->n_keep + n_discard, dn, -n_discard);
            s->draft_kv.erase(s->draft_kv.begin() + s->n_keep, s->draft_kv.begin() + s->n_keep + n_discard);
        } else {
            llama_memory_clear(dm, false);
            s->draft_kv.clear();
        }
    }
    LOGI("context shift: discarded %d tokens (keep=%d, now %d)", n_discard, s->n_keep, (int) s->n_past);
    return s->n_past + need < s->n_ctx;
}

// Brings the draft model's KV cache in line with the target's (common prefix reuse, then batched catch-up).
static bool sync_draft(Session * s) {
    llama_memory_t dm = llama_get_memory(s->draft_ctx);
    size_t common = 0;
    while (common < s->draft_kv.size() && common < s->kv_tokens.size() && s->draft_kv[common] == s->kv_tokens[common]) common++;
    if (!llama_memory_seq_rm(dm, 0, (llama_pos) common, -1)) { llama_memory_clear(dm, false); common = 0; }
    s->draft_kv.resize(common);
    const int nb = s->n_batch;
    for (size_t i = common; i < s->kv_tokens.size(); i += nb) {
        const size_t end = std::min(s->kv_tokens.size(), i + (size_t) nb);
        batch_clear(s->draft_batch);
        for (size_t j = i; j < end; j++) batch_add(s->draft_batch, s->kv_tokens[j], (llama_pos) j, false);
        if (llama_decode(s->draft_ctx, s->draft_batch) != 0) {
            llama_memory_clear(dm, false);
            s->draft_kv.clear();
            return false;
        }
        s->draft_kv.insert(s->draft_kv.end(), s->kv_tokens.begin() + i, s->kv_tokens.begin() + end);
    }
    return true;
}

static llama_token argmax_logits(const float * logits, int n_vocab) {
    int best = 0;
    for (int i = 1; i < n_vocab; i++) if (logits[i] > logits[best]) best = i;
    return best;
}

// Phase 6: prompt-lookup drafts. History = kv_tokens + t0. Finds the most recent earlier occurrence of the last n tokens
// (longest n first) within the last 4096 tokens and proposes what followed it.
static void ngram_drafts(const Session * s, llama_token t0, int n_max_draft, std::vector<llama_token> & out) {
    const std::vector<llama_token> & h = s->kv_tokens;
    const size_t L = h.size() + 1;
    auto at = [&](size_t i) { return i < h.size() ? h[i] : t0; };
    const size_t lo = L > 4096 ? L - 4096 : 0;
    for (int n = s->ngram_max; n >= s->ngram_min && n >= 1; n--) {
        if (L < (size_t) n + 1) continue;
        const size_t key = L - (size_t) n;
        for (size_t i = key; i-- > lo;) {
            bool m = true;
            for (int j = 0; j < n; j++) if (at(i + j) != at(key + j)) { m = false; break; }
            if (!m) continue;
            for (size_t q = i + n; q < L && (int) out.size() < n_max_draft; q++) out.push_back(at(q));
            if (!out.empty()) return;
        }
    }
}

// One speculative round. Fills out_queue with >= 1 accepted token. Returns false on a fatal decode error.
static bool spec_round(Session * s) {
    if (s->pending < 0) s->pending = sample_token(s, -1);
    const llama_token t0 = s->pending;
    s->pending = -1;
    if (llama_vocab_is_eog(s->vocab, t0)) { s->out_queue.push_back(t0); return true; }

    int n_draft = std::min(s->draft_ctx ? s->n_draft : (s->ngram_req ? s->ngram_cur : 0), std::max(0, s->max_new - s->generated - 1));
    if (!ensure_room(s, n_draft + 2)) n_draft = std::max(0, s->n_ctx - (int) s->n_past - 2);
    if ((int) s->n_past + 1 >= s->n_ctx) { s->stop = STOP_CONTEXT_FULL; return true; }

    std::vector<llama_token> drafts;
    if (!s->draft_ctx) {
        if (n_draft > 0) ngram_drafts(s, t0, n_draft, drafts);
        if (!drafts.empty()) s->ngram_rounds++;
    } else if (n_draft > 0 && sync_draft(s)) {
        const int n_vocab = llama_vocab_n_tokens(llama_model_get_vocab(s->draft_model));
        llama_token cur = t0;
        llama_pos pos = s->n_past;
        for (int i = 0; i < n_draft; i++) {
            batch_clear(s->draft_batch);
            batch_add(s->draft_batch, cur, pos, true);
            if (llama_decode(s->draft_ctx, s->draft_batch) != 0) break;
            s->draft_kv.push_back(cur);
            pos++;
            const llama_token d = argmax_logits(llama_get_logits_ith(s->draft_ctx, -1), n_vocab);
            drafts.push_back(d);
            cur = d;
            if (llama_vocab_is_eog(s->vocab, d)) break;
        }
    }

    batch_clear(s->batch);
    batch_add(s->batch, t0, s->n_past, true);
    for (size_t j = 0; j < drafts.size(); j++) batch_add(s->batch, drafts[j], s->n_past + 1 + (llama_pos) j, true);
    const int rc = llama_decode(s->ctx, s->batch);
    if (rc != 0) {
        s->has_logits = false;
        s->stop = (rc == 2 || s->abort.load()) ? STOP_CANCELLED : STOP_ERROR;
        if (s->stop == STOP_ERROR) g_last_error = "llama_decode failed during generation (code " + std::to_string(rc) + ")";
        llama_memory_seq_rm(llama_get_memory(s->ctx), 0, s->n_past, -1);
        s->out_queue.push_back(t0);
        return false;
    }
    // Verify: sample the TARGET at every position; keep drafts while they agree.
    size_t k = 0;
    llama_token next;
    while (true) {
        next = sample_token(s, (int32_t) k);
        if (k < drafts.size() && next == drafts[k]) { k++; continue; }
        break;
    }
    const llama_pos old_past = s->n_past;
    llama_memory_seq_rm(llama_get_memory(s->ctx), 0, old_past + (llama_pos) k + 1, -1);
    s->kv_tokens.push_back(t0);
    for (size_t j = 0; j < k; j++) s->kv_tokens.push_back(drafts[j]);
    s->n_past = old_past + (llama_pos) k + 1;
    // Draft cache: drop rejected positions.
    if (s->draft_ctx) {
        llama_memory_seq_rm(llama_get_memory(s->draft_ctx), 0, s->n_past, -1);
        if ((llama_pos) s->draft_kv.size() > s->n_past) s->draft_kv.resize(s->n_past);
    }
    s->pending = next;
    s->has_logits = true;
    s->spec_drafted += (long) drafts.size();
    s->spec_accepted += (long) k;
    if (!s->draft_ctx && !drafts.empty()) {
        s->ngram_cur = (k == drafts.size()) ? std::min(s->ngram_draft, s->ngram_cur + 2) : std::max(1, (int) k + 1);
    }
    // Adaptive auto-off: when n-gram drafts keep missing (free-form prose), stop paying for the wider verify batch.
    if (!s->draft_ctx && s->ngram_req && s->ngram_rounds >= 8 && s->spec_accepted * 5 < s->spec_drafted) s->ngram_req = false;
    s->out_queue.push_back(t0);
    for (size_t j = 0; j < k; j++) s->out_queue.push_back(drafts[j]);
    return true;
}

// Returns the UTF-8 bytes of the next piece, or null when generation ended (see nativeStopReason).
JNIEXPORT jbyteArray JNICALL
Java_com_offlineagent_engine_llamacpp_LlamaNative_nativeNext(JNIEnv * env, jobject, jlong h) {
    auto * s = as_session(h);
    if (s->stop != STOP_NONE && s->out_queue.empty()) return nullptr;
    if (s->abort.load()) { s->stop = STOP_CANCELLED; s->out_queue.clear(); return nullptr; }
    if (s->generated >= s->max_new) { s->stop = STOP_MAX_TOKENS; s->out_queue.clear(); return nullptr; }

    if (s->spec_active) {
        if (s->out_queue.empty()) {
            if (!s->has_logits && s->pending < 0) { s->stop = STOP_ERROR; return nullptr; }
            spec_round(s);
            if (s->out_queue.empty()) { if (s->stop == STOP_NONE) s->stop = STOP_ERROR; return nullptr; }
        }
        const llama_token tok = s->out_queue.front();
        s->out_queue.pop_front();
        if (llama_vocab_is_eog(s->vocab, tok)) { s->stop = STOP_END_OF_TURN; s->out_queue.clear(); return nullptr; }
        s->generated++;
        return to_jbytes(env, piece_of(s, tok));
    }

    if (!s->has_logits) { s->stop = STOP_ERROR; return nullptr; }
    if (!ensure_room(s, 1)) { s->stop = STOP_CONTEXT_FULL; return nullptr; }

    const llama_token tok = sample_token(s);
    if (llama_vocab_is_eog(s->vocab, tok)) { s->stop = STOP_END_OF_TURN; return nullptr; }
    const std::string piece = piece_of(s, tok);

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
    return to_jbytes(env, piece);
}

// Phase 4b: [evaluated, to evaluate, reused from the KV cache] of the prompt being read right now. Safe to call from any
// thread while the session is alive (atomics only).
JNIEXPORT jintArray JNICALL
Java_com_offlineagent_engine_llamacpp_LlamaNative_nativePrefillProgress(JNIEnv * env, jobject, jlong h) {
    jint v[3] = {0, 0, 0};
    if (h) {
        auto * s = as_session(h);
        v[0] = s->pf_done.load(); v[1] = s->pf_total.load(); v[2] = s->pf_cached.load();
    }
    jintArray arr = env->NewIntArray(3);
    env->SetIntArrayRegion(arr, 0, 3, v);
    return arr;
}

// [n_past, n_ctx, n_shifts, spec_drafted, spec_accepted, kv_tokens]
JNIEXPORT jintArray JNICALL
Java_com_offlineagent_engine_llamacpp_LlamaNative_nativeStats(JNIEnv * env, jobject, jlong h) {
    auto * s = as_session(h);
    jint v[6] = {(jint) s->n_past, s->n_ctx, s->n_shifts, (jint) s->spec_drafted, (jint) s->spec_accepted, (jint) s->kv_tokens.size()};
    jintArray arr = env->NewIntArray(6);
    env->SetIntArrayRegion(arr, 0, 6, v);
    return arr;
}

// 0.6.2.3 reading ahead: evaluates [prompt] into the KV cache (longest common prefix kept, only the tail computed) and
// samples nothing. Returns [prompt tokens, reused from the cache, evaluated now] or [negative error code]. An abort keeps
// the batches that finished (see eval_text), so a preempted prefill still saves work.
JNIEXPORT jintArray JNICALL
Java_com_offlineagent_engine_llamacpp_LlamaNative_nativePrefill(JNIEnv * env, jobject, jlong h, jbyteArray jprompt, jint n_keep) {
    auto * s = as_session(h);
    s->abort.store(false);
    s->pf_done.store(0); s->pf_total.store(0); s->pf_cached.store(0);
    jint out[3] = {0, 0, 0};
    std::vector<llama_token> tokens;
    if (!tokenize(s->vocab, jbytes(env, jprompt), tokens) || tokens.empty()) out[0] = BEGIN_ERR_TOKENIZE;
    else if ((int) tokens.size() + 8 > s->n_ctx) out[0] = BEGIN_ERR_TOO_LONG;
    else {
        size_t common = 0;
        if (!s->kv_dirty) while (common < s->kv_tokens.size() && common < tokens.size() && s->kv_tokens[common] == tokens[common]) common++;
        if (common == tokens.size()) {
            out[0] = (jint) tokens.size(); out[1] = (jint) common; out[2] = 0;   // already there: nothing to do
        } else {
            const jint rc = eval_text(s, tokens);
            out[0] = rc; out[1] = s->pf_cached.load(); out[2] = rc > 0 ? (jint) tokens.size() - out[1] : s->pf_done.load();
        }
    }
    s->has_logits = false;              // nothing was set up for sampling: the next nativeBegin re-evaluates >= 1 token
    s->n_keep = std::max(0, std::min((int) n_keep, (int) s->n_past - 1));
    jintArray arr = env->NewIntArray(3);
    env->SetIntArrayRegion(arr, 0, 3, out);
    return arr;
}

// 0.6.2.3: token ids of a text (no BOS), for the reasoning-ahead logit bias.
JNIEXPORT jintArray JNICALL
Java_com_offlineagent_engine_llamacpp_LlamaNative_nativeTokenIds(JNIEnv * env, jobject, jlong h, jbyteArray jtext) {
    auto * s = as_session(h);
    const std::string text = jbytes(env, jtext);
    int n = -llama_tokenize(s->vocab, text.data(), (int32_t) text.size(), nullptr, 0, false, true);
    std::vector<llama_token> ids(std::max(0, n));
    if (n > 0) llama_tokenize(s->vocab, text.data(), (int32_t) text.size(), ids.data(), n, false, true);
    jintArray arr = env->NewIntArray((jsize) ids.size());
    if (!ids.empty()) env->SetIntArrayRegion(arr, 0, (jsize) ids.size(), reinterpret_cast<const jint *>(ids.data()));
    return arr;
}

// 0.6.2.3: logit bias applied by the samplers of the following nativeBegin calls (empty array = clear).
JNIEXPORT void JNICALL
Java_com_offlineagent_engine_llamacpp_LlamaNative_nativeSetLogitBias(JNIEnv * env, jobject, jlong h, jintArray jids, jfloat bias) {
    auto * s = as_session(h);
    s->logit_bias.clear();
    if (jids == nullptr) return;
    const jsize n = env->GetArrayLength(jids);
    std::vector<jint> ids(n);
    if (n > 0) env->GetIntArrayRegion(jids, 0, n, ids.data());
    for (jint id : ids) if (id >= 0 && id < llama_vocab_n_tokens(s->vocab)) s->logit_bias.push_back(llama_logit_bias{(llama_token) id, bias});
}

// Exact token count of a text under this model's tokenizer (for the chat token counter and context budgeting).
JNIEXPORT jint JNICALL
Java_com_offlineagent_engine_llamacpp_LlamaNative_nativeCountTokens(JNIEnv * env, jobject, jlong h, jbyteArray jtext) {
    auto * s = as_session(h);
    const std::string text = jbytes(env, jtext);
    const int n = -llama_tokenize(s->vocab, text.data(), (int32_t) text.size(), nullptr, 0, false, true);
    return n < 0 ? 0 : n;
}

// Prompt-prefix KV cache on disk (system prompt + skill instructions). If the live KV cache already starts with the
// prefix nothing happens; else a saved state file for exactly these tokens is loaded; else the prefix is evaluated
// once and saved. Returns: 0 already cached, 1 loaded from disk, 2 evaluated+saved, <0 error.
JNIEXPORT jint JNICALL
Java_com_offlineagent_engine_llamacpp_LlamaNative_nativePrefixCache(JNIEnv * env, jobject, jlong h, jbyteArray jprefix,
                                                                   jstring jpath, jboolean save) {
    auto * s = as_session(h);
    s->abort.store(false);
    s->pf_done.store(0); s->pf_total.store(0); s->pf_cached.store(0);
    std::vector<llama_token> tokens;
    if (!tokenize(s->vocab, jbytes(env, jprefix), tokens) || tokens.size() < 2) return -1;
    if ((int) tokens.size() + 64 > s->n_ctx) return -2;
    if (!s->kv_dirty && s->kv_tokens.size() >= tokens.size() &&
        std::equal(tokens.begin(), tokens.end(), s->kv_tokens.begin())) return 0;
    const std::string path = jstr(env, jpath);
    llama_memory_t mem = llama_get_memory(s->ctx);
    if (!path.empty()) {
        std::vector<llama_token> got(tokens.size() + 8);
        size_t n_got = 0;
        std::ifstream probe(path, std::ios::binary);
        if (probe.good()) {
            probe.close();
            llama_memory_clear(mem, false);
            const size_t rd = llama_state_seq_load_file(s->ctx, path.c_str(), 0, got.data(), got.size(), &n_got);
            if (rd > 0 && n_got == tokens.size() && std::equal(tokens.begin(), tokens.end(), got.begin())) {
                s->kv_tokens = tokens;
                s->kv_dirty = false;
                s->n_past = (llama_pos) tokens.size();
                return 1;
            }
            llama_memory_clear(mem, false);
            s->kv_tokens.clear();
            s->n_past = 0;
            unlink(path.c_str());   // stale (other model / other prompt): rebuild below
        }
    }
    const jint rc = eval_text(s, tokens);
    if (rc < 0) return -3;
    if (save && !path.empty()) {
        // eval_text re-evaluates the last token if everything was cached; the KV now holds exactly `tokens`.
        if (llama_state_seq_save_file(s->ctx, path.c_str(), 0, s->kv_tokens.data(), s->kv_tokens.size()) == 0) {
            LOGW("could not save prefix cache %s", path.c_str());
            return 3;
        }
    }
    return 2;
}

// Phase 6: change the thread count / busy-poll level live (thermal, battery saver, low battery) without reloading.
// Must be called between requests (Kotlin calls it on the engine thread under the adapter mutex).
JNIEXPORT jboolean JNICALL
Java_com_offlineagent_engine_llamacpp_LlamaNative_nativeSetThreads(JNIEnv *, jobject, jlong h, jint n_threads, jint poll) {
    auto * s = as_session(h);
    const int n = std::max(1, std::min((int) n_threads, 8));
    if (s->threadpool) {
        llama_detach_threadpool(s->ctx);
        if (s->draft_ctx) llama_detach_threadpool(s->draft_ctx);
        if (s->tp_free) s->tp_free(s->threadpool);
        s->threadpool = nullptr;
    }
    llama_set_n_threads(s->ctx, n, n);
    if (s->draft_ctx) llama_set_n_threads(s->draft_ctx, n, n);
    attach_threadpool(s, n, s->cpus, poll);
    if (s->draft_ctx && s->threadpool) llama_attach_threadpool(s->draft_ctx, s->threadpool, s->threadpool);
    s->n_threads = n;
    LOGI("threads -> %d (poll %d)", n, (int) poll);
    return JNI_TRUE;
}

// Phase 6: n-gram self-speculation. n_draft = 0 turns it off. Returns false when the model cannot roll back its state
// (recurrent / hybrid architectures): speculation then stays off.
JNIEXPORT jboolean JNICALL
Java_com_offlineagent_engine_llamacpp_LlamaNative_nativeSetNgram(JNIEnv *, jobject, jlong h, jint n_min, jint n_max, jint n_draft) {
    auto * s = as_session(h);
    if (!s->rollback_ok) { s->ngram_draft = 0; return n_draft <= 0 ? JNI_TRUE : JNI_FALSE; }
    s->ngram_min = std::max(1, (int) n_min);
    s->ngram_max = std::max(s->ngram_min, (int) n_max);
    s->ngram_draft = std::max(0, std::min((int) n_draft, 16));
    return JNI_TRUE;
}

// Speculative decoding: loads a small draft model (same tokenizer family) next to the target. n_draft=0 detaches it.
JNIEXPORT jboolean JNICALL
Java_com_offlineagent_engine_llamacpp_LlamaNative_nativeAttachDraft(JNIEnv * env, jobject, jlong h, jstring jpath,
                                                                   jint n_draft, jint n_threads, jint type_k) {
    auto * s = as_session(h);
    if (s->draft_batch.token) { llama_batch_free(s->draft_batch); s->draft_batch = {}; }
    if (s->draft_ctx) { llama_free(s->draft_ctx); s->draft_ctx = nullptr; }
    if (s->draft_model) { llama_model_free(s->draft_model); s->draft_model = nullptr; }
    s->draft_kv.clear();
    s->n_draft = 0;
    if (n_draft <= 0 || jpath == nullptr) return JNI_TRUE;
    const std::string path = jstr(env, jpath);
    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0;
    mp.load_mode = LLAMA_LOAD_MODE_MMAP;
    s->draft_model = llama_model_load_from_file(path.c_str(), mp);
    if (!s->draft_model) { g_last_error = "Could not load the draft model"; return JNI_FALSE; }
    const llama_vocab * dv = llama_model_get_vocab(s->draft_model);
    // Same tokenizer required: compare vocab size (allowing padding) and a sample of token texts.
    const int nt = llama_vocab_n_tokens(s->vocab), nd = llama_vocab_n_tokens(dv);
    bool ok = std::abs(nt - nd) <= 128;
    for (int i = 0; ok && i < std::min(nt, nd); i += 997) {
        const char * a = llama_vocab_get_text(s->vocab, i);
        const char * b = llama_vocab_get_text(dv, i);
        ok = a && b && std::strcmp(a, b) == 0;
    }
    if (!ok) {
        llama_model_free(s->draft_model); s->draft_model = nullptr;
        g_last_error = "The draft model's tokenizer does not match the chat model (use Qwen3-0.6B for Qwen3 models)";
        return JNI_FALSE;
    }
    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = (uint32_t) s->n_ctx;
    cp.n_batch = (uint32_t) s->n_batch;
    cp.n_ubatch = (uint32_t) s->n_batch;
    cp.n_seq_max = 1;
    cp.n_threads = n_threads;
    cp.n_threads_batch = n_threads;
    cp.no_perf = true;
    cp.type_k = (ggml_type) type_k;
    cp.type_v = (ggml_type) type_k;
    cp.flash_attn_type = (type_k == GGML_TYPE_F16) ? LLAMA_FLASH_ATTN_TYPE_AUTO : LLAMA_FLASH_ATTN_TYPE_ENABLED;
    cp.abort_callback = abort_callback;
    cp.abort_callback_data = s;
    s->draft_ctx = llama_init_from_model(s->draft_model, cp);
    if (!s->draft_ctx) {
        llama_model_free(s->draft_model); s->draft_model = nullptr;
        g_last_error = "Could not create the draft context (out of memory?)";
        return JNI_FALSE;
    }
    if (s->threadpool) llama_attach_threadpool(s->draft_ctx, s->threadpool, s->threadpool);
    s->draft_batch = llama_batch_init(s->n_batch, 0, 1);
    s->n_draft = std::max(1, std::min((int) n_draft, 16));
    LOGI("draft model attached, n_draft=%d", s->n_draft);
    return JNI_TRUE;
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
    info += "\nCPU variant: " + g_cpu_variant;
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
