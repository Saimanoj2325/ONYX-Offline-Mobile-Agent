# Architecture

Phase 1 delivers the on-device inference foundation. Module boundaries are already drawn for the
agent runtime, tools, workflow builder and persistence that come later.

```
┌───────────────────────────── :app (Android, Compose M3) ─────────────────────────────┐
│ MainActivity ── ModelScreen / ChatScreen ── MainViewModel (StateFlow<UiState>)         │
│ OfflineAgentApp (manual DI root, onTrimMemory → unload) · data/ModelRepository (SAF)   │
└──────────────┬───────────────────────────────────────────────┬────────────────────────┘
               │ InferenceAdapter (interface)                   │ (Phase 3+) AgentRuntime
┌──────────────▼──────────────── :core:inference (pure Kotlin/JVM) ──────────────────────┐
│ InferenceAdapter · TokenEngine · EngineInferenceAdapter (state machine, threading)     │
│ Qwen3ChatTemplate · ThinkSplitter · Utf8StreamDecoder · ThreadPolicy · PromptFitter    │
│ GgufFile · data types (LoadConfig, ModelInfo, GenerationRequest/Event/Stats, EngineState)│
└──────────────▲─────────────────────────────────────────────────────────────────────────┘
               │ implements TokenEngine
┌──────────────┴──────── :engine:llamacpp (Android library + NDK) ───────────────────────┐
│ LlamaCppEngine (Kotlin) → LlamaNative (JNI decls) → llama_jni.cpp → llama.cpp/ggml      │
│ libllama.so · libggml*.so · libggml-cpu-android_armv*.so (runtime-selected CPU variant)│
└─────────────────────────────────────────────────────────────────────────────────────────┘
third_party/llama.cpp (vendored, pinned b11609)   tools/host-harness (dev-only JVM smoke test)
```

## Modules

| module | type | depends on | responsibility |
|---|---|---|---|
| `:core:inference` | Kotlin/JVM | coroutines | Engine-agnostic contracts + all logic that can be unit-tested without a phone |
| `:engine:llamacpp` | Android lib + CMake | `:core:inference` | The only place that knows llama.cpp exists |
| `:app` | Android app | both above | UI, model provisioning, lifecycle, DI |
| `:tools:host-harness` | Kotlin/JVM (opt-in, `-PhostHarness`) | `:core:inference`, engine classes | Real-model smoke test on an ARM64/x86 Linux host |

Reserved (commented in `settings.gradle.kts`), so later phases slot in without reshuffling:

| future module | phase | notes |
|---|---|---|
| `:core:runtime` | 3–4 | Agent loop, tool registry, workflow executor. Pure Kotlin; talks only to `InferenceAdapter`. |
| `:core:data` | 5 | Room DB: conversations, workflows, memory. `app` will swap its in-memory chat list for a repository. |
| `:feature:workflow` | 6 | Visual node workflow builder (Compose). Depends on `:core:runtime` models, never on an engine. |

Rule: **nothing above `:core:inference` may import from an `:engine:*` module** except the DI root (`OfflineAgentApp`).

## Key contracts (`core/inference`)

```kotlin
interface InferenceAdapter {
    val state: StateFlow<EngineState>          // Unloaded(reason) | Loading | Ready(model) | Generating | Failed
    suspend fun load(config: LoadConfig): ModelInfo
    fun generate(request: GenerationRequest): Flow<GenerationEvent>  // PromptProcessed, Token*, Completed(stats)
    fun cancel()                               // Stop button; any thread
    suspend fun unload(reason: UnloadReason)
    suspend fun systemInfo(): String
}
interface TokenEngine {                        // what an engine binding provides; blocking, single-threaded
    fun load(config): ModelInfo
    fun beginCompletion(prompt, sampling, maxNewTokens): Int   // returns prompt tokens; reuses KV prefix
    fun nextToken(): TokenStep                 // Piece(bytes) | End(reason)
    fun requestAbort()                         // thread-safe
    fun unload(); fun systemInfo(): String
}
```

`EngineInferenceAdapter` turns any `TokenEngine` into an `InferenceAdapter`: it owns the state machine, the single engine
dispatcher, the mutex, cancellation, prompt rendering (`PromptTemplate`), context-overflow trimming, UTF-8 reassembly and
tokens/sec timing. That is why a fake engine is enough to unit-test nearly all behaviour.

### State machine

```
Unloaded ──load──▶ Loading ──ok──▶ Ready ──generate──▶ Generating ──done/stop/error──▶ Ready
   ▲                  └──fail──▶ Failed ──load──▶ Loading                                 │
   └──────────────────────────── unload(USER | LOW_MEMORY | SHUTDOWN) ◀────────────────────┘
```

## Threading

| thread | does |
|---|---|
| main | Compose only. Never calls into JNI. |
| `llm-engine` (single, 16 MB stack) | every JNI call: load, tokenize, decode, sample, unload |
| ggml worker threads (n_threads − 1) | matmuls inside `llama_decode`, spawned by ggml's threadpool |
| `Dispatchers.IO` | SAF copy of the GGUF into internal storage |
| any | `cancel()` → atomic flags (Kotlin + native) |

## Generation data flow

1. `ChatScreen` → `MainViewModel.send()` builds `[system] + history + user` as `ChatMessage`s.
2. `EngineInferenceAdapter.generate()` renders with `Qwen3ChatTemplate` (thinking on/off), on `llm-engine`.
3. `llama_jni.cpp nativeBegin`: tokenize (parse special tokens) → find common prefix with KV mirror →
   `llama_memory_seq_rm` the rest → `llama_decode` the suffix in 512-token batches (abortable).
4. Loop `nativeNext`: sample (penalties → top-k → top-p → min-p → temp → dist) → EOG? stop : piece bytes → decode 1 token.
5. Kotlin `Utf8StreamDecoder` joins partial multi-byte pieces; `ThinkSplitter` separates `<think>` from the answer.
6. `GenerationEvent.Token` carries running tokens/sec; `Completed` carries `GenerationStats` (decode tok/s excludes prompt time).

## Memory & lifecycle

- One adapter per process (`OfflineAgentApp`). Model weights mmapped; KV cache f16 (≈ 224 MiB for Qwen3-0.6B at 2048 ctx: 28 layers × 8 KV heads × 128 dims × K+V × 2 bytes = 112 KiB per token).
- `onTrimMemory(RUNNING_CRITICAL | ≥ BACKGROUND)` and `onLowMemory()` → `unload(LOW_MEMORY)`; UI shows a snackbar and a Load button.
  `UI_HIDDEN` (brief app switch) does not unload.
- Load failure → `EngineState.Failed(message)` shown inline on the Model screen with "Retry load".
- `android:largeHeap` is set only to give the Java side headroom; native allocations are not limited by it.

## Offline guarantee

No `INTERNET` permission (explicitly removed in the manifest), no networking code, no cloud fallback. See DECISIONS D9 and
DEVICE_TEST_PLAN §3 for the airplane-mode test.
