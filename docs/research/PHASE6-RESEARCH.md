# Phase 6 research — "Universal app connection" + "Big model, top optimisation"

Status: **research only** (no code changed). Written 2026-11-03 against `master` @ `d759ea7` (Phase 5b).
Star counts / licences / last push are from the GitHub REST API on 2026-11-03. Every number below
has a source link; numbers marked **(est.)** are my own back-of-envelope estimates, not measurements.
Nothing in this document has been measured on the target phone yet — §B.8 is the plan to fix that.

---

## 0. Target phone (verified) and what it means

| Item | OnePlus Nord CE 3 5G — model **CPH2569** (India) | Source |
|---|---|---|
| SoC | Qualcomm **Snapdragon 782G (SM7325-AF)**, 6 nm, Android board name `lahaina` | [devicespecifications](https://www.devicespecifications.com/en/model/7d005d1b), [Geekbench CPH2569](https://browser.geekbench.com/v7/cpu/784883) |
| CPU | 1× Cortex-A78 2.7 GHz + 3× A78 2.4 GHz + 4× A55 1.8 GHz. ISA flags: `neon, fp16, dotprod` — **no i8mm, no SVE/SME** (ARMv8.2) | Geekbench (above) |
| GPU | **Adreno 642L** (2 compute units, OpenCL 2.0 driver) | [vovaxa44 bench](https://huggingface.co/datasets/vovaxa44/snapdragon778g-llama-cpp-opencl-bench/blob/main/README.md) |
| NPU/DSP | **Hexagon 770 = HTP v68** (same die as 778G) | [Qualcomm QAIRT table](https://docs.qualcomm.com/doc/80-63442-10/topic/SNPE_general_overview.html?product=1601111740010412) |
| RAM | **12 GB** (Sai's unit; confirmed 2026-11-03 — the 7.15 GB Geekbench run was an 8 GB variant). Type is **uncertain**: sources disagree LPDDR5 vs LPDDR4X — measure on device | Kalvo vs devicespecifications |
| Storage | UFS 3.1 | Kalvo spec page |
| OS | Geekbench run shows Android 15 | Geekbench |

Consequences (these drive the whole of Part B):
1. **Decode is memory-bandwidth bound on the CPU.** On the same SoC family (SD778G, 8 GB LPDDR4X) llama.cpp
   decoded Qwen2.5-3B Q4_K_M at **7.45 tok/s with `-t 4` = ~15.6 GB/s ≈ 91 % of DRAM peak**; `-t 6/8` were slower. ([vovaxa44](https://huggingface.co/datasets/vovaxa44/snapdragon778g-llama-cpp-opencl-bench/blob/main/README.md))
2. **GPU (OpenCL, Adreno 642L) is slower than CPU at every `-ngl`** (tg −30 % … −70 %) and needs 3 driver patches; flash-attention fails on that driver. Same source.
3. **NPU (HTP v68) is not worth it.** Upstream merged v68/v69 code (PR #17394) but the maintainer said performance "is not going to be that good" ([PR](https://github.com/ggml-org/llama.cpp/pull/17394)); the vendored b11609 Hexagon CMake builds only v73/v75/v79/v81 skels (`third_party/llama.cpp/ggml/src/ggml-hexagon/CMakeLists.txt`); even v73 without FP16 HMX was slower than CPU ([#29473](https://github.com/ggml-org/llama.cpp/issues/29473)).
4. **i8mm-only fast paths (KleidiAI i8mm, Q4_0 4x8 repack) do not apply.** The dotprod paths do: vendored `ggml-cpu/repack.cpp` picks `q4_0_4x4_q8_0`, `q4_K_8x4_q8_K`, `iq4_nl_4x4` on NEON+dotprod.
5. **AppFunctions (Android 16+) and Computer Control (OEM-preloaded only) are unavailable** to ONYX on this phone (see A.3).

---

## Part A — Universal app connection (offline)

### A.1 What ONYX already has (so Phase 6 is an upgrade, not a rewrite)
`OfflineAccessibilityService` + `AutomationController` + `core/runtime/phone/ScreenAgent.kt` already do:
view-tree snapshot → numbered elements → grammar-constrained single action (tap/type/scroll/back/open_app/ask_user/done)
→ `AutomationPolicy` (banking/payment package blocklist, CAPTCHA stop, password/OTP rules, irreversible-target approval)
→ overlay preview + kill switch → log; plus macros (record/replay), autofill, screenshot description,
`read_screen` / `run_phone_task` / `list_apps` / `open_app` / `open_link` / draft_* tools, notification reading,
`riskOf()` (SCREEN_ACTION/EXTERNAL_INTENT = HIGH; send/pay/delete names = CRITICAL), ApprovalSheet, audit log.
Gap: the screen agent is a **separate inner loop** (its own ReAct-style loop) instead of first-class tools of the
Phase 5 Planner/Executor DAG, it re-describes the whole screen every step (expensive at 8K ctx on a 1.7B model),
it has no cross-app capability catalogue, and it only uses one channel (accessibility) where cheaper,
Play-safe channels exist (intents, share sheet, notification actions, assist).

### A.2 Top repositories (Part A)

| Repo | ★ | Licence | Last push | Reusable idea for ONYX |
|---|---:|---|---|---|
| [droidrun/mobilerun](https://github.com/droidrun/mobilerun) (ex-DroidRun) + [mobilerun-portal](https://github.com/droidrun/mobilerun-portal) | 9 911 / 401 | MIT / NOASSERTION | 2026-11-03 | On-phone **Portal app = accessibility service** exposing UI tree + screenshots; a11y tree first, `--vision` / `--vision-only` fallback; **"app cards"** (per-app guidance) and manager/executor "reasoning mode". |
| [vercel-labs/agent-desktop](https://github.com/vercel-labs/agent-desktop) | 10 200 | Apache-2.0 | 2026-11-03 | **Refs scoped to a snapshot** (`@snap:e8`; bare ref rejected, `STALE_REF`/`AMBIGUOUS_TARGET` errors force re-snapshot); `--skeleton` map then `--root` drill-down for dense apps; act through the a11y API "headless" (no pointer); JSON envelope with `error.disposition.retry`. |
| [mobile-next/mobile-mcp](https://github.com/mobile-next/mobile-mcp) | 10 234 | Apache-2.0 | 2026-11-02 | "Accessibility-first — no vision model, no image tokens", screenshot+coords only as fallback; a clean MCP tool vocabulary (`list_elements_on_screen`, `click…` by ref, `type_keys`, `press_button`, `launch_app`, `batch_commands`). Host-side (adb), not on-phone. |
| [X-PLUG/MobileAgent](https://github.com/X-PLUG/MobileAgent) (Mobile-Agent-v3.5 / GUI-Owl-1.5) | 9 354 | MIT | 2026-11-02 | GUI-Owl-1.5 **2B/4B/8B** native GUI models on Qwen3-VL (AndroidWorld 71.6 for the family's best; [arXiv 2602.16855](https://arxiv.org/abs/2602.16855)); planner/executor/reflector multi-agent design. |
| [zai-org/Open-AutoGLM](https://github.com/zai-org/Open-AutoGLM) | 26 524 | Apache-2.0 | 2026-03-06 | Screenshot→VLM→coords via ADB; **sensitive-operation confirmation callback + `Take_over` action** for login/captcha; black screenshot = secure page → hand back. Model is 9B (GLM-4.1V arch): too big for this phone. |
| [bytedance/UI-TARS](https://github.com/bytedance/UI-TARS) / [UI-TARS-desktop](https://github.com/bytedance/UI-TARS-desktop) | 11 666 / 39 528 | Apache-2.0 | 2026-01 / 2026-10 | Mobile action space (`long_press`, `open_app`, `press_home/back`); UI-TARS-1.5-7B open weights. 7B = too slow here. |
| [web-infra-dev/midscene](https://github.com/web-infra-dev/midscene) | 15 478 | MIT | 2026-11-03 | `aiAct / aiWaitFor / aiAssert` triad — **assert/wait as first-class steps** (maps to ONYX Verifier); Android via ADB; supports AutoGLM. |
| [minitap-ai/mobile-use](https://github.com/minitap-ai/mobile-use) | 3 355 | Apache-2.0 | 2026-11-03 | UI-tree-driven agent; structured **data extraction from any app into JSON** ("scraping" as a tool). Claims 100 % AndroidWorld with cloud LLMs. |
| [TencentQQGYLab/AppAgent](https://github.com/TencentQQGYLab/AppAgent) | 6 915 | MIT | 2025-03-19 | **Exploration/demonstration phase builds a per-app knowledge doc** reused at run time (→ ONYX "app cards" + macros). Stale. |
| [Tongyi-MAI/MAI-UI](https://github.com/Tongyi-MAI/MAI-UI) | 2 427 | none listed | 2026-08-19 | MAI-UI-**2B** on-device GUI model; reported +75 % relative over Ferret-UI Lite on AndroidWorld ([arXiv 2512.22047](https://arxiv.org/pdf/2512.22047)); device-cloud split. Licence of repo unclear (HF card says Apache-2.0 per featherless listing) — check before shipping. |
| [apple ml-ferret](https://github.com/apple-aiml-research/ml-ferret) (Ferret-UI Lite paper) | 8 649 | NOASSERTION (Apple) | 2026-09 | 3B on-device GUI agent: ScreenSpot-V2 91.6, AndroidWorld **28.0 %** ([arXiv 2509.26539](https://arxiv.org/abs/2509.26539)) — evidence that small *end-to-end* GUI agents are still weak at navigation. |
| [showlab/ShowUI](https://github.com/showlab/ShowUI) | 1 929 | Apache-2.0 | 2026-04-24 | UI-guided visual token selection (drop redundant patches) — same trick Opposable uses. |
| [OS-Copilot/OS-Atlas](https://github.com/OS-Copilot/OS-Atlas) | 455 | Apache-2.0 | 2025-04-20 | Grounding datasets/models; stale. |
| [OpenBMB/AgentCPM-GUI](https://github.com/OpenBMB/AgentCPM-GUI) | 1 428 | Apache-2.0 | 2026-01-11 | 8B on-device GUI agent, Chinese+English apps; RFT/GRPO recipe. 8B too heavy here. |
| [stepfun-ai/gelab-zero](https://github.com/stepfun-ai/gelab-zero) | 2 293 | MIT | 2026-05-11 | Step-GUI; another local GUI agent stack. |
| [microsoft/OmniParser](https://github.com/microsoft/OmniParser) | 25 602 | CC-BY-4.0 | 2026-07-20 | Pure-vision screen parsing into elements — only relevant for a11y-less apps. |
| [android/appfunctions](https://github.com/android/appfunctions) | 216 | Apache-2.0 | 2026-10-31 | Official AppFunctions samples ("Android MCP"). |

Non-repo but important write-up: **Opposable** ran **GUI-Owl-1.5-2B Q4 in llama.cpp + mtmd inside an Android app**
(LM 1.05 GB + vision encoder 445 MB). Tricks that worked: crop status/nav bar/keyboard, drop flat-background image
tokens (25–40 % fewer, accuracy 27→28/36), keep the system-prompt KV, **"grounded early exit"** (model writes
"Action: Click the Files app icon." → if exactly one a11y node matches, tap it and stop decoding; decode 0.8–1.3 s
instead of 2–4 s), **~40 common tasks as fixed code with no model**, and **one subgoal at a time verified against the
a11y tree** (held-out tasks 4/8 → 6/8). Step time 7.3 s on an M2 CPU, of which 3.6 s is the vision encoder
([blog](https://getopposable.com/blog/computer-use-on-the-phone)). On an A78 phone CPU expect several times slower **(est.)**.

### A.3 Android platform channels — what a normal (non-system) app can and cannot use

| Channel | Can ONYX use it? | Offline | Play-safe | Notes |
|---|---|---|---|---|
| **Launcher catalogue** (`PackageManager.queryIntentActivities(MAIN/LAUNCHER)`) | ✅ already declared in `<queries>` | ✅ | ✅ | Gives visibility of every launchable app without `QUERY_ALL_PACKAGES` (restricted by Play policy, [Play policy](https://support.google.com/googleplay/android-developer/answer/16558241)). |
| **Intent capabilities** (`ACTION_SEND`, `VIEW` + scheme, `WEB_SEARCH`, `MEDIA_PLAY_FROM_SEARCH`, `INSERT` calendar, `SET_ALARM`, `DIAL`, `SENDTO`) resolved per package | ✅ (add matching `<queries>` entries) | ✅ | ✅ | Deterministic, no screen reading. Best "universal" channel. |
| **Share-in** (`ACTION_SEND` receiver) and **`ACTION_PROCESS_TEXT`** ("Ask ONYX" in every app's text-selection menu) | ✅ | ✅ | ✅ | Inbound connection from *any* app, user-initiated, zero automation risk. |
| **Assistant role** (`RoleManager.ROLE_ASSISTANT` + `VoiceInteractionService`) | ✅ if user picks ONYX as default digital assistant | ✅ | ✅ | On invoke the system can hand `AssistStructure` (view tree of the focused activity) and a screenshot (`SHOW_WITH_ASSIST`, `SHOW_WITH_SCREENSHOT`, `onHandleAssist(AssistState)`, `onHandleScreenshot`) — [VoiceInteractionSession docs](https://developer.android.com/reference/android/service/voice/VoiceInteractionSession). **Read-only, per invocation** → a Play-safe "what's on my screen?" without AccessibilityService. |
| **NotificationListenerService actions** (`Notification.Action` + `RemoteInput`) | ✅ (ONYX already has the listener for reading) | ✅ | ✅ (with disclosure) | Reply/mark-read/archive in WhatsApp, Gmail, SMS etc. **without opening the app**. Strong universal channel; HIGH/CRITICAL risk (it sends). |
| **AccessibilityService** (read tree, `performAction`, gestures, screenshot API 30+) | ✅ already | ✅ | ⚠️ see A.6 | The only truly universal *operate* channel for 3rd-party apps. |
| **App Shortcuts of other apps** (`LauncherApps.getShortcuts`) | ❌ unless ONYX is the default launcher (`hasShortcutHostPermission`) | — | — | Not worth becoming a launcher for this. |
| **AppFunctions — calling other apps** | ❌ caller needs `EXECUTE_APP_FUNCTIONS`, granted to privileged/system apps (Assistant role preloads); Android 16+; Gemini integration private preview ([overview](https://developer.android.com/ai/appfunctions), [AOSP commit](https://android.googlesource.com/platform/frameworks/base/+/1c7f2811703d207e823972aceb4d93c56733005a), [demo needing priv-app](https://github.com/sigusr1/appfunction_java_demo)) | — | — | Target phone is Android 15 (Geekbench) anyway. |
| **AppFunctions — ONYX exposing its own** | ⚠️ Android 16+ only; useful later so Gemini/OEM agents can call ONYX | ✅ | ✅ (new Play policy from 2027-04-14) | Low priority. |
| **Computer Control framework** (virtual display automation) | ❌ "OEM-preloaded AI assistants" with privileged `ACCESS_COMPUTER_CONTROL` ([docs](https://developer.android.com/ai/computer-control)) | — | — | Not available to ONYX. |
| **ADB-based agents** (mobile-mcp, Open-AutoGLM, Midscene) | ❌ need a host computer / wireless-debugging self-ADB hacks | — | — | Not a product path for an on-phone agent. |

### A.4 Screen understanding: tree vs pixels vs hybrid (decision)
* **Accessibility tree first.** Cheap in tokens, exact targets (node `performAction(ACTION_CLICK / ACTION_SET_TEXT /
  ACTION_SCROLL_FORWARD)`), no vision model, works offline with the 1.7B text model. All the strongest open stacks
  (mobile-mcp, mobilerun, agent-desktop, mobile-use) default to it.
* **Pixels only as fallback** for nodes the tree cannot describe (games, canvas/Flutter/Unity, some WebViews,
  icon-only buttons with no `contentDescription`). Small GUI VLMs that load in the vendored llama.cpp/mtmd:
  GUI-Owl-1.5-2B and MAI-UI-2B are Qwen3-VL fine-tunes; vendored mtmd has `PROJECTOR_TYPE_QWEN3VL`
  (`tools/mtmd/clip-impl.h`) and Opposable proved GUI-Owl-1.5-2B Q4 runs in llama.cpp on Android. Ferret-UI Lite 3B
  and ShowUI-2B weights exist but I did not verify GGUF conversions. On this phone a VLM step will be **tens of seconds
  (est.)** and, under ONYX's one-model rule, requires swapping out the text model → use rarely.
* **Hybrid = grounding by text.** Let the text model choose a target *by label*, resolve it to an a11y node in Kotlin,
  and fall back to the VLM only when 0 or >1 nodes match (Opposable's grounded early exit, inverted).

### A.5 Recommended Phase 6A design — "AppBridge"

New pure-Kotlin module **`:core:apps`** (JVM-testable like `:core:agents`) + Android ports in `app/onyx/apps/*`.
Everything is a **normal ToolSpec in the existing registry**, so the Planner can mix app tools with device tools in one
DAG, the Executor stays behind `ApprovalGate`, and every call lands in `audit_log`.

**A5.1 App catalogue (P0, Play-safe).** On app start + `PACKAGE_ADDED/REMOVED` broadcast, build
`AppEntry(pkg, label, category, versionCode, launchIntent, capabilities[])` where capabilities come from
`queryIntentActivities(intent.setPackage(pkg))` for a fixed verb list (send-text, send-image, view-https, view-geo,
web-search, media-play-from-search, calendar-insert, set-alarm, dial, sendto-mailto/smsto). Store in Room
(`onyx-agents.db`). Generated tools: `app_find(query)` (fuzzy on label + Indic transliteration later),
`app_open(pkg)`, `app_share(pkg, text|file_id)`, `app_view(pkg, uri)`, `app_search(pkg, query)`. Deep links: only the
schemes ONYX knows (no API exposes another app's intent filters without `GET_ACTIVITIES` filter data) — keep a small
curated, user-editable table (Maps `geo:`, YouTube search URL, WhatsApp `wa.me`, etc.). **Never auto-generate UPI
(`upi://pay`) intents** → hard block / CRITICAL.

**A5.2 Inbound connection (P0, Play-safe).** `ACTION_SEND`/`SEND_MULTIPLE` receiver → starts a Chat/Agents task with
the shared content as context; `ACTION_PROCESS_TEXT` activity ("Ask ONYX", "Summarise", "Translate") → available in
the text-selection menu of every app; Quick Settings tile → voice orb. Optional: `VoiceInteractionService` so the user
can set ONYX as default assistant and get read-only `AssistStructure` + screenshot on long-press (mapped into the
same `ScreenSnapshot` type) — this gives "read my screen" **without** enabling accessibility.

**A5.3 Notification bridge (P1).** Extend the existing listener: keep the last N `StatusBarNotification`s with their
`actions` (+ `RemoteInput` keys) in memory only. Tools: `notif_list(app?)` (SENSITIVE_READ, MEDIUM),
`notif_action(key, action_title)` (HIGH), `notif_reply(key, text)` (CRITICAL — sends a message; always shows the exact
text + recipient on the ApprovalSheet; never "always allow"). Works offline for local actions; a reply in an online app
is just a PendingIntent, so the outbox is not involved.

**A5.4 Screen tool v2 (P1, needs accessibility).** Refactor `ScreenAgent` from an inner loop into tools:
* `screen_snapshot(mode=skeleton|full, root?)` → returns `snapshot_id` and compact lines `e12 Button "Send" [click]`;
  refs are `snapshot_id:e12` and **stale refs are rejected** (agent-desktop). Skeleton mode (top-level containers +
  counts) keeps the prompt small for the 8K context; `root=` drills into one region.
* `screen_act(ref, action=click|long_click|set_text|scroll_fwd|scroll_back|focus, text?)` → uses
  `AccessibilityNodeInfo.performAction` (no coordinates, no pointer), waits for `TYPE_WINDOW_CONTENT_CHANGED` idle,
  returns the **diff** (what appeared/disappeared) instead of a full new tree.
* `screen_find(text|role)`, `screen_wait(text, timeout)`, `screen_assert(text)` (Midscene's `aiWaitFor/aiAssert` as
  deterministic Kotlin checks → the Verifier's rule-first path).
* `screen_extract(schema)` → grammar-constrained JSON from the visible tree (mobile-use "scraping").
* Planner emits **subgoals** ("open WhatsApp chat with X", "type message", "stop before Send"); each subgoal node is
  verified against the tree before the next (Opposable 4/8 → 6/8).
* **App cards**: per-package markdown notes (where the search box is, quirks) learned from successful runs and from
  user-recorded macros; injected only for the current package (mobilerun app cards, AppAgent knowledge doc). Successful
  subgoal sequences are offered back to the user as macros ("Save this as a routine?").
* VLM fallback tool `screen_look(question)` only when `screen_snapshot` has no usable nodes; runs GUI-Owl-1.5-2B or
  MAI-UI-2B via mtmd after unloading the text model (InferenceQueue already serialises; add a "model swap" job type).

**A5.5 MCP surface (P2).** Publish the AppBridge tools through the existing `mcp-aidl` binder server (and `:core:mcp-http`
in the connected flavor) using mobile-mcp-compatible names so other local agents can drive the phone through ONYX's
policy layer instead of their own.

**A5.6 Safety & approvals (applies to all of the above).**
* Risk mapping: catalogue/find = NONE; `screen_snapshot`/`notif_list`/assist read = MEDIUM (SENSITIVE_READ, approval
  once per task); `app_open`/`app_view`/`screen_act` on non-committing nodes = HIGH but grantable as a **scoped session
  approval** ("operate *App X* for this task, ≤ N steps, until done") so the user is not asked 20 times;
  `isIrreversibleTarget` taps, `set_text`+submit, `notif_reply`, `app_share` to a messaging app = CRITICAL, per-step, never
  auto-allowed (existing rule: HIGH/CRITICAL never "always allowed").
* Hard blocklist (extend `AutomationPolicy.BLOCKED_PACKAGES`): UPI/banking/brokerage (PhonePe, GPay, Paytm, BHIM, bank
  apps), password managers, authenticator apps, **ONYX itself, Settings → Accessibility / Device admin / Install
  unknown apps / Developer options** (an agent must never grant itself permissions), Play Store purchase screens,
  `FLAG_SECURE` windows (screenshot is black → stop, like Open-AutoGLM), views marked `accessibilityDataSensitive`
  (Android hides them from non-accessibility-tool services anyway — [blog](https://android-developers.googleblog.com/2025/12/enhancing-android-security-stop-malware.html)).
* **Prompt-injection guard:** screen text and notification text are *data*, wrapped in delimiters, never instructions;
  the Verifier rejects actions not traceable to the user's goal; a new app package appearing mid-task (e.g. a link
  opened a browser) pauses for approval.
* Audit: log every snapshot (package, activity, element count, hash — not the text) and every action with ref label,
  approval id and outcome. Kill switch overlay stays.

### A.6 Distribution / policy reality (important)
* Google Play's Accessibility policy (current text): the API "cannot be requested for … **an app that autonomously
  initiates, plans, and executes actions or decisions**"; non-accessibility-tool apps need a Play declaration,
  listing disclosure, prominent in-app disclosure and consent; `isAccessibilityTool=true` only for disability tools
  ([Play policy](https://support.google.com/googleplay/android-developer/answer/16558241)). → ONYX's screen agent
  **cannot ship on Play.** The intent/share/process-text/notification-action/assist channels can.
* Sideloading (GitHub Releases / F-Droid) keeps working; Android developer verification is enforced only in Brazil,
  Indonesia, Singapore, Thailand since 2026-09-30 and "globally in 2027"; a free **limited-distribution** account
  covers students/hobbyists (20–50 devices) and an advanced flow / ADB remains for power users
  ([Android Developers](https://developer.android.com/developer-verification/guides/limited-distribution),
  [Android Police](https://www.androidpolice.com/androids-confusing-new-sideloading-limitations/)).
  Recommendation: register the package name under a limited-distribution account before the 2027 global rollout.
* Android 17 Advanced Protection Mode restricts non-accessibility-tool services ([write-up](https://therulingdesk.com/articles/android-17-advanced-protection-accessibility/)) — detect it and degrade to the Play-safe channels.
* Suggested build split: `offline` flavor (full AppBridge incl. screen agent, sideload) vs a future `play` flavor
  (catalogue + intents + share/process-text + notification actions + assist; screen agent compiled out).

### A.7 Effort & order (one developer, rough)
| Step | Effort | Depends on |
|---|---|---|
| A5.1 catalogue + intent tools + Room table + tests | 3–4 days | — |
| A5.2 share/process-text/QS tile (+ assistant role as stretch) | 2–3 days (+3) | — |
| A5.3 notification actions/reply | 2–3 days | existing listener |
| A5.4 screen tools v2 (refs, skeleton, diff, assert/wait, subgoal verify, app cards) | 7–10 days | A5.1 |
| A5.4 VLM fallback (GUI-Owl-1.5-2B via mtmd + model swap job) | 4–6 days + device tuning | B measurements |
| A5.5 MCP surface | 2 days | A5.1–A5.4 |

---

## Part B — Big models and top optimisation on SD782G-class phones (target: every 8 GB phone; Sai's CPH2569 has 12 GB)

### B.1 Top repositories (Part B)

| Repo | ★ | Licence | Last push | Reusable idea |
|---|---:|---|---|---|
| [ggml-org/llama.cpp](https://github.com/ggml-org/llama.cpp) (vendored b11609) | 133 211 | MIT | 2026-11-03 | Already the engine. Unused-by-ONYX features present in the vendored tree: `LLAMA_LOAD_MODE_DIRECT_IO`, dotprod repacks, speculative types `DRAFT_EAGLE3`, `DRAFT_MTP`, `DRAFT_DFLASH`, `DRAFT_DSPARK`, `NGRAM_SIMPLE/MAP_K/MAP_K4V/MOD/CACHE` (`common/common.h`). |
| [JustVugg/colibri](https://github.com/JustVugg/colibri) | 46 654 | Apache-2.0 | 2026-11-03 | Disk-streamed **MoE experts**: dense part in RAM, routed experts read per token via `pread` with a learned per-layer LRU + hot set; router-lookahead prefetch; reads GGUF as-is. **Desktop-class** (x86/macOS/Vulkan/CUDA; min 8 GB RAM and 22 GB disk; no Android build). Ideas transfer; code does not. |
| [Helldez/BigMoeOnEdge](https://github.com/Helldez/BigMoeOnEdge) (mirror: supastishn/edgeMoE) | 703 | Apache-2.0 | 2026-11-03 | **The Android version of that idea, on llama.cpp's public API** (eval-callback hook, one optional ~25-line patch for overlap). Measured on a 12 GB, UFS 4.x phone: Qwen3-30B-A3B Q4_K_M (18.5 GB) **5.2 tok/s** with a 4 GB expert cache (mmap baseline 2.0 "unstable"; cache off 1.7). Knobs: expert cache, O_DIRECT, I/O lanes, `--dense-weights anon/ahwb` (keep dense weights out of reclaimable page cache), lossy `--n-expert-used`. |
| [Tiiny-AI/PowerInfer](https://github.com/Tiiny-AI/PowerInfer) (ex-SJTU-IPADS) | 9 862 | MIT | 2026-05-11 | Activation-locality (hot/cold neurons). PowerInfer-2 paper: TurboSparse-Mixtral-47B at 11.68 tok/s on a 24 GB OnePlus 12 ([arXiv 2406.06282](https://arxiv.org/html/2406.06282v2)) — needs **ReLU-sparsified** models; I did not find a usable PowerInfer-2 phone runtime in the repo (SmallThinker framework is there). |
| [kvcache-ai/ktransformers](https://github.com/kvcache-ai/ktransformers) | 19 655 | Apache-2.0 | 2026-10-28 | CPU/GPU heterogeneous MoE offload — server/desktop GPUs; not applicable to phones. |
| [ARM-software/kleidiai](https://github.com/ARM-software/kleidiai) | 203 | (mirror) | 2026-11-02 | Micro-kernels; biggest wins need i8mm/SME which A78 lacks. ONYX disabled it (P0 garbage output) — keep off on this phone. |
| [google-ai-edge/LiteRT-LM](https://github.com/google-ai-edge/LiteRT-LM) | 6 805 | Apache-2.0 | 2026-11-03 | Gemma-4-E2B: 47 tok/s CPU / 52 GPU decode on S26 Ultra ([numbers](https://developers.google.com/edge/litert-lm/overview)) — possible alternate engine for Gemma only; no GBNF grammar parity. |
| [alibaba/MNN](https://github.com/alibaba/MNN) | 16 339 | Apache-2.0 | 2026-11-02 | Fast mobile CPU LLM runtime (TokForge leaderboard top entries use it). Alternate engine, re-conversion needed. |
| [mlc-ai/mlc-llm](https://github.com/mlc-ai/mlc-llm) | 23 296 | Apache-2.0 | 2026-10-30 | Compiled GPU path; GPU loses on Adreno 642L anyway. |
| [SafeAILab/EAGLE](https://github.com/SafeAILab/EAGLE) | 2 568 | NOASSERTION | 2026-02-20 | EAGLE-3 heads; llama.cpp has `DRAFT_EAGLE3`; need a head trained for the exact target. |
| [a8nova/adreno-llms](https://github.com/a8nova/adreno-llms) | 26 | Apache-2.0 | 2026-10-31 | Hand-written OpenCL for Adreno 6xx (1-bit Bonsai). Niche; confirms 6xx needs custom kernels. |
| [kan-linux/ggml-hexagon](https://github.com/kan-linux/ggml-hexagon) (ex-zhouwg) | 64 | MIT | 2026-11-01 | Alternative FastRPC Hexagon backend; flagship-focused. |
| [OpenBMB/MiniCPM-V](https://github.com/OpenBMB/MiniCPM-V) | 27 052 | Apache-2.0 | 2026-10-30 | On-device VLMs; I could not verify a "MiniCPM-V 4.7 MoE" release — treat as unconfirmed. |

### B.2 How loading actually works on Android (what to rely on)
* **mmap (current default in ONYX, `LLAMA_LOAD_MODE_MMAP`)**: weights are clean, file-backed pages. Android can
  drop them under pressure and re-fault them from flash later — the app is not killed for them, but decode speed
  collapses into a page-fault storm when the model is near or above free RAM (BigMoeOnEdge measured the mmap
  baseline for an 18.5 GB model on 11.3 GB RAM at "2.0 tok/s, unstable", and 0.1 tok/s for 22.3 GB).
* **mlock**: llama.cpp has `MLOCK`/`MMAP_MLOCK` modes, but ordinary Android apps get a tiny `RLIMIT_MEMLOCK`, so
  locking a multi-GB model is expected to fail **(verify on device: log `getrlimit(RLIMIT_MEMLOCK)` in the JNI)**.
* **Anonymous copy (`--no-mmap`)**: counts against the app's PSS → low-memory-killer target. Useful only for the
  small always-hot part (BigMoeOnEdge `--dense-weights anon`, worth 3.2× on a far-past-RAM model;
  `ahwb` = AHardwareBuffer pins it outside the reclaimable cache).
* **Background cores**: an XDA test saw a Samsung phone restrict a backgrounded app to cores 0–2 (generation fell
  from 11.39 to 0.1 tok/s) ([XDA](https://www.xda-developers.com/tried-old-samsung-galaxy-local-llm-hosting-machine-worked-better-expected/)).
  ONYX runs long tasks under a foreground service — confirm on OxygenOS that the inference threads keep the
  A78 cores (`/proc/self/status Cpus_allowed_list`) when the screen is off.

### B.3 Backends on *this* phone — verdict
| Backend | Verdict for CPH2569 | Evidence |
|---|---|---|
| CPU NEON+dotprod, 4 threads pinned to cores 4–7 | **Use.** ONYX `ThreadPolicy` already pins to the 4 A78 cores. | vovaxa44: `-t 4` best (7.47 tok/s) vs `-t 6` 5.65, `-t 8` 4.47 |
| OpenCL Adreno 642L | **Don't.** Slower at every layer count; FA fails; driver patches needed. | vovaxa44; upstream fixed A6x q6_K kernels only recently ([PR #26476](https://github.com/ggml-org/llama.cpp/pull/26476)) |
| Vulkan | **Don't** (gibberish reports on Android, no evidence of wins on 6xx) | [issue #16881](https://github.com/ggml-org/llama.cpp/issues/16881) |
| Hexagon HTP v68 | **Don't** (not built by vendored CMake; maintainers expect poor perf; even v73 w/o FP16 lost to CPU) | §0 |
| KleidiAI | Keep **off** (A78 has no i8mm; ONYX P0 garbage-output history) | Geekbench flags; PHASE4 |

### B.4 Quantisation & kernels
* Decode tok/s ≈ effective bandwidth ÷ bytes read per token. With ~12–16 GB/s effective **(est. from the 778G
  numbers)**: 1.1 GB model → ~10–14 tok/s ceiling, 2.5 GB → ~5–6, 4.7 GB → ~3.
* **Q4_0** (pure) gets the dotprod `4x4` repack (faster prompt processing) and is the format Google ships QAT
  checkpoints for (Gemma 4 QAT Q4_0 GGUFs, [blog](https://blog.google/innovation-and-ai/technology/developers-tools/quantization-aware-training-gemma-4/)).
  **Q4_K** also has an `8x4` dotprod repack in the vendored tree, so Q4_K_M is not penalised; **IQ4_NL** repacks too,
  **IQ4_XS does not** (no entry in `repack.cpp`) → prefer Q4_0/Q4_K_M/IQ4_NL over IQ4_XS on this CPU.
  A Kirin 980 study found Q4_0 vs Q4_K_M quality close enough to use either ([discussion #30549](https://github.com/ggml-org/llama.cpp/discussions/30549)) — measure KL on our prompts before switching.
* **KV cache**: ONYX already does Auto f16/q8_0 with flash-attention (required for quantised V). q8_0 halves KV vs
  f16 with small loss; q4_0 KV is a further halving but riskier for long JSON — keep q8_0 as the floor on 8 GB.
* Prompt-processing is compute-bound and **drops ~29 % when the SoC is hot (45–47 °C)** while decode drops only
  ~2 % (vovaxa44) → keep prompts short and cached (ONYX's 321-token chat prompt + prefix reuse is the right shape;
  extend prefix caching to each agent role prompt).

### B.5 Speculative decoding (the real "free" speed-up in a bandwidth-bound regime)
Verifying k drafted tokens costs roughly one target forward (weights are read once), so acceptance converts directly
into tok/s.
1. **N-gram self-speculation (no extra RAM, no extra model)** — vendored llama.cpp has `NGRAM_MOD/CACHE/MAP_K…`.
   Agent outputs (JSON args, repeated tool names, copying from context) are highly repetitive. A Kirin 980
   (Cortex-A76) user got **1.34× reply speed** from `-t 4 -C 0xf0 --spec-type ngram-mod -ub 128` with identical
   outputs ([#30549](https://github.com/ggml-org/llama.cpp/discussions/30549)). ONYX's JNI only implements
   "draft model" speculation today → **port the common/speculative n-gram path into the JNI first.**
2. **Draft model** (already in JNI): Qwen3-0.6B for Qwen3-1.7B costs ~0.4 GB more RAM; worth measuring, likely
   smaller gain than n-gram on structured output.
3. **DFlash / DSpark** (block-diffusion drafters, DSpark adds a Markov head; merged upstream, [PR #25173](https://github.com/ggml-org/llama.cpp/pull/25173),
   [docs](https://github.com/ggml-org/llama.cpp/blob/master/docs/speculative.md)): drafts exist for Qwen3-4B (z-lab) and
   MiniCPM5-2B ([HF](https://huggingface.co/aj9o9/MiniCPM5-2B-DSpark-GGUF)). Promising for a 4B target, but the drafter
   needs `-fa on` and extra memory; benchmark after n-gram. EAGLE-3 needs a head per target; skip for now.

### B.6 Which models are realistic on 8 GB / SD782G

Measured anchors on comparable hardware:
* Qwen2.5-3B Q4_K_M, SD778G 8 GB: **7.45 tok/s** decode, 28.8 (hot) – 40.5 (cold) pp256 ([vovaxa44](https://huggingface.co/datasets/vovaxa44/snapdragon778g-llama-cpp-opencl-bench/blob/main/README.md)).
* Gemma-4B Q4_K_M, SD778G: 4.6 tok/s decode, 18 tok/s prefill ([#25255](https://github.com/ggml-org/llama.cpp/discussions/25255)).
* Dimensity 1200 (4× A78 2.6 GHz): Qwen3-1.7B Q4_K_M **6.6–6.8**, Qwen3-4B **3.2–3.3**, Qwen3-8B **2.4** tok/s ([ZSC-1 record](https://zsc.sh/record/the-ai-is-slow/)).

| Model (GGUF) | File | Fits resident? | Expected decode on CPH2569 | Verdict |
|---|---|---|---|---|
| Qwen3-1.7B Q4_K_M (current default) | 1.1 GB | yes | ~6–9 tok/s (anchors above) | Keep as the agent workhorse. |
| Qwen3-4B / Qwen3.5-4B Q4 | ~2.5 GB | yes | ~3–4.5 tok/s | "Quality mode" for Writer/Canvas only. |
| **LFM2.5-8B-A1B** (8.3 B total, ~1.5 B active MoE) Q4 | ~5 GB **(est.)** | tight (needs ~5 GB free) | **(est.) ~6–10 tok/s**; Liquid reports it faster than Qwen3-1.7B on a Galaxy S24 Ultra (~14.2 vs 9.8 tok/s) ([write-up](https://www.xugj520.cn/en/archives/lfm2-8b-a1b-sparse-moe-mobile.html), [LFM2 blog](https://www.liquid.ai/blog/lfm2-8b-a1b-an-efficient-on-device-mixture-of-experts)) | **Best "big model" candidate**: 3–4B-class quality at 1.5B-class bandwidth. Needs lfm2moe support (vendored tree is far newer than b6709 where it landed) and tool-call/GBNF testing. |
| Gemma 4 E2B (QAT Q4_0) | ~3.1 GB Q4_K_M file; per-layer-embedding table read sparsely via mmap | yes | (est.) ~5–8 tok/s; LiteRT-LM 47 tok/s is a flagship S26 number | Good second candidate; text-only in llama.cpp unless mmproj (BF16 mmproj ~0.9 GB). |
| Gemma 4 E4B Q4 | ~4.3 GB | tight | (est.) ~3–4 tok/s | Possible, not better than LFM2.5-8B-A1B for speed. |
| Dense 7–8B Q4 | 4.5–5 GB | borderline (eviction) | ~2–2.5 tok/s | Not worth it. |
| **Qwen3-30B-A3B Q4_K_M** (expert streaming) | 18.5 GB on flash | no (2.6× RAM) | **(est.) ≤1–1.5 tok/s**: BigMoeOnEdge got 1.7 tok/s with *no* expert cache on UFS **4.x** + 12 GB (1 051 MiB read/token); this phone has UFS 3.1 and room for a much smaller cache | **Demo / overnight background only — hype for interactive agents.** 18.5 GB of storage too. |
| 100B+ MoE (gpt-oss-120b, DeepSeek V4 Flash) | 60–91 GB | no | 12 GB UFS4 phone: 1–2.2 tok/s best case | Hype on this phone. |

**Biggest practical model:** ~8 B *total* parameters **only if MoE with ~1–1.5 B active** (LFM2.5-8B-A1B class); for
dense models ~4 B. "30B on a phone" is real on 12 GB + UFS 4 flagships at ~5 tok/s with expert streaming, not on an
8 GB UFS 3.1 mid-ranger at agent-usable speed.

### B.7 Recommended Phase 6B design — "Fit & Fly" runtime work (priority order)
1. **Device benchmark harness first (P0, 2 days).** In-app `llama-bench`-like screen: pp256/tg64 at t=2/3/4, KV f16/q8_0,
   cold vs hot (log `thermal_zone*` + `scaling_cur_freq` of cpu4–7), RSS, `RLIMIT_MEMLOCK`, `Cpus_allowed_list` in
   foreground/background, flash sequential + 4 KiB random read MB/s. Export CSV (match the vovaxa44 protocol so
   numbers are comparable). Everything else is tuned from this.
2. **N-gram self-speculation in the JNI (P0, 3–4 days).** Reuse `common/speculative` n-gram state; enable for
   Planner/Executor arg-filling (JSON) and Writer; auto-disable when acceptance < threshold. Expected 1.2–1.4× on
   structured output **(est., anchored on #30549)**.
3. **Prefix-KV per agent role + smaller prompts (P1, 2 days).** Each role's system prompt cached once (Opposable kept
   its 900-token prefix); keep the screen snapshot in "skeleton + diff" form (Part A) — prompt cost is what heat hurts.
4. **MoE-aware model tier (P1, 3–4 days).** Add LFM2.5-8B-A1B (and Gemma 4 E2B QAT Q4_0) to the catalogue as
   "High-8GB quality" options; `RuntimeSelector` checks *free* RAM (`MemoryInfo.availMem`) not total before offering
   them; run the Phase 5b e2e harness on them (tool-call validity, GBNF speed).
5. **Memory placement controls (P1, 2–3 days).** Expose load mode (mmap / direct-IO / no-mmap) in Advanced settings;
   detect page-fault storms (`/proc/self/stat` majflt per token) and warn "model too big for free RAM — switch".
6. **Thermal-aware scheduling (P2, 1–2 days).** Already pausing at SEVERE; add: drop to 3 threads at MODERATE, defer
   long prefill (RAG re-embed, report generation) to charging/cool periods via WorkManager constraints.
7. **DSpark/DFlash drafter for a 4B target (P2, 3 days)** once 1–6 are measured.
8. **Expert streaming (P3, research spike 1 week).** Prototype BigMoeOnEdge-style streaming via llama.cpp eval
   callback for LFM2.5-24B-A2B / Qwen3-30B-A3B as an opt-in "overnight big brain" for background tasks only. Expect ≤1–1.5 tok/s (est.).
9. **Not recommended on this phone:** OpenCL/Vulkan offload, Hexagon backend, KleidiAI, IQ4_XS, mlock.

### B.8 Risks
* All Part B speed figures are from *similar* SoCs, not CPH2569; memory type (LPDDR5 vs LPDDR4X) alone could move
  decode ±50 %. Step 1 removes this risk.
* LFM2.5-8B-A1B is "reasoning-only" (always thinks) per Liquid's blog — extra tokens may eat the speed win for agents;
  must test with thinking budget / no-think templates.
* 5 GB resident models on an 8 GB OxygenOS phone may be evicted when the user switches apps (RAM-Vita, other apps).
* Expert streaming needs a forked eval hook for overlap and 18+ GB storage; quality-lossy knobs (`n-expert-used`)
  must stay off by default.

---

## C. What I would implement first (combined order)
1. **B7.1 device bench** (2 d) — unblocks every tuning decision and the Part A VLM decision.
2. **A5.1 app catalogue + intent tools** and **A5.2 share/process-text** (5–7 d) — immediate, Play-safe "universal
   connection", no model change, fits existing ToolSpec/riskOf/ApprovalSheet/audit.
3. **B7.2 n-gram speculation** (3–4 d) — biggest speed win per effort for agent JSON.
4. **A5.3 notification actions/reply** (2–3 d).
5. **A5.4 screen tools v2** (7–10 d) — refs/skeleton/diff/assert/subgoal verification inside the Phase 5 DAG.
6. **B7.4 LFM2.5-8B-A1B / Gemma 4 E2B tier** (3–4 d) after bench numbers.
7. VLM fallback, MCP surface, DSpark, expert streaming later.

Total for 1–6: roughly **4–5 developer-weeks**, plus on-device test time.

## D. Open questions / not established
* Exact RAM type and real bandwidth of CPH2569; OxygenOS background CPU affinity behaviour.
* Whether CPH2569 receives Android 16 (affects AppFunctions *provider* work only).
* GGUF availability/quality of Ferret-UI Lite and ShowUI on llama.cpp (not verified); MAI-UI repo licence.
* "MiniCPM-V 4.7 MoE" — not found; may not exist under that name.
* PowerInfer-2 phone runtime availability (not found open-sourced).
