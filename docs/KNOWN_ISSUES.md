# Known issues & limitations (Phase 2 + Phase 3)

## Not yet verified (needs a physical phone)
1. **Nothing Android-side has run on a device.** The sandbox has no emulator/phone. Verified in sandbox: 79 JVM unit tests,
   the real JNI bridge + llama.cpp b11609 on ARM64 Linux (host harness: grammar Canvas JSON, agent tool calls, embeddings
   retrieval, image input), APK contents/permissions/16 KB alignment. **Not verified**: every Compose screen, Room on a
   device, SAF/photo picker/camera, PdfBox on real PDFs, share/calendar intents, CPU-variant selection on a phone SoC,
   real tok/s, thermals, jank. See DEVICE_TEST_PLAN.md (§9–§16 are new).
2. **Built with a community aarch64-host NDK r30 + static aarch64 aapt2** (sandbox is aarch64 Linux). Rebuild release
   builds on a normal x86_64 machine with Google's NDK r30 / Android Studio; no source changes needed.

## Model quality
3. **Qwen3-0.6B is weak at structured output.** Grammar sampling guarantees *valid* JSON, not *good* JSON: 0.6B makes
   simple canvases (often one table), sometimes ignores edit requests ("add a chart" kept only the table in the host test),
   and picks tools unreliably. Qwen3-1.7B passed every host check (canvas with chart+callouts, in-place update adding a
   chart, calculator tool call, skill → canvas). Use 1.7B (6 GB phones) or 4B (8 GB+) for Canvas and skills.
4. **Grammar guarantees shape, not facts.** Charts can contain made-up numbers when the user gives none; RAG answers can
   still misquote a passage. Source chips let the user check.
5. **Auto mode is keyword-based** (no extra model call). It can pick Canvas for a chatty "plan" question or Chat for a
   visual request phrased unusually. The Auto/Chat/Canvas switch above the composer overrides it.
6. **Thinking mode** applies only to plain chat; Canvas, agent and vision turns always run with thinking off.

## Canvas / UI
7. Checklist ticks, quiz answers and the flashcard position are UI state only; they are not saved into a new canvas version.
8. Canvas `image` blocks can only show photos the user attached in that chat (`attachment:N`). No network images, by design.
9. Charts support bar, line and pie with ≤12 labels and ≤3 series (schema limits keep generation short on a phone).
10. Canvas generation is capped at 1400 tokens; very large documents are cut and then fall back to markdown if invalid.

## RAG
11. **Scanned PDFs (images only) have no text** → "no extractable text". OCR is not included.
12. Brute-force vector search loads all READY chunk vectors per query: fine up to a few thousand chunks (≈ tens of MB of
    PDFs); beyond that an ANN index would be needed.
13. Changing the embedding model makes old vectors incompatible (different dimension): use **Docs → Re-embed all**.
    Until then those documents fall back to keyword search.
14. Indexing runs in the app process (not WorkManager): leaving the app for a long time can let Android kill it; the
    half-indexed document is then marked FAILED/removed and must be imported again.

## Agent / skills
15. *(Phase 3: long agent runs, reports and automation now run under a foreground service with Cancel.)* Agent runs live in the application scope: if Android kills the process mid-run, the run is
    checkpointed and offered for **Resume** at next launch, but a pending approval must be answered again.
16. Only one generation at a time (chat, canvas or agent). Sending while an agent runs is blocked with a notice.
17. Skill triggers only *suggest* a skill (chip above the composer); skills run when chosen (chip, + menu, /skill-id or
    Skills tab). This avoids hijacking normal questions such as "what is…".
18. `summarize` inside an agent run is a nested model call and can take a while on 4B models (120 s tool timeout).

## Multimodal
19. **One model in RAM at a time**: sending a photo loads the vision model (unloading the chat model) and the next text
    message reloads the chat model. On a phone this is a few seconds each way.
20. Only the latest user message keeps its photo in the prompt; earlier photos in the chat are dropped from context.
21. Image cost: SmolVLM2-256M encoded a test image as 358 tokens in ~14 s on 2 sandbox cores (capped at 512 visual
    tokens per image). Qwen3-VL-2B will be slower; expect 10–40 s per photo on mid-range phones.
22. **No on-device image generation** (out of scope for Phase 2; would need a separate diffusion runtime).
23. Audio input (mtmd supports some audio models) is not wired up.

