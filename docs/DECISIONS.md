# Decisions log

Each entry: what was decided, why, and what it would take to revisit it.

## D1. QVAC Kotlin SDK does not exist → llama.cpp via JNI behind `InferenceAdapter` (approved by Sai, 14 Oct 2026)

- The spec named `io.tether:qvac-sdk-android:0.20.1`. That artifact is **not on Maven Central** (or any public Maven repo we checked),
  and QVAC's own docs only support Android through **Expo / React Native** (the JS SDK). There is no native Kotlin/JVM SDK to depend on.
- Decision: use **llama.cpp** (the same GGUF runtime family QVAC itself builds on) through our own small JNI bridge.
- Isolation: the app and future agent runtime only see `core:inference`'s `InferenceAdapter` / `TokenEngine` interfaces.
  Swapping engines = add a new `:engine:<name>` module implementing `TokenEngine` (or `InferenceAdapter`) and change one line in
  `OfflineAgentApp.onCreate()`. No UI or runtime code changes.
- Revisit when: Tether publishes a real Android (Kotlin/AAR) QVAC SDK.

## D2. llama.cpp version pin: `b11609` (commit `fcac19079f3f2985fe74ed845f0880e7e9a8030a`, 14 Oct 2026)

- Vendored as plain source in `third_party/llama.cpp` (shallow clone of the tag, `.git` removed). Version recorded in
  `third_party/LLAMA_CPP_VERSION`.
- Note: the repo also has `v0.x` "release" tags (latest `v0.6.0`); the `bNNNNN` build tags are the canonical per-commit releases and
  `b11609` was the newest at build time. Reference used for the bridge: `examples/llama.android` at that tag.
- Our JNI (`engine/llamacpp/src/main/cpp/llama_jni.cpp`) uses only the **public C API (`llama.h`, `ggml-backend.h`)**, not the
  unstable `common/` C++ helpers, so tag upgrades rarely break it. Upgrade = replace the folder, rebuild, run the host harness.

## D3. CPU features: llama.cpp Android CPU variants loaded at runtime (not a single `-march`)

- Build flags: `GGML_NATIVE=OFF`, `GGML_BACKEND_DL=ON`, `GGML_CPU_ALL_VARIANTS=ON`, `GGML_OPENMP=OFF`, `GGML_LLAMAFILE=OFF`,
  `CMAKE_BUILD_TYPE=Release` (`-O3 -DNDEBUG`) **even inside the debug APK**.
- This ships 7 CPU backend libraries, each compiled for one Arm feature level:

  | library | `-march` | typical SoCs |
  |---|---|---|
  | `libggml-cpu-android_armv8.0_1.so` | `armv8-a` | very old / budget (Cortex-A53 only) |
  | `libggml-cpu-android_armv8.2_1.so` | `armv8.2-a+dotprod` | |
  | `libggml-cpu-android_armv8.2_2.so` | `armv8.2-a+dotprod+fp16` | Snapdragon 7xx/8xx 2019-21, Dimensity 700-1200, Helio G-series |
  | `libggml-cpu-android_armv8.6_1.so` | `armv8.6-a+dotprod+fp16+i8mm` | Snapdragon 7/8 Gen 1+, Dimensity 7000/8000/9000 (Armv9 cores) |
  | `libggml-cpu-android_armv9.0_1.so` | `+sve2` | |
  | `libggml-cpu-android_armv9.2_1.so` / `_2` | `+sve(+sve2)+sme` | 2025+ flagships |

- At startup `ggml_backend_load_all_from_path(nativeLibraryDir)` scores each variant against the CPU's real features and keeps the
  best one that is supported. Result: modern mid-range phones get dotprod/fp16/i8mm kernels (big speed-up for Q8_0/Q4 matmuls), and an
  old armv8.0 phone still runs instead of crashing with `SIGILL`. This is strictly better than hard-coding `armv8.2-a+dotprod+fp16`.
- Cost: ~6.3 MB of extra stripped `.so` in the APK. Acceptable.
- Requires `jniLibs.useLegacyPackaging = true` (libraries extracted to disk) so they can be `dlopen`ed from `nativeLibraryDir`.
- OpenMP is off: ggml's own thread pool is used (one fewer runtime dependency; the reference example turned it on, but we keep
  thread control in our hands).
- ABIs: **arm64-v8a only**. x86_64 (emulator) was skipped: the sandbox build machine is arm64 and emulator LLM speed is meaningless.
  Adding it later is one `abiFilters` line plus an x86 branch in `CMakeLists.txt`.

## D4. Threads = performance cores, fallback 4

- `ThreadPolicy.performanceCores()` reads `cpuinfo_max_freq` for every core and counts cores faster than the slowest cluster
  (e.g. 1+3+4 → 4; 2+6 → 2). Uniform clusters → `min(cores, 4)`. Unreadable sysfs → 4. Clamped 1..8.
