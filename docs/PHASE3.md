# Phase 3 — speed, long context, PDF, MCP, phone actions, form filling & automation

Target: common 8 GB RAM arm64 phones. Everything below is built and compiles into both APKs; the pure-Kotlin parts
are unit-tested and the inference parts were exercised on an ARM64 Linux host with real GGUF models. **Nothing was
run on a real phone yet** (see "Not verified" at the end and DEVICE_TEST_PLAN §17–§23).

## 1. Long context & performance

| Area | What is implemented | Where |
|---|---|---|
| Context sizes | 2K/4K/8K/16K/32K, plus experimental 64K/128K (YaRN, only when the plan says it fits). **Auto** picks from RAM: <4 GB → 2K, 4 GB → 4K, 6 GB → 8K, **8 GB → 16K**, 12 GB+ → 32K. Options that would not fit are disabled with the reason. | `core/inference/ContextPlanner.kt`, Settings → Performance |
| Memory estimate | GGUF header read (no load) → weights + KV (per KV type) + compute buffers + runtime overhead, shown per option and for the current setting, with a live token counter. | `ContextPlanner`, `GgufMetadata` |
| KV cache | F16 / **q8_0 (default)** / q4_0; quantised KV forces flash attention on. | JNI `nativeLoad` opts |
| Flash attention | Auto / On / Off (llama.cpp `flash_attn_type`). | |
| mmap / warm model | mmap loading, warm-up decode after load, model kept resident while the app is used; lazy load on first message. | `EngineController` |
| Batch / threads | batch/ubatch presets (256/256 … 1024/512); threads = performance cores, **threadpool pinned to the big-core CPU ids** (sysfs max-freq clusters), user override. | `ThreadPolicy.performanceCoreIds`, JNI `cpus` |
| Prompt/KV prefix cache | System-prompt (+ stable prefix) KV kept in memory between turns **and saved to disk** (`llama_state_seq_save_file`), so after an app restart the first answer skips re-reading the system prompt. Keyed by model + prefix hash. | JNI `nativePrefixCache`, `PrefixCacheFiles` |
| Speculative decoding | Optional draft model (Qwen3-0.6B) for larger targets; 4/6/8 draft tokens; greedy-equivalent verification. | JNI `nativeAttachDraft` |
| Context shifting | When generation hits the window, the oldest non-kept tokens are discarded (`n_keep` = system prompt) and decoding continues. | JNI generate loop |
| Auto summarisation | `ContextManager` keeps the system prompt stable, folds the oldest turns into a rolling summary (by the model) when history passes the high-water mark, never starts with an assistant turn. | `core/runtime/context/ContextManager.kt` |
| Long-term memory / RAG paging | Old turns and summaries of every chat are embedded into a "memory" collection incrementally; relevant pages are paged back into the final user message. Clear in Settings. | `app/data/ChatMemory`, `LongTermMemory` |
| Output length | Max answer up to **8192 tokens** (chip choices; capped by the plan). | Settings → Performance |
| UI smoothness | Streaming tokens are coalesced (≤ 20 Compose state updates/s) so the UI never janks on fast decode; lazy lists keyed; Phase 3 services created lazily (fast cold start). | `ChatViewModel` |
| Build | Baseline profile (`app/src/main/baseline-prof.txt`), R8 full mode + resource shrinking for `release`, keep-rules for JNI/serialization. | `app/build.gradle.kts`, `proguard-rules.pro` |
| Incremental RAG indexing | Only changed/new documents and new chat turns are (re)embedded. | `DocumentIndexer`, `ChatMemory` |

## 2. PDF

* `create_pdf` tool (safe: writes only a new file in the app's own PDF folder / Downloads via MediaStore; approval
  "local write"). Input is Markdown or the current Canvas.
* Layout engine in pure Kotlin (`core/runtime/pdf/PdfLayout.kt`): title page, optional table of contents, headings,
  paragraphs with bold/italic/code runs, nested bullets & numbered lists, tables (column widths, header repeat on page
  break), code blocks (monospace, wrapped), quotes, callouts, images (scaled), rules, page breaks, page numbers.
  Rendering on Android uses `android.graphics.pdf.PdfDocument` (`app/pdf/PdfExporter.kt`); the host/unit tests use a
  tiny pure PDF writer (`PdfWriter.kt`) with the same layout.
* Save: MediaStore Downloads (Android 10+) or app external Downloads (Android 9); "Save as…" via SAF
  `CreateDocument`; preview with `PdfRenderer`; share via FileProvider; export a whole chat or the Canvas.
* "Write a report / PDF" skill (`skills/write-report.md`, `ReportWriter.kt`): outline first, then each section
  generated separately (small prompts, fits 8K/16K), progress in the foreground-service notification, cancellable,
  then assembled into one PDF.

## 3. MCP