## Performance / memory
24. *(Superseded in Phase 3: context is Auto by RAM — 16K on 8 GB with q8_0 KV.)* Phase 2 default context was **4096** (Canvas and agent prompts are long): KV ≈ 448 MiB for Qwen3-0.6B/1.7B, ≈ 576 MiB for 4B.
    On 4 GB phones choose 2048 in Models → Settings.
25. Debug APK is ~48 MB (un-minified Compose + Room + PdfBox resources + 7 CPU variants + libmtmd). Release with R8 will be much smaller.
26. PdfBox-Android is pure Java: a 300-page PDF can take a minute to extract on a mid-range phone (progress + cancel shown).

## Cosmetic / logs
27. On Stop, logcat shows `ggml_backend_sched_graph_compute_async failed with error 1` / `llama_decode: failed to decode, ret = 2`
    — the expected abort path.
28. SmolVLM2 logs `control-looking token … was not control-type` at load — harmless model metadata warning.
29. Links in answers are rendered but never opened (no browser intents); tapping copies the URL.

## Phase 3 (speed, long context, PDF, MCP, phone actions, automation)
30. **Still nothing verified on a phone.** Speed, RAM, heat/throttling, 16K/32K behaviour, autofill in real apps,
    accessibility automation on real UIs, overlay, foreground service, WorkManager schedules, PdfDocument output,
    MediaStore/SAF saving, binder MCP between two apps — all compile and are covered by JVM tests or the host harness
    where possible, but need DEVICE_TEST_PLAN §17–§23.
31. **Host numbers are not phone numbers.** The harness ran on 2 ARM64 server cores with Qwen3-1.7B **Q8_0** (the only
    1.7B file in the sandbox); a phone with Q4_K_M and 4 big cores will differ (likely faster decode, similar or faster
    prompt reading). See docs/verification/phase3-host-harness.txt.
32. **Long prompts are slow on CPU.** Reading a full 16K window takes minutes on 2 cores (and likely 1–3 min on a phone).
    The prompt-prefix cache, rolling summaries and memory paging exist so normal chats never re-read that much.
33. **32K context** is allowed on 8 GB phones with a warning; **64K/128K** need YaRN (beyond Qwen3's 40K trained
    context) and more RAM than an 8 GB phone can spare for a 1.7B model — the planner disables them there. Untested.
34. **q4_0 KV was slower than q8_0** on the host (dequant cost on the CPU path) with only a small RAM saving at 4K; it is
    offered for 32K on tight RAM, q8_0 stays the default.
35. **Speculative decoding** only helps when the target is much bigger than the draft (e.g. Qwen3-4B/8B + 0.6B). With a
    1.7B target on a CPU it can be slower; it is off by default and should be judged per phone (§18.8).
36. **Screen agent quality depends on the model.** 1.7B can follow simple forms; complex apps (custom-drawn UIs,
    WebViews without accessibility info, games) may fail. Vision screenshots need API 30+ and a vision model, and are slow.
37. **Autofill** maps fields with hints/labels/rules (+ optional local LLM for unknown labels). Password, OTP, card,
    CVV, Aadhaar/PAN fields are never filled. Some apps/browsers ignore third-party autofill datasets.
38. **Banking/payment block list** is a known-package list + name heuristics; an unknown payment app could slip
    through, so approval is still required before any pay/send/submit-like tap.
39. **Accessibility service on Google Play**: `isAccessibilityTool=false`; a Play release needs the prominent disclosure
    (implemented), the Accessibility declaration form, a privacy policy and Data-safety entries. Sideload builds only for now.
40. **Connected flavor** allows cleartext HTTP so LAN MCP servers work; use HTTPS + a token for anything remote.
41. **Binder MCP servers** must declare the `com.offlineagent.mcp.SERVER` action and hold/require
    `com.offlineagent.permission.USE_MCP_SERVERS`; third-party tools always ask for approval.
42. **Release build (R8 + baseline profile)** is configured but only the debug APKs were built and checked here.

## Phase 4b
- Phone prefill speed is not measured yet; the first message of a new chat still waits for the ~350-token prompt (or the
  ~2 200-token full library when you ask for UI / choose "All components").
- Prompt progress updates once per prefill batch (512 tokens by default), so short prompts show only the status line.
- Thinking is on by default: answers take longer in total (the reasoning is generated first), but text appears at once.
- The agent card streams raw step output (JSON tool calls look technical); the final answer is formatted as before.
- StrictMode only logs (debug builds); it does not crash on violations.