- Why: decoding is memory-bound and every thread waits for the slowest one at each sync point, so adding efficiency cores usually
  makes tokens/sec *worse* and heats the phone. User can override in the Model screen (−/+), applied on next Load.

## D5. Single engine thread + Flow streaming + cooperative cancel

- One dedicated thread `llm-engine` (16 MB stack) via `Executors.newSingleThreadExecutor().asCoroutineDispatcher()`.
  Every JNI call except `nativeAbort` runs there. A `Mutex` serialises load/generate/unload; a second `generate` while busy fails
  fast with `BusyException` instead of queueing.
- Stop button → `InferenceAdapter.cancel()` → sets an atomic flag in Kotlin **and** in native code. Native `abort_callback`
  (`llama_context_params.abort_callback`) makes an in-progress `llama_decode` (long prompt) return early; the token loop checks the
  flag between tokens. Cancelling the collecting coroutine does the same via `abortOnCancel`.
- UI never blocks: Compose collects `StateFlow`s; tokens are applied to state as they arrive (Compose coalesces to frames).

## D6. Model loading: mmap, internal storage, one model kept

- `llama_model_params.load_mode = LLAMA_LOAD_MODE_MMAP`; CPU only (`n_gpu_layers = 0`). Weights are paged in on demand and can be
  dropped by the kernel under pressure without killing us.
- SAF URIs are not file paths, so the picked file is **copied** into `filesDir/models/` (progress bar, cancellable, GGUF magic +
  version validated on the first 8 bytes, free-space check, `.part` file renamed on success, stale `.part` cleaned at startup).
  The previous model is deleted after a successful import to save storage. Path remembered in SharedPreferences.
