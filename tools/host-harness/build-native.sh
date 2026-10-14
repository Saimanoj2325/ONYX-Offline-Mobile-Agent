#!/bin/bash
# Builds llama.cpp + the app's JNI bridge for the HOST (ARM64/x86_64 Linux) into $OUT.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
OUT=${1:-$ROOT/tools/host-harness/build/native}
L=$ROOT/third_party/llama.cpp
mkdir -p "$OUT/llama"
cmake -S "$L" -B "$OUT/llama" -G Ninja -DCMAKE_BUILD_TYPE=Release -DBUILD_SHARED_LIBS=ON \
  -DLLAMA_BUILD_COMMON=OFF -DLLAMA_BUILD_TESTS=OFF -DLLAMA_BUILD_EXAMPLES=OFF -DLLAMA_BUILD_TOOLS=OFF \
  -DLLAMA_BUILD_SERVER=OFF -DLLAMA_BUILD_APP=OFF -DLLAMA_OPENSSL=OFF -DGGML_NATIVE=OFF \
  -DGGML_BACKEND_DL=ON -DGGML_CPU_ALL_VARIANTS=ON -DGGML_OPENMP=OFF -DGGML_LLAMAFILE=OFF \
  -DCMAKE_C_COMPILER=clang -DCMAKE_CXX_COMPILER=clang++ >/dev/null
ninja -C "$OUT/llama" -j"${JOBS:-2}" >/dev/null
JH=${JAVA_HOME:-/opt/jdk17}
clang++ -std=c++17 -O3 -shared -fPIC -I"$ROOT/tools/host-harness/stubs" -I"$JH/include" -I"$JH/include/linux" \
  -I"$L/include" -I"$L/ggml/include" "$ROOT/engine/llamacpp/src/main/cpp/llama_jni.cpp" \
  -L"$OUT/llama/bin" -lllama -lggml -lggml-base -Wl,-rpath,"$OUT/llama/bin" -o "$OUT/llama/bin/liboffline_agent_llama.so"
echo "native libs in $OUT/llama/bin"