## Phase 5 (multi-agent ONYX runtime) — see docs/PHASE5.md §6
- **Nothing in Phase 5 has run on a phone yet**: new Home/Tasks/Memory/Agents UI, voice (SpeechRecognizer/TTS), WorkManager
  resume + outbox, AlarmManager exact reminders, brightness, direct calendar insert, thermal listener — compile + JVM tests only.
- Agents share one model: model-bound steps are sequential; only tool/IO work overlaps. Expect 3–5 model calls per task.
- Voice is only guaranteed offline with Android's on-device recognizer (API 31+ with a language pack); otherwise the UI warns.
- A task resumed in the background that needs approval waits 9 minutes (notification), then counts as declined.
- Android does not allow toggling Wi-Fi/mobile data/airplane mode or reading other apps' data; ONYX opens Settings panels.
- The Phase 1–4 chat agent and its key/value memory are unchanged and separate from the Phase 5 memories.

## Phase 5b — see docs/PHASE5.md §10
- The new agent trace, Agents chip in Chat, Home model card/onboarding and Agents catalogue are compiled but **not yet run on a phone**.
- Small models still make content mistakes in the final answer (wrong weekday, "queued offline" invented on a retry);
  the Verifier's model check is a yes/no from the same small model and can miss or over-flag.
- Coverage repair and the rule planner are English keyword heuristics; unusual phrasing may still lose a step.
- Planning and argument filling remain separate model calls (a planned 2-tool task ≈ 3–5 calls; simple requests 1).
- After an app restart, old chat traces show saved states/outputs only (no live thoughts or call timings).
- Chat "Agents: auto" sends any request with tool words (e.g. "explain how to set up Wi-Fi") to the agents; they answer
  directly (1 call) but without the Chat mode's rich-UI prompt. Switch to "Agents: off" for pure chat.

## Phase 6 — see docs/PHASE6.md
- **No device test yet**: tiers, speculation, power governor, benchmark, the Memory screen (Threads, contacts, routines),
  memory chips and temporary chat are compiled and JVM-tested only. Speculation numbers are from a 2-vCPU sandbox host.
- With Qwen3 1.7B greedy code-edit prompts, output differs when n-gram speculation is on (probably numeric drift from
  batched verification vs single-token decode).
- Memory extraction is English rules plus an optional small-model pass; unusual phrasing can be missed or mis-worded
  (inferred memories are marked until confirmed).
- Chat-history retrieval is keyword (BM25), not semantic: paraphrases with no shared words are not found.
- Deleting a thread forgets its summary and snippets; the chat's next summary (if it keeps growing) is re-indexed.

## Phase 6 AppBridge — see docs/PHASE6-APPS.md
- The legacy Phase 1–4 chat path has no scoped approvals ("Allow <App> for this task" exists only on the Onyx/agents path).
- The app catalogue is in memory only (rebuilt on start / package changes, not persisted).
- `clipboard_get` works only while ONYX is in the foreground (Android 10+).
- `notif_reply` (inline RemoteInput reply) is untested on a device; the AppBridge Android side is compiled, not phone-tested.

## Phase 6 screen tools v2 — see docs/PHASE6-SCREEN.md
- Sideload only: Google Play bans autonomous accessibility agents. Build a Play APK with `-PscreenAgent=false`.
- Overlay **Pause** is a no-op for v2 (only Stop works).
- The secure-window (FLAG_SECURE) check (`takeScreenshot` probe) is untested on a device.
- `awaitIdle` polls the accessibility tree every 120 ms instead of listening to window events.
- No vision fallback: screens without an accessibility tree (games, canvases, some WebViews) cannot be operated.
- With the toggle on, the legacy chat agent (Phase 1–4 path) can still see the `screen_*` tools; in-app requests no longer reach it (6.1 routes them to ONYX), but an explicit `/skill` run still uses the legacy loop.
- ~~The shared AppSafetyPolicy blocks the whole Settings app~~ — 6.1: Settings is page-gated (see below). The Play Store stays blocked for screen use.
- The Android side (ports, overlay host, settings card) is compiled, not phone-tested.