- Weight repacking (`use_extra_bufts`, ggml's CPU_REPACK) left at llama.cpp default **on**: for Q4_0/IQ4_NL it gives large speed-ups
  on Arm; for Q8_0 it's a no-op. Repacked tensors live in anonymous memory, not mmap.

## D7. Context 2048 default; KV prefix reuse; history trimming

- `n_ctx = 2048`, `n_batch = n_ubatch = 512`. Selectable 1024/2048/4096 in the Model screen.
- Native side mirrors the tokens in the KV cache and only evaluates the new suffix of each prompt (multi-turn chat stays fast).
- When a prompt doesn't fit, the adapter drops the oldest user/assistant pair and retries (system prompt + latest message always kept).
  A first pass uses a conservative 3 chars/token estimate to avoid a wasted tokenize.

## D8. Qwen3 chat template is rendered in Kotlin (not via llama.cpp's Jinja)

- `Qwen3ChatTemplate` reproduces the official template for plain chat: ChatML turns, past assistant reasoning stripped,
  generation prompt `<|im_start|>assistant\n`.
- **Thinking off (default)**: adds Qwen's documented `/no_think` soft switch to the last user turn **and** pre-fills an empty
  `<think>\n\n</think>\n\n` block (what `enable_thinking=False` produces). Belt and braces, so a 0.6B model doesn't drift into
  reasoning. Sampling: temp 0.7, top-k 20, top-p 0.8 (Qwen's non-thinking recommendation), repeat-penalty 1.05.
- **Thinking on**: no prefill; temp 0.6, top-p 0.95; `<think>` content shown collapsibly in the UI.
- Why Kotlin: pure-JVM unit-testable, deterministic, and it lets the future agent runtime add tool turns without touching C++.
  Other model families currently fall back to ChatML (see KNOWN_ISSUES).

## D9. Offline guarantee by construction

- The manifest declares **no `INTERNET` permission** (and explicitly `tools:node="remove"`s INTERNET / ACCESS_NETWORK_STATE /
  ACCESS_WIFI_STATE in case a future dependency tries to merge them in). Without it Android denies socket creation to the app's UID.
- No network code, no cloud fallback, no analytics/crash SDKs.
- The only permission in the merged manifest is androidx.core's signature-level `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`
  (internal broadcast hygiene, not network).
- The **only** internet step for a user is downloading the GGUF onto the phone beforehand (browser / PC → USB).

## D10. Toolchain

- AGP 8.13.2, Gradle 8.14.3, Kotlin 2.2.21, Compose BOM 2025.06.01 (Material 3), coroutines 1.10.2, compileSdk/targetSdk 35,
  minSdk 28 (Android 9), JDK 17, NDK r30 (30.0.16248370), CMake 3.31.6.
- The sandbox that produced the Phase 1 APK is **aarch64 Linux**, where Google ships no NDK/aapt2 host binaries, so it used a
  community aarch64 build of NDK r30 (github.com/HomuHomu833/android-ndk-custom) and static aarch64 aapt2 (lzhiyong/android-sdk-tools 35.0.2)
  via `android.aapt2FromMavenOverride` in `~/.gradle/gradle.properties` (not committed). On a normal x86_64 PC/Mac with Android Studio
  none of this is needed: the project uses the standard Google NDK r30 and AGP's own aapt2.

## D11. Licences

| component | licence | obligation |
|---|---|---|
| llama.cpp / ggml (`third_party/llama.cpp`) | MIT (see `third_party/llama.cpp/LICENSE`) | keep copyright notice; shown in-repo |
| Qwen3-0.6B weights (not bundled) | Apache 2.0 (https://huggingface.co/Qwen/Qwen3-0.6B) | user downloads it; attribution in README |
| AndroidX / Jetpack Compose / Kotlin / kotlinx.coroutines | Apache 2.0 | standard |

An in-app "Open-source licences" screen is a Phase 8 (release) item.

---

# Phase 2 decisions (14 Oct 2026)

## D12. One typed schema → grammar + validation + docs
- UI documents and tool arguments are described once as a `Schema` (small JSON-schema subset, `core/runtime/schema`).
  `Gbnf.of(schema)` produces the llama.cpp GBNF grammar used for constrained sampling, `schema.validate()` is the trusted
  check before anything renders or executes, and `signature()` documents the tool to the model. They cannot drift apart.
- Objects have a fixed property order and all properties required (optional data = empty string/array). Grammars stay
  small and small models are much more reliable. String length / numeric ranges are checked in Kotlin, not in the grammar
  (bounded repetitions blow up grammar size).
- We did not use llama.cpp's `common/json-schema-to-grammar.cpp`: it is C++ in the unstable `common` library (needs
  nlohmann::json); keeping the converter in Kotlin keeps it unit-testable and the JNI on the public C API only.

## D13. Grammar sampling outside the sampler chain
- `llama_sampler_init_grammar` is applied lazily: sample normally, test the one token against the grammar, and only when it
  is rejected mask the full vocabulary and resample (same as llama.cpp `common_sampler`). Running the grammar over all
  151k Qwen candidates for every token would cost tens of ms per token on a phone.
- Structured sampling: temperature 0.3, top-k 20, top-p 0.9, min-p 0.05, **repeat penalty off** (JSON repeats braces and
  quotes; a penalty corrupts it).

## D14. Canvas = versioned document per chat, updated by regeneration
- The model regenerates the whole (small, ≤12 blocks) document on every edit, given the current JSON. Patch formats
  (JSON-patch/diffs) were rejected: small models produce invalid paths, and the full doc is only a few hundred tokens.
- Every version is stored (`canvas_versions`); undo/redo/history move a pointer (`chats.canvasVersion`).
- Auto mode uses keyword rules instead of an extra LLM classification call (an extra pass would add seconds per message
  on a phone); the user can force Chat or Canvas.
- Invalid output never shows raw JSON as the answer: valid blocks are kept, invalid ones dropped, and a total failure
  falls back to markdown.

## D15. Markdown + math + highlighting are in-house
- A small Kotlin parser (block + inline) instead of a markdown library: no Android/WebView dependency, JVM-testable,
  tolerant of half-streamed text (unclosed fences/emphasis), `[n]` citations as first-class inlines.
- Math is "LaTeX-ish": TeX commands → Unicode (α, ², √, ∑, ≤ …), rendered in a serif italic. A real TeX typesetter
  (KaTeX/MathJax) would need a WebView/JS engine.
- Syntax highlighting is a linear-time lexer (keywords/strings/comments/numbers/types/functions) for ~15 languages.

## D16. RAG: Room + brute force + FTS4; embeddings via llama.cpp
- Vectors are stored as float32 BLOBs in Room and searched brute-force (cosine). For a few thousand chunks this is
  milliseconds and avoids a native vector DB. FTS4 (Room `@Fts4(contentEntity)`) provides keyword search and the
  no-embedding-model fallback; scores are blended 0.7 vector / 0.3 keyword.
- Embeddings use llama.cpp's embedding mode (same native stack, model's own pooling, L2-normalised) on a separate model +
  context and thread. Recommended: bge-small-en-v1.5 (MIT, 37 MB), nomic-embed-text-v1.5 (Apache-2.0), EmbeddingGemma-300M
  (Gemma terms, multilingual).
- PDF text: **PdfBox-Android** (`com.tom-roush:pdfbox-android`, Apache-2.0), page by page for progress/cancel.

## D17. Agent loop: LLM proposes, Kotlin disposes
- Each step is ONE JSON action under a grammar that only contains the allowed tools with their exact argument schemas
  (or `final`). Then: schema validation → `PolicyEngine` → user approval when needed → execution with timeout →
  truncated observation. Plans are generated first and shown.
- Approval policy: read-only and canvas tools never ask; local writes (notes, memory) ask unless the user chose "always
  allow"; anything that leaves the app (share sheet, calendar insert intent) asks every time. Side-effect budget 3/run.
  No URL-opening, code, shell or file tools exist.
- Small-model guard rails learned from host tests: (a) until the first successful tool call, if the plan names a tool, the
  grammar has no `final` option (0.6B otherwise "answered" 17.5% of 2480 as 433.0 without calling the calculator);
  (b) a UI skill ends when its `render_ui` succeeds (1.7B kept calling tools after rendering); (c) repeated tool errors
  after useful work end the run as completed instead of failed; (d) if a UI skill never renders, a compose step builds the
  canvas from the observations.
- Checkpoint = the whole `RunCheckpoint` after every step in one Room transaction; resume restarts from the saved
  iteration without re-planning. Runs execute in the application scope (not a foreground service, see KNOWN_ISSUES).

## D18. Image input via libmtmd, one model at a time
- `libmtmd` (llama.cpp `tools/mtmd`, built standalone with `LLAMA_BUILD_MTMD=ON`, `MTMD_VIDEO=OFF`) — b11609 supports
  SmolVLM/Idefics3, Qwen2-VL/2.5-VL, Qwen3-VL, Gemma 3/4, InternVL, etc. Images are decoded in Kotlin (BitmapFactory,
  ≤768 px) and passed as RGB, so no image codecs run in native code on the phone.
- `image_max_tokens = 512`: without it SmolVLM upscales every photo to 2048 px (17 tiles, ~1100 tokens; 56 s on the
  sandbox). With the cap the test image is 358 tokens / 14 s.
- The chat and vision models are separate roles; the app switches automatically (one in RAM). A VLM can also be used as
  the chat model if the user selects it in both roles.

## D19. Non-ChatML templates via llama.cpp's built-in matcher
- Qwen3 hybrid-thinking models keep the Kotlin template. ChatML models without `<think>` (Qwen2.5, Qwen3-VL-Instruct) use
  ChatML without the empty think block. Everything else (e.g. SmolVLM) uses `llama_chat_apply_template` with the model's
  embedded template (falls back to ChatML if unsupported).

## D20. Toolchain additions
- KSP 2.2.21-2.0.5, Room 2.7.2, DataStore 1.1.7, kotlinx-serialization-json 1.9.0 (library only, no compiler plugin —
  JSON is handled as `JsonElement`), PdfBox-Android 2.0.27.0. All Apache-2.0.
- Build sandbox note: JuiceFS occasionally returns EIO to long-lived Gradle daemons; builds use `--no-daemon`.

## D21. Phase 4 P0: remove unverified fast paths instead of patching them
- Garbage output on a real phone could not be reproduced in the sandbox (native ARM64 + QEMU SVE/SME). The runtime-
  dispatched KleidiAI kernels were the one path the phone used and the host never did, so `GGML_CPU_KLEIDIAI=OFF`.
- Defaults are the most-tested llama.cpp configuration: 8K, f16 KV, flash attention Auto; bigger windows opt-in.
- Every load is followed by a 12-token greedy self-test; failures switch to safe mode, then to the conservative
  `android_armv8.2_1` CPU variant (needs a restart because ggml backends are registered once per process).
- Crash detection uses a marker file around native work + ApplicationExitInfo, not a native signal handler (simpler,
  no async-signal-safety pitfalls).

## D22. OpenUI implemented natively in Kotlin
- A WebView + the React renderer would add a JS runtime, slow streaming updates and break the native look; OpenUI Lang is
  small enough to implement (parser ~300 lines). Names/props follow OpenUI's default library so its prompts and examples
  apply. Inline mode (Markdown + fenced blocks) keeps plain Markdown as the default answer.

## D23. Downloader in plain HttpURLConnection + WorkManager
- No OkHttp: one class, testable against the JDK's HttpServer. Manual redirects so every hop is checked against the HF
  allow-list and Range headers survive the CDN hop. WorkManager foreground job survives the app going to background;
  no network constraint (that would need ACCESS_NETWORK_STATE, which the offline flavor strips).

## D21–D24 (Phase 5)
- **D21** ACCESS_NETWORK_STATE in both flavors (online/offline badge + WorkManager CONNECTED constraint for the outbox). Wi-Fi state and change-network permissions stay stripped in `offline`.
- **D22** Phase 5 state in a separate Room database `onyx-agents.db` (the Phase 1–4 DB is version 1 without migrations; no risk to chats).
- **D23** `:core:agents` is pure Kotlin; Android supplies ports (tools, memory, context, approvals, network, outbox, audit, task store). Plan-and-execute DAG + blackboard + one serialized inference queue (priority, prefix affinity, thermal pause).
- **D24** Executor actions are never re-delegated or retried after a denial; every action with risk > NONE asks unless the user "always allowed" a LOW/MEDIUM tool; HIGH/CRITICAL never auto-allowed.