* `core/runtime/mcp`: JSON-RPC 2.0 codec, MCP client (`initialize`, `tools/list`, `tools/call`, `resources/list`,
  `resources/read`, `prompts/list`, `prompts/get`, notifications), server base, `McpHub` that merges every server's
  tools into the agent's `ToolRegistry` (names prefixed per server, same approval policy; untrusted servers'
  tools always ask).
* Transports: in-process (built-in servers), **Android binder/AIDL** (`:mcp-aidl`, `IMcpServer.aidl`; servers are
  discovered by the `com.offlineagent.mcp.SERVER` service action and protected by the
  `com.offlineagent.permission.USE_MCP_SERVERS` permission), and — **connected flavor only** — Streamable HTTP + SSE
  (`:core:mcp-http`, plain `HttpURLConnection`, optional bearer token).
* Built-in servers: files (SAF folders the user granted), notes, clipboard, calendar & contacts (ContentProviders,
  read-only + "prepare event" intent), share intents, plus the phone-action groups below.
* `samples/mcp-server-sample`: a separate app ("MCP Study Tools") showing a third-party on-device MCP server.
* Flavors: `offline` (default) has **no INTERNET** (also stripped from libraries with `tools:node="remove"`);
  `connected` adds INTERNET only for user-added MCP endpoints. Inference is local in both.

## 4. Phone capabilities

Typed tools grouped in Settings → Phone actions (each group off until enabled; runtime permissions requested there):
alarms/timers (`AlarmClock` intents), reminders (WorkManager + notification), calendar & contacts lookup, prepare
event, open app / deep link (launcher `<queries>` only, no QUERY_ALL_PACKAGES), SMS / WhatsApp / email / call
**drafts** (user presses Send/Call — no SMS or CALL permissions), camera (camera-app intent, photo attached to chat),
flashlight, volume, ringer, DND (needs notification-policy access), quick-settings panels, battery/storage/RAM/device
info, clipboard (foreground only), file search/read/create/move/rename/copy in granted SAF folders, recent
notifications (NotificationListener, opt-in, kept in memory only). Every tool has a side-effect class; anything that
changes state, leaves the app or reads personal data goes through the approval dialog. Long agent runs, report
generation and automation run in a foreground service (`specialUse`) with a Cancel action.

## 5. Form filling & automation

* **AutofillService** (`app/autofill`): fills fields in any app/browser from an encrypted **profile vault**
  (AES-GCM key in Android Keystore, user-authentication-bound when the phone has a screen lock; unlocked by
  biometric/PIN in `VaultUnlockActivity`). Field mapping = autofill hints + label/id rules, optional local-LLM
  resolution restricted to keys that exist; sensitive custom fields are only offered on exact label match. No
  passwords/cards are stored by default.
* **AccessibilityService screen agent** (`app/automation`, `core/runtime/phone/ScreenAgent.kt`): compact view-tree
  snapshot → grammar-constrained next action (tap/type/scroll/back/wait/ask/done) → policy check → perform; optional
  vision screenshot (`takeScreenshot`, API 30+) described by the vision model when the tree is not enough.
  Multi-step goals ("fill this internship form", "book in app X"), max steps, timeouts.
* Floating overlay: current step and planned action, Pause / Resume / Stop, **approval before submit / pay / send /
  delete / confirm-like buttons**, questions to the user.
* Teach by demonstration: record taps/typing in any app → macro (selectors, not coordinates) → edit, mark typed
  values as `{{params}}` → saved as a skill, runnable by name; optional WorkManager schedules (daily/weekly/interval,
  only-while-charging). Scheduled runs never press submit/pay/send — they stop and notify.
* Safety: CAPTCHA screens stop the agent (no bypass); OTP fields are typed only with a code the user gave in this
  run; banking / UPI / wallet / payment apps blocked by default (list + package-name heuristics; user can allow
  specific apps); password fields are never read or typed by the agent; full action log (Settings → Automation);
  explicit opt-in (prominent disclosure dialog before the system settings page) and a kill switch (Stop everything,
  notification Stop, turning the service off).

### Google Play policy notes (accessibility & privacy)

* The accessibility service is **not** declared `isAccessibilityTool="true"` (it is not primarily for people with
  disabilities), so a Play release must: show the prominent in-app disclosure (implemented) before enabling, explain
  in the Play listing + the Accessibility declaration form what is read and why, and record a video of the flow.
* Data accessed via accessibility, notifications, contacts, calendar and the vault never leaves the device (offline
  flavor has no INTERNET at all; connected flavor only talks to MCP servers the user adds). This must be stated in the
  privacy policy / Data safety form.
* No SMS, call-log or QUERY_ALL_PACKAGES permissions; messaging and calls are user-confirmed intents.
* Autofill service follows the AutofillService contract (user selects it in system settings; datasets require vault
  unlock).
* Sideloaded debug builds are not Play builds; a Play submission needs the declarations above and a privacy policy.

## Not verified / limitations

See KNOWN_ISSUES.md (Phase 3 section) and the verification numbers in `docs/verification/phase3-host-harness.txt`.
