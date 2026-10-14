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
