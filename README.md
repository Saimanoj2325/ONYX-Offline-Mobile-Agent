# Offline-First Android Agent Platform — Phase 1

A native Android app (Kotlin + Jetpack Compose, Material 3) that loads a local **GGUF** model and streams answers
**fully offline** on the phone's CPU. Phase 1 is the inference foundation for the later agent loop, tool registry,
workflow builder and memory (see `docs/CHECKLIST.md`).

- Engine: **llama.cpp `b11609`** (vendored in `third_party/llama.cpp`) via a small JNI bridge, behind an `InferenceAdapter`
  interface so another engine (e.g. QVAC, once it has an Android SDK) can be swapped in. Why: `docs/DECISIONS.md`.
- Test model: **Qwen3-0.6B** GGUF (Q8_0 recommended, Q4_K_M also fine). The model is **not** bundled in the APK.
- **No INTERNET permission at all.** No cloud fallback. The only time you need internet is to download the `.gguf` onto the phone beforehand.

## Repository layout

```
app/                 Compose UI (Model + Chat screens), ViewModel, SAF model import, lifecycle (onTrimMemory → unload)
core/inference/      Pure Kotlin: InferenceAdapter/TokenEngine, state machine, Qwen3 template, UTF-8 streaming, thread policy (+ JVM tests)
engine/llamacpp/     Android library: LlamaCppEngine (Kotlin) + llama_jni.cpp + CMake build of llama.cpp
third_party/         llama.cpp source at a pinned tag (LLAMA_CPP_VERSION)
tools/host-harness/  Dev-only: runs the real JNI bridge + real GGUF on an ARM64/x86 Linux JVM
docs/                ARCHITECTURE, DECISIONS, CHECKLIST, DEVICE_TEST_PLAN, KNOWN_ISSUES
```

## Build

Requirements: JDK 17, Android SDK (platform 35, build-tools 35), **NDK 30.0.16248370 (r30)**, CMake 3.31.6. Android Studio
(Ladybug or newer) installs all of these from SDK Manager.

```bash
# point Gradle at your SDK (Android Studio does this automatically)
echo "sdk.dir=$HOME/Android/Sdk" > local.properties

./gradlew :core:inference:test      # JVM unit tests (fast, no device)
./gradlew :app:assembleDebug        # → app/build/outputs/apk/debug/app-debug.apk
```

Notes
- The native code is always built **Release / -O3** (also in the debug APK). First native build takes a few minutes
  (llama.cpp + 7 CPU variants); later builds are incremental.
- If Gradle runs out of memory: `gradle.properties` already uses `org.gradle.jvmargs=-Xmx3g` and `org.gradle.workers.max=2`.
- Only `arm64-v8a` is built (all modern phones). The APK won't install on an x86_64 emulator.

Verify the offline guarantee yourself:

```bash
$ANDROID_HOME/build-tools/35.0.0/aapt dump permissions app/build/outputs/apk/debug/app-debug.apk
# package: com.offlineagent.app
# permission: com.offlineagent.app.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION   ← androidx internal, signature-only
# (no android.permission.INTERNET)
unzip -l app/build/outputs/apk/debug/app-debug.apk | grep lib/arm64-v8a
```

## Install the APK

- USB: enable Developer options → USB debugging, then `adb install -r offline-agent-phase1-debug.apk`
- Or copy the APK to the phone and tap it (allow "Install unknown apps" for your file manager).

## Provision the model (the only step that needs internet)

1. On the phone, download **`Qwen3-0.6B-Q8_0.gguf`** (~610 MB) from
   https://huggingface.co/Qwen/Qwen3-0.6B-GGUF/tree/main (or download on a PC and copy it to the phone's `Download/` folder).
2. Open **Offline Agent → Model → Pick .gguf file** and select it. The app copies it into its private storage
   (progress bar; you can cancel). It is remembered across restarts. You may delete the downloaded copy afterwards.
3. Tap **Load model**. Status turns **Ready**; the app switches to **Chat**.

## Use

- Type a message → the answer streams token by token; **tokens/sec** is shown live and under each reply.
- Red square = **Stop** (stops within a fraction of a second, model stays loaded).
- **Thinking** switch: OFF (default) = fast direct answers (`/no_think`); ON = Qwen3 reasoning, shown in a collapsible "Show reasoning".
- Refresh icon = new chat. Model tab: context length (1024/2048/4096), CPU threads (defaults to your phone's performance cores),
  Unload, model info, and ⓘ for the CPU features llama.cpp detected.

## Offline test (airplane mode)

1. Provision the model as above.
2. Turn on **Airplane mode** and switch **Wi-Fi and Bluetooth off**.
3. Force-stop and reopen the app → Model → Load → Chat → ask *"What is the capital of India?"*, then *"And of Telangana?"*.
4. Both answers stream with no network. Full checklist: `docs/DEVICE_TEST_PLAN.md`.

## Verification done in the build sandbox (no phone available)

| check | result |
|---|---|
| `:core:inference:test` (26 JVM tests: template, UTF-8, think splitting, thread policy, GGUF header, prompt fitting, adapter state machine/threading/cancel with a fake engine) | **26/26 pass** |
| Host harness: real `llama_jni.cpp` + llama.cpp `b11609` + **Qwen3-0.6B-Q8_0** on ARM64 Linux, 2 threads, through the app's `EngineInferenceAdapter` | **all checks pass**: correct answers ("New Delhi", multi-turn "Hyderabad"), no template-token leaks, multi-byte Telugu output intact, Stop → CANCELLED in **5 ms**, thinking mode emits `<think>`, corrupt GGUF → clean `ModelLoadException`, reload after failure |
| Host harness speed (2 sandbox server cores; **not** a phone number) | decode ≈ 40–74 tok/s (shared VM, varies run to run), prompt ≈ 230–600 tok/s |
| APK | `lib/arm64-v8a/` has libllama, libggml, libggml-base, 7 × libggml-cpu-android_armv*, liboffline_agent_llama, libc++_shared; all 16 KB-aligned |
| Permissions (`aapt dump permissions`) | **no INTERNET**; only androidx's signature-level dynamic-receiver permission |

Real-phone speed, smoothness, memory behaviour and the UI are still to be checked by Sai (DEVICE_TEST_PLAN.md).

## Licences

- llama.cpp / ggml: **MIT** — `third_party/llama.cpp/LICENSE`
- Qwen3 model weights (downloaded by the user, not distributed with the app): **Apache 2.0** — https://huggingface.co/Qwen/Qwen3-0.6B
- AndroidX, Jetpack Compose, Kotlin, kotlinx.coroutines: Apache 2.0
