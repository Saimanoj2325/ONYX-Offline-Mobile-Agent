# Delivery checklist

Legend: ✅ done & verified in sandbox · 📱 done, needs Sai's phone to verify · ⬜ not started

## Phase 1 — Offline inference foundation (this delivery)

- ✅ Toolchain: JDK 17, Android SDK platform 35 + build-tools 35, NDK r30, CMake 3.31.6, Gradle 8.14.3 wrapper
- ✅ Git repo at `offline-agent/`, llama.cpp vendored at pinned tag `b11609`
- ✅ Modules `:app`, `:core:inference` (pure Kotlin), `:engine:llamacpp` (JNI); `:core:runtime` / `:core:data` / `:feature:workflow` reserved
- ✅ `InferenceAdapter` + `TokenEngine` interfaces; llama.cpp behind them (QVAC swappable later)
- ✅ Native build: arm64-v8a, `-O3` Release native code inside debug APK, `GGML_NATIVE=OFF`, Android CPU variants armv8.0 → armv9.2 selected at runtime
- ✅ Single dedicated `llm-engine` thread for all native calls (unit-tested)
- ✅ Token streaming via `Flow<GenerationEvent>`
- ✅ Stop button cancels generation (native abort callback + between-token check; unit-tested, and host-tested with the real model)
- ✅ mmap model loading (`LLAMA_LOAD_MODE_MMAP`)
- ✅ Threads = performance cores (sysfs max-freq clusters), fallback 4, user override
- ✅ Context 2048 default (1024/4096 selectable), KV-cache prefix reuse across turns, oldest-turn trimming on overflow
- ✅ Load / unload lifecycle; load failure → clear error + retry; `onTrimMemory`/`onLowMemory` → unload + snackbar
- ✅ Qwen3 ChatML template, thinking OFF by default (`/no_think` + empty think block), thinking ON toggle with collapsible reasoning
- ✅ tokens/sec (live while streaming + final stats incl. prompt speed)
- ✅ No cloud fallback; **no INTERNET permission** (verified with `aapt dump permissions`)
- ✅ Model provisioning via SAF picker → copy to internal storage with progress, cancel, header/space validation, remembered
- ✅ Compose Material 3 UI: Model screen (pick/replace/delete, settings, load/unload, model info, CPU features), Chat screen
- ✅ JVM unit tests (template, UTF-8 streaming, think splitting, thread policy, GGUF header, prompt fitting, adapter state machine with fake engine)
- ✅ Host harness: real JNI bridge + real Qwen3-0.6B GGUF on ARM64 Linux (see README "Verification")
- ✅ `assembleDebug` → APK with `lib/arm64-v8a/*.so`
- ✅ Docs: README, ARCHITECTURE, DECISIONS, CHECKLIST, DEVICE_TEST_PLAN, KNOWN_ISSUES
- 📱 Runs smoothly on a real mid-range phone (no jank, tok/s, memory, thermals) — DEVICE_TEST_PLAN
- 📱 Airplane-mode end-to-end test — DEVICE_TEST_PLAN §3

## Phase 2 — Inference hardening & settings
- ⬜ Persist runtime settings (DataStore); per-model presets
- ⬜ Model metadata pre-read (arch, quant, ctx) before load; RAM-fit check per model
- ⬜ Foreground service for long generations (survive backgrounding)
- ⬜ Optional KV cache quantisation (q8_0 + flash-attn) for 4096+ ctx on 4 GB phones
- ⬜ Generic chat templates for non-Qwen GGUFs (llama.cpp Jinja or a template registry)
- ⬜ Benchmark screen (pp/tg), instrumented tests on device

## Phase 3 — Tool registry
- ⬜ `:core:runtime` module; `Tool` interface (name, JSON schema, suspend execute), registry, permission model
- ⬜ Built-in offline tools (calculator, date/time, notes, device info, file read within app sandbox)
- ⬜ Qwen3 tool-call template (`<tool_call>` JSON) + parser + unit tests

## Phase 4 — Agent loop
- ⬜ ReAct-style loop: plan → tool call → observation → answer; step limits, cancellation, streaming of intermediate steps
- ⬜ Guardrails: max steps, timeouts, tool-argument validation

## Phase 5 — Persistence (Room)
- ⬜ `:core:data`: conversations, messages, workflows, runs; migrations; repository interfaces in core

## Phase 6 — Visual workflow builder
- ⬜ `:feature:workflow`: node graph editor (Compose canvas), node types (LLM, tool, condition, loop, input/output)
- ⬜ Serialisation (JSON), validation, executor in `:core:runtime`

## Phase 7 — Memory
- ⬜ Long-term memory store (Room + on-device embeddings via a small GGUF embedding model), retrieval into prompts

## Phase 8 — Release
- ⬜ Release build (R8, signing), in-app licences screen, crash-free offline diagnostics export, device report
