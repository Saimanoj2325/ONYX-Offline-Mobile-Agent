# Delivery checklist

Legend: ✅ done & verified in sandbox · 📱 done, needs Sai's phone to verify · ⬜ not started

## Phase 1 — Offline inference foundation (delivered)

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

## Phase 2 — Persistence, rich output, Canvas, RAG, skills/agent, multimodal (this delivery)

Legend as above. "host" = verified with the real JNI bridge + real GGUF on the ARM64 sandbox.

- ✅ `:core:data` Room DB (chats, messages incl. UI JSON, canvas versions, documents, chunks+vectors, FTS4, skills, agent runs/steps, memory, notes, models) + DataStore settings — 📱 restart survival to check on phone
- ✅ Markdown renderer: headings, emphasis, lists (nested/tasks), tables, code (highlight + copy), quotes, rules, links (not opened), math → Unicode, `[n]` citations — unit-tested parser; 📱 visual check
- ✅ Intelligent UI schema v1 (15 components), GBNF generated from the same schema, Kotlin validation + repair + markdown fallback — unit-tested; host: grammar output always parsed
- ✅ Canvas panel per chat: live preview while streaming, update in place (versions), undo/redo/history, copy/share — host: 1.7B update added a chart; 📱 UI
- ✅ Auto / Chat / Canvas routing — unit-tested
- ✅ Charts (bar/line/pie) drawn with Compose Canvas, flashcards, quiz, checklist, steps, comparison, actions — 📱 visual
- ✅ Local RAG: SAF import (PDF via PdfBox-Android, txt, md), chunking with overlap, llama.cpp embeddings, Room vectors + FTS4 hybrid, citations as tappable chips, background indexing with progress + cancel — host: retrieval 5/5, grounded answer; 📱 PDF import
- ✅ Skills: markdown + front matter, 5 built-ins, create/edit/import/enable/delete in-app, suggestion chip, /skill-id — unit-tested parser
- ✅ Tool registry with typed schemas, permissions, side effects, timeouts: search_docs, read_document, summarize, calculator, get_datetime, create_note, memory_read/write, render_ui, canvas_update, share_text, create_calendar_event (no URL/code/shell tools)
- ✅ Policy engine + approval dialog (allow once / always for local writes / deny)
- ✅ Agent loop: plan → grammar tool call → validation → policy → execute → observe; max iterations, timeouts, retries, cancellation, checkpoint every step, resume after restart, run history screen — unit-tested (fake engine) + host (0.6B and 1.7B tool calls)
- ✅ Image input via libmtmd (photo picker / camera), vision model role with mmproj — host: SmolVLM2-256M answered "red circle"; 📱 on phone
- ✅ Multiple models with roles (chat / embedding / vision), RAM guidance + recommendations in-app
- ✅ 79 JVM unit tests pass; host harness ALL PASS with Qwen3-1.7B (0.6B: all but "canvas update adds a chart")
- ✅ assembleDebug, no INTERNET permission, arm64 libs incl. libmtmd, 16 KB aligned
- 📱 Everything UI/Android-side: DEVICE_TEST_PLAN §9–§16

## Later phases (re-planned)
- ⬜ Visual node workflow builder (`:feature:workflow` graph editor, executor in `:core:runtime`)
- ⬜ Foreground service for long agent runs / indexing; WorkManager indexing
- ⬜ KV cache quantisation (q8_0 + flash-attn) for 8k context on 4–6 GB phones
- ⬜ OCR for scanned PDFs; ANN index for very large libraries
- ⬜ Canvas interactions persisted (checklist ticks, quiz progress) as new versions
- ⬜ Release build (R8, signing), licences screen, instrumented UI/Room tests, benchmark screen
