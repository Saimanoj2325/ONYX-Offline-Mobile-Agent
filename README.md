# Offline-First Android Agent — Phase 3

A native Android app (Kotlin + Jetpack Compose, Material 3) that runs local **GGUF** models **fully offline** on the
phone's CPU, with:

- **Rich answers**: proper Markdown (headings, lists, tables, highlighted code with Copy, quotes, math), streamed without jank.
- **Intelligent UI / Canvas**: for structured requests the model emits a typed JSON UI document (tables, charts, checklists,
  timelines, flashcards, quizzes, comparisons, callouts, action buttons…) under a **llama.cpp grammar**, Kotlin validates
  it and renders native Compose components in a full-screen Canvas that is **updated in place** on follow-ups
  ("make the table shorter", "add a chart") with version history and undo.
- **Local RAG**: import PDFs/txt/md, on-device embeddings, hybrid vector + keyword search, answers with tappable citations.
- **Skills + agent**: markdown skills drive a bounded, checkpointed agent loop with grammar-constrained tool calls,
  Kotlin validation, a policy engine and user approval for consequential actions. Run history shows every step.
- **Photos in**: attach a photo (gallery/camera) and ask about it via llama.cpp **libmtmd** + a small vision model.
- **Persistence**: chats, canvases, documents, skills, runs and settings survive restarts (Room + DataStore).
- **Phase 3 — faster & longer**: RAM-based context Auto (16K on 8 GB phones, up to 32K; 64K/128K YaRN experimental),
  Auto KV precision (f16 when it fits, q8_0 otherwise), flash attention, big-core pinning, prompt-prefix KV cache saved
  to disk, context shifting, rolling summaries + long-term memory paging, optional speculative decoding, up to 8K-token answers.
- **PDF**: `create_pdf` tool, multi-page reports (title page, contents, tables, code, images, page numbers), preview/share/save,
  "Write a report" skill that writes section by section.
- **MCP**: JSON-RPC client/server, on-device servers over binder/AIDL, built-in servers (files, notes, clipboard, calendar,
  contacts, share, phone actions); Streamable HTTP/SSE only in the separate **connected** build.
- **Phone actions**: alarms, timers, reminders, calendar, contacts, apps/deep links, message/call drafts, camera, flashlight,
  volume/DND, quick settings, device info, clipboard, files, notifications — typed tools behind approvals.
- **Form filling & automation**: AutofillService + encrypted Keystore profile vault, AccessibilityService screen agent with
  overlay (pause/stop/approve before submit), record/replay macros with schedules; CAPTCHA/OTP/banking safeguards.
- **Offline build: no INTERNET permission. No cloud fallback. No telemetry.** The only online step is downloading model files beforehand.

Docs: `docs/ARCHITECTURE.md` (how it works), `docs/DECISIONS.md` (why), `docs/CHECKLIST.md`, `docs/DEVICE_TEST_PLAN.md`,
`docs/KNOWN_ISSUES.md`, `docs/PHASE3.md` (Phase 3 features + Play policy notes), `docs/verification/phase3-host-harness.txt`
and `phase2-host-harness.txt` (real-model test output).

## Repository layout

```
app/                 Compose UI: Chat (+Canvas), Docs, Skills, Runs, Models; ViewModels; ports; DI root
core/inference/      Pure Kotlin: InferenceAdapter/TokenEngine, templates, grammar/images in requests, EmbeddingService
core/runtime/        Pure Kotlin: UI schema+GBNF+validation, markdown, RAG maths, skills, tools, policy, agent loop (+ tests)
core/data/           Room database + DataStore settings + agent checkpoint store
feature/workflow/    Skills editor and agent run history screens
engine/llamacpp/     JNI bridge (chat, grammar sampling, embeddings, libmtmd images) + CMake build of llama.cpp
third_party/         llama.cpp b11609 (vendored)
core/mcp-http/       Streamable HTTP/SSE MCP transport (connected flavor only)
mcp-aidl/            Binder/AIDL MCP contract + server base Service
samples/mcp-server-sample/  Example third-party on-device MCP server app
third_party/kleidiai KleidiAI v1.31.0 (vendored, pruned) for ggml-cpu ARM kernels
tools/host-harness/  Dev-only: runs the real JNI bridge + real GGUFs on an ARM64/x86 Linux JVM
```

