# Offline-First Android Agent — Phase 4b

A native Android app (Kotlin + Jetpack Compose, Material 3) that runs local **GGUF** models **fully offline** on the
phone's CPU, with:

- **Phase 4b — fast first token + live streaming**: 321-token chat system prompt (was 2 163 with the full OpenUI
  library; the full library is used only in Canvas mode or when you ask for UI), "Reading prompt… n/N" progress, live
  Thinking section (open while the model thinks, collapses when the answer starts), answer streamed token by token,
  OpenUI blocks stream as text until they render, agent/report/automation steps stream into their cards, footer with
  tok/s and time to first token, Thinking on by default (+ per-message Think chip). **Reload ANR fixed** (GGUF header
  read off the main thread, buffered, early stop, cached); StrictMode in debug builds. See `docs/PHASE4.md` § Phase 4b.
- **Phase 4 — one ChatGPT-style chat**: drawer with searchable conversations + Settings, model picker, streaming answers
  with Stop / Copy / Regenerate / Edit & resend, collapsible thinking, "+" attachments (photo, camera, document) and a
  Tools toggle (Agent, Canvas, PDF report, Phone actions, Automation) — agent steps, approvals, canvases, PDFs and
  automation logs are cards in the thread. Documents, skills, runs, macros and models open as bottom sheets.
- **OpenUI answers**: a native Kotlin implementation of [OpenUI Lang](https://www.openui.com) (MIT): streaming parser,
  component library (tables, bar/line/area/pie charts, forms, tabs, steps, callouts, code, follow-up chips…), generated
  system prompt, GBNF grammar for Canvas mode, Compose renderer. Plain Markdown when the model writes no OpenUI.
- **Built-in model downloader**: curated Hugging Face catalogue + "paste any HF GGUF link", resumable (HTTP Range),
  verified (GGUF header, size, SHA-256), WorkManager foreground job with notification; first-launch onboarding picks a
  model for your RAM.
- **Phase 4 P0 fixes** (garbage output / crashes on a real phone): KleidiAI off, 8K + f16 KV defaults (16K+ opt-in),
  post-load self-test with automatic safe mode and compatibility CPU kernels, crash detection + safe-mode start,
  diagnostics & crash-log export, fail-safe settings and prompt cache. See `docs/PHASE4.md`.

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
- **Network: the only network use is the in-app model downloader** (HTTPS to huggingface.co and its CDN, every redirect
  checked against that allow-list). Inference, chats, documents and tools never leave the phone. No cloud fallback,
  no telemetry. The *connected* flavor additionally allows LAN/remote MCP servers.

Docs: `docs/ARCHITECTURE.md` (how it works), `docs/DECISIONS.md` (why), `docs/CHECKLIST.md`, `docs/DEVICE_TEST_PLAN.md`,
`docs/KNOWN_ISSUES.md`, `docs/PHASE4.md` (Phase 4: P0 root cause + fixes, chat UI, OpenUI, downloader), `docs/PHASE3.md` (Phase 3 features + Play policy notes), `docs/verification/phase4-p0.txt`, `docs/verification/phase3-host-harness.txt`
and `phase2-host-harness.txt` (real-model test output).

## Repository layout

```
app/                 Compose UI: one chat screen (ui/chat) + OpenUI renderer (ui/openui) + Settings; downloader worker (models/); diagnostics (diag/)
core/openui/         Pure Kotlin OpenUI Lang: streaming parser, library, resolver, forms, prompt generator, GBNF (+ tests)
core/models/         Pure JVM model catalogue + resumable, verified Hugging Face downloader (+ fake-server tests)
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
./gradlew :core:inference:test :core:runtime:test :core:mcp-http:test :core:openui:test :core:models:test   # JVM unit tests (see PHASE4.md for counts)
./gradlew :app:assembleOfflineDebug     # → app/build/outputs/apk/offline/debug/app-offline-debug.apk (INTERNET only for the model downloader)
./gradlew :app:assembleConnectedDebug   # → app/build/outputs/apk/connected/debug/app-connected-debug.apk (+ LAN/remote MCP)
./gradlew :samples:mcp-server-sample:assembleDebug
```

Native code is always built Release/-O3 (also in the debug APK). `gradle.properties` limits Gradle to `-Xmx3g`, 2 workers.

Check the permissions (since Phase 4 both flavors list INTERNET, used only by the downloader; no ACCESS_NETWORK_STATE):

```bash
$ANDROID_HOME/build-tools/35.0.0/aapt dump permissions app/build/outputs/apk/offline/debug/app-offline-debug.apk
# package: com.offlineagent.app
# uses-permission: SET_ALARM, POST_NOTIFICATIONS, FOREGROUND_SERVICE(+SPECIAL_USE), READ_CONTACTS, READ_CALENDAR,
#   ACCESS_NOTIFICATION_POLICY, USE_BIOMETRIC/USE_FINGERPRINT, WAKE_LOCK, RECEIVE_BOOT_COMPLETED, USE_MCP_SERVERS (own)
# + INTERNET (model downloader only) + FOREGROUND_SERVICE_DATA_SYNC (download job)
# (no ACCESS_NETWORK_STATE, no SMS/call/camera/storage permissions)
```

## Install & get a model

1. `adb install -r offline-agent-phase4-offline-debug.apk` (or copy to the phone and open it).
2. First launch shows a sheet recommending a model for your phone's RAM → **Download** (one-time, Wi-Fi recommended).
   More models: model picker (top bar) → **Download models…** (catalogue below, or paste any Hugging Face GGUF link).
   Importing a .gguf file you already have still works (Models sheet → Import).

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

- **Chat**: type and send; the model loads automatically (and runs a 2-second self-test). Answers show tables, charts,
  forms and follow-up chips inline when they help. Long-press your message → Edit & resend; under an answer → Copy /
  Regenerate. Red square = Stop.
- **Tools chip** (under the composer): Agent (tools + approvals), Canvas (a UI you refine with follow-ups; Undo/Redo),
  PDF report, Phone actions, Automation (screen agent). Also opens Documents, Skills, Agent runs, Macros, Models.
- **+**: Photo / Camera (needs a vision model) / Document for RAG (answers cite `[1]`, tap to read the passage).
- **Skills**: Tools → Run a skill, or type `/study-plan …`, `/flashcards …`, `/compare-options …`, `/daily-planner …`.
- **Settings** (drawer, bottom): Models, Performance (context, KV, rich answers), Phone actions, Profile & autofill,
  Automation & macros, MCP, **Diagnostics & crash logs** (self-test, safe mode, compatibility CPU kernels, export logs).

## Verified in the build sandbox — Phase 2/3 (no phone available; Phase 4: docs/PHASE4.md)

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