## Phase 6.1 (0.6.1) — screen agent routing, Settings allowlist, macro launcher fix — see docs/PHASE6.md §6.1
- **Fixed: "screen control only opens apps".** Causes: (1) the Screen-automation phone group is off by default, so with only
  the Agents toggle and the accessibility service on, the kill switch refused every `screen_*` call (STOPPED) and `app_open`
  was the only tool that worked; (2) the planner (Qwen3 1.7B, ≤4 one-shot steps, tools picked by word overlap) chose
  `app_open` for "open X and …", and nothing in the plan could do a multi-step in-app loop (`screen_do` is one step;
  `run_phone_task` is hidden while v2 is on); (3) the verifier accepted "opened the app" as done; (4) Chat's Agent/Phone
  modes used the legacy agent, which only had intent openers. 6.1: Agents toggle on → Screen-automation group allowed;
  `Triage` routes in-app requests to `screen_open_app` → `screen_task` (new perceive→act loop) without a planner call;
  Chat sends them to ONYX in every mode; `InAppCheck` + the Writer never report `app_open` alone as done; a status banner
  (Chat + Agents) shows service/toggle/kill-switch mismatches with fix buttons.
- `InAppRequest` is English, pattern-based ("open X and …", "in X, …", "… on X"). Phrasings it misses still go to the
  planner, which is repaired (opener-only plan → screen steps) but can still pick wrong tools on a small model.
- `screen_task` stops before Send/Submit/Enter unless the goal asks to send; then the step still needs the per-step OK.
  A list row whose text contains words like "call" or "share" counts as irreversible and stops the loop early.
- Loop guard: same action on the same screen 3× → STUCK; ≤12 actions by default (max 25).
- **Settings allowlist** (screen only; AppBridge still never opens Settings): allowed Wi-Fi, Bluetooth, Display, Sound,
  Do Not Disturb, Battery saver; ask every step: Location, Airplane mode, Mobile data, Hotspot; everything else blocked
  incl. the Settings home and search. Pages are entered only by their entry intent and identified by the window title
  (top 22% of the screen / toolbar ids) — English titles only; OxygenOS titles are a best guess and untested on a phone.
  An unknown title blocks (Back still needs the user). `TETHER_SETTINGS` / `DATA_ROAMING_SETTINGS` fall back to
  `WIRELESS_SETTINGS` when the phone lacks them.
- **Fixed: macro recorded from the home screen failed at step 1** ("Open com.android.launcher → failed"). The launcher has
  no launch intent. The recorder now skips launcher/systemui/ONYX windows, turns a home-screen icon tap into "open <app>",
  and returning home into a HOME step; the runner replays a launcher step as Home and explains open failures; saved macros
  are migrated on load (and re-saved). Home is detected with `resolveActivity(ACTION_MAIN + CATEGORY_HOME)` (needs the
  new `<queries>` entry). An icon whose label matches no launchable app is dropped from the recording.
- Still not run on a phone: the OnePlus Nord CE 3 checks are in docs/PHASE6.md §6.1.


## Phase 6.2 (0.6.2) — macros, screen agent, overlay approvals — see docs/PHASE6.md §6.2
- **Not run on a phone yet.** Everything is JVM-tested on the fake phone; the Android recorder, overlay card, highlight,
  notification fallback and key-event recording have only been compiled.
- **Apps with poor accessibility trees** cannot be replayed exactly: games, custom-drawn / canvas UIs, many Flutter and
  some React Native / Unity apps expose few or no nodes. Replay then has only the relative-position fallback, which is
  never used for list rows or irreversible steps, so such steps stop with a clear reason instead of tapping blindly.
- **Secure screens** (FLAG_SECURE, banking, password managers) are never automated; they are handed to you.
- **Enter on the on-screen keyboard** is invisible to accessibility on most keyboards: tick "Press Enter after typing" on
  that step in the editor. Back is recorded from key events while recording; on phones whose gesture navigation does not
  send a key event, Back is inferred when you return to an earlier screen without a tap.
- **Fingerprint drift**: if an app redesigns a screen completely (new view ids), a step may wait up to ~15 s and stop with
  "expected … but saw …". Record it again.
- **Typed text in some apps is not readable**: when a field never reports its text, replay notes it and continues.
- **WhatsApp chat link** needs the contact's number typed in the editor once (no contacts lookup yet), and only works for
  a fixed chat (not a `{{chat}}` value).
- **Accessibility re-enable**: the service config now requests long-press and key-event filtering; OxygenOS may turn the
  service off after updating. Key-event filtering is switched on only while recording.
- **Overlay over secure screens**: approvals then go to a heads-up notification. If notifications are blocked for ONYX and
  the service is off, the request falls back to ONYX's own sheet (open ONYX to answer).
- The legacy chat agent (explicit `/skill` runs) still uses its in-chat approval card.