## Build

Requirements: JDK 17, Android SDK 35, **NDK 30.0.16248370**, CMake 3.31.6 (Android Studio installs them).

```bash
echo "sdk.dir=$HOME/Android/Sdk" > local.properties
./gradlew :core:inference:test :core:runtime:test :core:mcp-http:test   # 136 JVM unit tests
./gradlew :app:assembleOfflineDebug     # → app/build/outputs/apk/offline/debug/app-offline-debug.apk (no INTERNET)
./gradlew :app:assembleConnectedDebug   # → app/build/outputs/apk/connected/debug/app-connected-debug.apk (+INTERNET for MCP)
./gradlew :samples:mcp-server-sample:assembleDebug
```

Native code is always built Release/-O3 (also in the debug APK). `gradle.properties` limits Gradle to `-Xmx3g`, 2 workers.

Verify the offline guarantee:

```bash
$ANDROID_HOME/build-tools/35.0.0/aapt dump permissions app/build/outputs/apk/offline/debug/app-offline-debug.apk
# package: com.offlineagent.app
# uses-permission: SET_ALARM, POST_NOTIFICATIONS, FOREGROUND_SERVICE(+SPECIAL_USE), READ_CONTACTS, READ_CALENDAR,
#   ACCESS_NOTIFICATION_POLICY, USE_BIOMETRIC/USE_FINGERPRINT, WAKE_LOCK, RECEIVE_BOOT_COMPLETED, USE_MCP_SERVERS (own)
# (no android.permission.INTERNET / ACCESS_NETWORK_STATE, no SMS/call/camera/storage permissions)
```

## Install & provision models (only step needing internet)

1. `adb install -r offline-agent-phase3-offline-debug.apk` (or copy to the phone and open it).
2. Download GGUFs on the phone/PC, then in the app: **Models → (role) → Add**. Files are copied into app storage.

| role | recommended | size | phone RAM | licence | link |
|---|---|---|---|---|---|
| Chat (Canvas + skills) | **Qwen3-1.7B-Q4_K_M.gguf** | 1.1 GB | 6 GB+ | Apache-2.0 | https://huggingface.co/unsloth/Qwen3-1.7B-GGUF |
| Chat (best quality) | Qwen3-4B-Q4_K_M.gguf | 2.5 GB | 8 GB+ | Apache-2.0 | https://huggingface.co/Qwen/Qwen3-4B-GGUF |
| Chat (fast, weak structure) | Qwen3-0.6B-Q8_0.gguf | 0.6 GB | 4 GB | Apache-2.0 | https://huggingface.co/Qwen/Qwen3-0.6B-GGUF |
| Embedding | bge-small-en-v1.5-q8_0.gguf | 37 MB | any | MIT | https://huggingface.co/CompendiumLabs/bge-small-en-v1.5-gguf |
| Embedding (better EN) | nomic-embed-text-v1.5.Q8_0.gguf | 146 MB | any | Apache-2.0 | https://huggingface.co/nomic-ai/nomic-embed-text-v1.5-GGUF |
| Embedding (multilingual) | embeddinggemma-300M-Q8_0.gguf | 334 MB | any | Gemma Terms of Use | https://huggingface.co/ggml-org/embeddinggemma-300M-GGUF |
| Vision (fast) | SmolVLM2-256M-Video-Instruct-Q8_0.gguf **+** mmproj-SmolVLM2-256M-Video-Instruct-Q8_0.gguf | 280 MB | 4 GB | Apache-2.0 | https://huggingface.co/ggml-org/SmolVLM2-256M-Video-Instruct-GGUF |
| Vision (better) | Qwen3VL-2B-Instruct-Q4_K_M.gguf **+** mmproj-Qwen3VL-2B-Instruct-Q8_0.gguf | 1.55 GB | 6 GB+ | Apache-2.0 | https://huggingface.co/Qwen/Qwen3-VL-2B-Instruct-GGUF |

