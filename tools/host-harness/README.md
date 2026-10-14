# Host harness (developer tool, not shipped)

Runs the **real** inference path — `LlamaCppEngine` → JNI (`llama_jni.cpp`) → llama.cpp → GGUF — through the same
`EngineInferenceAdapter` the app uses, on a desktop Linux JVM. It catches JNI/template/streaming/cancellation bugs
without a phone. It does **not** test Android specifics (SAF, onTrimMemory, CPU-variant selection on a phone SoC, UI).

```bash
# 1. host build of llama.cpp + the app's JNI bridge (needs cmake, ninja, clang, JDK 17)
./tools/host-harness/build-native.sh /tmp/hostnative
# 2. engine classes (the harness links the compiled Kotlin of :engine:llamacpp)
./gradlew :engine:llamacpp:assembleDebug
# 3. run
THREADS=4 ./gradlew -PhostHarness :tools:host-harness:run \
   -PnativeDir=/tmp/hostnative/llama/bin -Pmodel=/path/to/Qwen3-0.6B-Q8_0.gguf
```

Exit code 0 = all checks passed. `stubs/android/log.h` replaces Android logging on the host.