Tool calling and Canvas work best with Qwen3-1.7B or 4B; **0.6B is weak at structured output** (valid but simple JSON,
shaky tool choice). RAM ≈ model file + KV cache (≈ 448 MiB at the default 4096 context for 0.6B/1.7B) + ~150 MB; on 4 GB
phones set context 2048. For a vision model, tap **Add projector** on its card and pick the matching mmproj file.

## Use

- **Chat**: type and send; the model loads automatically. Above the composer: **Auto / Chat / Canvas**. Auto opens the
  Canvas for tables, charts, plans, comparisons, checklists, quizzes…; follow-ups such as "add a chart" edit it in place.
  Canvas: ‹ › = undo/redo, ⋮ = history, copy/share as Markdown. Red square = Stop.
- **Documents**: Docs → Import (PDF/txt/md). With "Use my documents" on (chat ⋮ menu), answers cite `[1]`, `[2]`; tap a
  chip to read the passage.
- **Skills**: + → *Run a skill…*, or type `/study-plan …`, `/flashcards …`, `/compare-options …`, `/daily-planner …`,
  `/explain-concept …`. Skills tab: create, duplicate & edit, import .md, enable/disable, delete. Writes (notes, memory)
  and anything leaving the app (share sheet, calendar) ask for approval.
- **Runs**: every agent run with plan, tool inputs/outputs, approvals, errors and timings; unfinished runs can be resumed.
- **Photos**: + → Photo from gallery / Take a photo, then ask about it (needs a Vision model + projector).

## Verified in the build sandbox (no phone available)

| check | result |
|---|---|
| JVM unit tests | **79/79 pass** (26 `:core:inference`, 53 `:core:runtime`: UI schema parsing/validation/repair/fallback + GBNF well-formedness, partial-JSON streaming, router, markdown/math/highlighting, chunker, cosine/BM25/hybrid ranking, skill parser/matcher/built-ins, tool argument validation, calculator, policy engine, agent loop happy path / iteration bound / invalid output / approvals / allow-list / tool timeout / repeated calls / cancellation checkpoint / resume / compose fallback) |
| Host harness, **Qwen3-1.7B-Q8_0** (real JNI + llama.cpp b11609) | **ALL PASS**: grammar-constrained canvases (heading+callouts+chart+actions; checklist), in-place update added a chart, agent called `calculator` with `2480 * 0.175` → 434 → final "434", compare-options skill rendered a canvas |
| Host harness, **Qwen3-0.6B-Q8_0** | all pass except "canvas update adds a chart" (0.6B kept only the table). Agent tool call passes after the plan-driven grammar fix |
| Embeddings + RAG (bge-small-en-v1.5 via llama.cpp) | 384-d vectors; top-1 correct for 4/4 queries + a pure-vector paraphrase query; grounded answer "four books … [1]" |
| Image input (SmolVLM2-256M + mmproj via libmtmd) | answered "red circle" for a synthetic image; 358 image tokens in 14 s on 2 sandbox cores (capped at 512 tokens/image); text turn after an image works |
| APK | `lib/arm64-v8a/` libllama, libggml, libggml-base, libmtmd, 7 × CPU variants, liboffline_agent_llama, libc++_shared; 16 KB-aligned |
| Permissions | **no INTERNET** (only androidx's signature-level dynamic-receiver permission) |

Not verified (needs a phone): all UI, Room on device, SAF/photo picker/camera, PdfBox on real PDFs, intents, real speed and
memory → `docs/DEVICE_TEST_PLAN.md` §9–§16.

Host harness: `tools/host-harness/build-native.sh <dir>`, `./gradlew :engine:llamacpp:assembleDebug`, then
`MODEL=… EMBED=… VLM=… MMPROJ=… ./gradlew -PhostHarness :tools:host-harness:runPhase2 -PnativeDir=<dir>/llama/bin`.

## Licences

llama.cpp / ggml / libmtmd: MIT (`third_party/llama.cpp/LICENSE`). PdfBox-Android: Apache-2.0. AndroidX (Compose, Room,
DataStore), Kotlin, kotlinx.coroutines, kotlinx.serialization: Apache-2.0. Models are downloaded by the user (licences in
the table above); none are bundled.
