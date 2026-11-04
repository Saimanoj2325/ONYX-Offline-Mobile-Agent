# Phase 6 — device-adaptive runtime (M1) and long memory (M2)

Status: **built and unit-tested in the sandbox; not yet run on a phone** (target: OnePlus CPH2569, Snapdragon 782G, 12 GB RAM).
Version: `0.6.0-phase6` (versionCode 6).

| Milestone | Commit | What |
|---|---|---|
| M1 | `fdc1cef` | Device tiers, new catalogue entries, n-gram speculation, power governor, on-phone benchmark, memory engine (pure Kotlin) |
| M2 | `462d8c2` and earlier | Long memory wired into the app: saved memories, chat-history reference, threads, routines, frequent contacts, temporary chat |
| AppBridge | `462d8c2` | App intents, notifications, quick ask, the shared AppSafetyPolicy ([PHASE6-APPS.md](PHASE6-APPS.md)) |
| Screen tools v2 | this commit | `:core:screen`: 10 `screen_*` tools in the task graph, scoped approvals, shared blocklist — sideload only ([PHASE6-SCREEN.md](PHASE6-SCREEN.md)) |

---

AppBridge (app intents, notifications, quick ask): see [PHASE6-APPS.md](PHASE6-APPS.md).

Screen tools v2 (sideload only): see [PHASE6-SCREEN.md](PHASE6-SCREEN.md).

## M1 — device-adaptive model runtime

### 1. Tiers (`:core:agents` `RuntimeSelector`)

The tier comes from **total** RAM (what class of phone it is); the model picks are then filtered by **free** RAM right now
(`ModelFit`, `ActivityManager.MemoryInfo.availMem`), so a 12 GB phone with 3 GB free gets the same safe default as an 8 GB phone.

| Tier | Total RAM | Context | Candidates (first that fits = primary) |
|---|---|---|---|
| ENTRY | < 4.8 GB or `isLowRamDevice` | 2K | Qwen3 0.6B Q8, Qwen3.5 0.8B, LFM2.5 1.2B, Gemma 3 1B |
| MID | 4.8–6.8 GB | 4K | Qwen3 1.7B, LFM2.5 1.2B, Qwen3.5 2B |
| HIGH | 6.8–10.5 GB | 8K | Qwen3 1.7B, Qwen3.5 2B, Qwen3 4B Instruct 2507, Gemma 4 E2B |
| FLAGSHIP | ≥ 10.5 GB | 8K | Qwen3 1.7B, Qwen3 4B Instruct 2507, LFM2.5 8B-A1B (MoE), Qwen3.5 4B, Gemma 4 E2B |

- Default on every tier ≥ 6 GB: **Qwen3 1.7B Q4_K_M** (~2.2 GB resident at 8K).
- Free RAM < 2 GB caps context at 4K; < 1.2 GB forces 2K and the smallest model.
- No dot-product CPU feature → small models only. Bigger models are hidden (with a note) until enough RAM is free; a
  hand-picked model gets a warn-before-load message.
- Downloads are blocked if they would leave < 1.5 GB of storage; partial downloads resume.

### 2. Catalogue entries added in `fdc1cef` (`core/models/.../Catalog.kt`)

Exact byte sizes from the Hugging Face tree API (verified 2026-11-03):

| Id | Model | File | Bytes | Notes |
|---|---|---|---|---|
| `qwen3.5-0.8b` | Qwen3.5 0.8B | `unsloth/Qwen3.5-0.8B-GGUF` · `Qwen3.5-0.8B-Q4_0.gguf` | 507 154 688 | hybrid, TINY |
| `lfm2.5-1.2b` | LFM2.5 1.2B Instruct | `LiquidAI/LFM2.5-1.2B-Instruct-GGUF` · `LFM2.5-1.2B-Instruct-Q4_0.gguf` | 695 751 488 | hybrid, TINY |
| `qwen3.5-2b` | Qwen3.5 2B | `unsloth/Qwen3.5-2B-GGUF` · `Qwen3.5-2B-Q4_0.gguf` | 1 214 873 856 | hybrid, SMALL |
| `qwen3-4b-2507` | Qwen3 4B Instruct 2507 | `unsloth/Qwen3-4B-Instruct-2507-GGUF` · `Qwen3-4B-Instruct-2507-Q4_0.gguf` | 2 375 773 280 | MEDIUM |
| `qwen3.5-4b` | Qwen3.5 4B | `unsloth/Qwen3.5-4B-GGUF` · `Qwen3.5-4B-Q4_0.gguf` | 2 583 221 408 | hybrid, MEDIUM |
| `gemma4-e2b` | Gemma 4 E2B | `ggml-org/gemma-4-E2B-it-GGUF` · `gemma-4-E2B-it-Q4_0.gguf` | 2 841 481 184 | MEDIUM |
| `lfm2.5-8b-a1b` | LFM2.5 8B-A1B (MoE) | `LiquidAI/LFM2.5-8B-A1B-GGUF` · `LFM2.5-8B-A1B-Q4_0.gguf` | 4 844 678 368 | MoE + hybrid, LARGE |

Existing entries whose sizes were made exact in the same commit: Qwen3 1.7B Q4_K_M 1 107 409 472 B, Qwen3 0.6B Q8_0 639 446 688 B.
Every chat entry now also carries `kvBytesPerToken`, `moe`, `hybrid` and `sizeClass`.

### 3. N-gram self-speculation (JNI, `llama_jni.cpp`)

Prompt-lookup drafting from the context itself (no draft model): output is identical in design, works with GBNF grammars,
adaptive draft length, switches itself off on low acceptance, and is disabled for recurrent/hybrid models (KV rollback impossible).

Measured on the **2-vCPU sandbox host** (not a phone), host harness `Phase6Harness`:

| Model | Plain decode | Speed-up with speculation |
|---|---|---|
| Qwen3 0.6B Q8_0 | 39.8 tok/s | 0.94–2.68× |
| Qwen3 1.7B Q8_0 | 11.4 tok/s | 1.02–4.40× |

The gain depends on how much of the answer repeats the prompt (code edits and rewrites gain most; free-form chat little).
Phone numbers will differ; use the on-phone benchmark.

Also in M1: per-role static system prompts (planner/args) so their KV prefix is cached; prefix-cache files 8 → 16.

### 4. Power governor (`PowerGovernor`)

| Condition | Threads | Batch | Other |
|---|---|---|---|
| Normal | big cores (1–8) | 256 (128 on ENTRY) | speculation on |
| Thermal LIGHT | −1 | — | |
| Thermal MODERATE | ÷2 | ÷2 (≥ 64) | busy-poll off, speculation off |
| Thermal SEVERE+ | 1 | ≤ 64 | new model work paused |
| Battery saver | ≤ 2 | ≤ 128 | poll off, idle unload 2 min |
| Battery ≤ 15 % and not charging | ≤ 2 | ≤ 128 | poll off, idle unload 2 min |

Idle unload defaults to 10 minutes (configurable); `onTrimMemory` release is kept. Threads/poll can change live.

### 5. On-phone benchmark (Agents → Models → Benchmark)

Reloads the active model (load time), then measures prompt tok/s, generation tok/s with speculation off and on
(accepted/drafted), peak RSS, thermal state and battery. Results are stored in `onyx-agents.db` (`bench_results`, schema v2
via AutoMigration). Nothing leaves the phone.

---

## M2 — long memory

Everything is on-device in `onyx-agents.db`. Engine: `:core:agents` `memory/LongMemory.kt`, `memory/RoutineBook.kt`
(pure Kotlin, JVM-tested). App wiring: `app/onyx/OnyxMemory.kt`, `ChatViewModel`, `ChatScreen`, `ui/onyx/MemoryScreen.kt`.

### 1. Features

- **Saved memories.** Rule-based extraction turns first-person statements into third-person facts ("I live in Hyderabad" →
  "Lives in Hyderabad"). Questions, hypotheticals and other people's statements are skipped. A small model pass runs only
  when the rules find nothing, at BACKGROUND priority, and is skipped on battery < 30 % (not charging). Auto-saved items are
  marked *inferred* with a confidence until the user confirms them. Single-value slots (name, home, employer, role, diet,
  birthday) replace the old value; near-duplicates merge.
- **Explicit commands.** "remember that …" saves (works even when auto-save is off); "forget …" deletes matching memories
  and matching chat-history snippets. "remember to …" is treated as a reminder, not a memory.
- **"Memory updated" chip** under the user message that changed memory.
- **Reference chat history.** Every finished exchange of every normal chat is indexed; BM25 (k1 1.2, b 0.75) with a recency
  boost retrieves snippets from *other* chats for new chats and agent runs. BM25 was chosen over embeddings: no second model
  in RAM, < 10 ms for thousands of chunks, good with names and numbers, explainable.
- **Rolling summaries.** When older turns fall out of the context window they are folded into the chat's running summary
  (model at BACKGROUND priority, extractive fallback). The summary is indexed for cross-chat recall. **Each chat has one
  summary in the index: a new summary replaces the old one** (it already contains it).
- **Threads (Memory screen).** Lists the current summary of each chat (title + preview). *View* opens the full summary;
  *Delete* forgets the thread's summary and its indexed snippets. The chat itself stays.
- **Chat deletion.** Deleting a chat also deletes its indexed history, its summary and its chat-memory state.
- **Temporary chat.** Toggle in Chat (before or during a chat). A temporary chat neither reads nor writes saved memories,
  chat history, summaries index, routines or contacts.
- **Frequent contacts.** Learned only from approved call/message tool runs; ranked by frecency (14-day half-life); used to
  resolve "message Anu". Listed on the Memory screen with delete.
- **Routines.** A multi-step task that succeeded twice becomes a routine; the Planner reuses it without a planning call.
  Enable/disable, rename and delete on the Memory screen.
- **Controls.** Settings: *Use memory* (master), *Saved memories*, *Reference chat history* (each on its own). Memory screen:
  view/add/edit/pin/confirm/delete memories, export JSON, clear chat-history index, delete all memories, wipe ALL memory.

### 2. Token budget

Memory is injected as one block, built by `MemoryContext.build`, within a fixed token budget (≈ 4 characters per token):

| Caller | Budget |
|---|---|
| Chat, context ≤ 4096 | 200 tokens |
| Chat, context > 4096 | 320 tokens |
| `OnyxMemory.contextFor` default | 280 tokens |
| Agents (`profile`) | budget passed by the Planner; saved memories only |

Order inside the budget: up to 6 relevant saved memories first (pinned, confidence and recency boosts; inferred ones
labelled "(inferred)"), then up to 3 chat-history snippets from other chats (each ≤ 360 chars, only if ≥ 24 tokens remain).
Agent memory search returns up to 2 chat snippets alongside saved memories and documents. Temporary chats get an empty block.

### 3. Tests

`Phase6MemoryTest` (`:core:agents`) covers extraction, remember/forget, slots, BM25 history, temporary scope, contacts,
routines, the budgeted context block, plus M2: summary replacement, chat/thread deletion, temporary summaries not indexed.

### 4. Known gaps

See `docs/KNOWN_ISSUES.md` → Phase 6.

---

## Phase 6 complete

Phase 6 is done in the sandbox: device-adaptive runtime (M1), long memory (M2), AppBridge, and screen tools v2
(sideload only; Play builds use `-PscreenAgent=false`). Both debug APKs (`offline`, `connected`) compile and package.
Nothing in Phase 6 has run on a phone yet.

JVM unit tests, all modules run with `--rerun-tasks` (2026-11-04): **366/366 pass** —
screen 38 · apps 35 · agents 83 · runtime 112 · inference 45 · openui 29 · models 20 · mcp-http 4.

Open gaps: `docs/KNOWN_ISSUES.md` → Phase 6, Phase 6 AppBridge, Phase 6 screen tools v2.

---

## 6.1 — screen agent routing, status banner, Settings allowlist, macro launcher fix (0.6.1-phase6, versionCode 7)

Two bugs from testing 0.6.0 on a OnePlus Nord CE 3 (OxygenOS, Android 14/15).

### Bug 1: screen control only opened apps

Accessibility service "Offline Agent screen automation" on, Screen agent on in Agents; "open WhatsApp and type hi to
Mom" only opened WhatsApp.

**Root causes (several, all on the path):**
1. **Kill switch.** `ScreenTools.kill` treats a missing `SCREEN_AUTOMATION` phone group as "stopped by you", and that group
   is *not* in `DEFAULT_PHONE_GROUPS`. With only the Agents toggle on, every `screen_*` call returned `STOPPED`. The
   AppBridge `app_open` intent was the only tool that could succeed.
2. **Routing / planner.** The ONYX planner sees ≤10 tools picked by word overlap and plans ≤4 one-shot steps. For "open X
   and …" Qwen3 1.7B picks `app_open` (exact "open" match). No tool could run a multi-step loop inside the app:
   `screen_do` is one step, and the legacy `run_phone_task` loop is hidden while v2 is on.
3. **Verification.** The Writer said "Done", and no rule checked that an in-app step ran.
4. **Chat.** Chat's Agent / Phone / Automation modes used the legacy chat agent (intent `open_app`/`app_open` only). Only
   Chat-mode "Agents: Auto" reached the ONYX orchestrator.

Checked and **not** the cause: the prefs key (Agents card and `ScreenTools` share one `ScreenPrefs("onyx_screen","enabled")`),
and the manifest (it declares `.automation.OfflineAccessibilityService`, the class whose `instance` the port uses).

**Fixes**
- `ScreenTools.setEnabled(true)` (the Agents switch) also allows the Screen-automation group. Turning the group or Phone
  automation off in Settings stays the kill switch, and `KillSwitch.reason()` now says which switch is off.
- New `screen_task(goal, app, max_steps)` (`ScreenSession.task`): opens the app if needed, then loops snapshot → one
  model-chosen action (grounded early exit, same overlay/safety/approval path as `screen_act`) → re-read. It stops before
  Send/Submit/Enter unless the goal asks to send ("type hi to Mom" stops before Send; "send hi to Mom" asks on the overlay).
  It also stops on a 3× repeated step (STUCK). Its subgoal passes only after a real in-app action: opening alone is never
  success.
- `:core:agents` `InAppRequest` + `Triage`: in-app requests take the new `Route.SCREEN` →
  `screen_open_app(app)` → `screen_task(goal, app)`, with no planner call. With v2 off and Screen automation allowed, the
  route is the legacy `run_phone_task`. A model plan for an in-app request that only opens the app is repaired with the
  screen steps.
- `InAppCheck` (Verifier) + Writer: when no in-app step ran, the Writer is told so up front, and on its last try an explicit
  "I did not … inside <app>" line is added.
- Chat: `ChatRouting.screenTask` sends in-app turns to ONYX in Chat/Agent/Phone/Automation modes when the Screen agent is on.
- Status banner (`ScreenAgentStatus` + `ScreenAgentStatusBanner`, in Chat and on the Agents card). It covers: agent on but
  service off, service enabled but not connected (OxygenOS killed it), kill switch off, group off, or service on but agent
  off. Each has buttons: Open Accessibility settings, Battery → Unrestricted, Turn automation back on, Allow screen
  automation, Turn on Screen agent.

**Settings allowlist** (MIKA-COMPARISON §6), via `AppSafetyPolicy.isPageGatedSettings` / `checkSettingsPage` →
`SettingsPages` and `DefaultScreenSafetyPolicy.screenVerdict` / `confirmReason`:

| Access | Pages |
|---|---|
| allow | Wi-Fi (saved networks; a Wi-Fi password field is a take-over), Bluetooth (paired), Display, Sound & vibration, Do Not Disturb, Battery saver |
| ask every step (CRITICAL, never scoped) | Location, Airplane mode, Mobile data, Hotspot (+ Airplane/Mobile data/Hotspot/Location/Forget/Unpair controls on allowed pages) |
| block | everything else, incl. the Settings home and search, accessibility, developer options, accounts, security |

`screen_open_app("Settings > Display")` (or "Settings > turn on dark mode") opens the page's entry intent. A plain
"Settings" is refused. AppBridge (`app_open`, intents) still never opens Settings.

### Bug 2: recorded macro stopped at step 1

"New macro, 11 steps in com.android.launcher" → "Macro stopped at step 1: step 1 failed"; the log showed
"app · Open com.android.launcher → failed".

**Root cause.** `startRecording` presses Home first, so the first window event came from the launcher, and `MacroRecorder`
made "open com.android.launcher" the first step and `startPackage`. The launcher has no launch intent
(`getLaunchIntentForPackage` returns null; its label also matches nothing), so `openApp` failed, and the player only said
"step 1 failed". The icon tap that followed was recorded as a TAP in the launcher instead of "open WhatsApp".

**Fixes** (`Macros.kt`, `LauncherInfo.kt`):
- The home app comes from `PackageManager.resolveActivity(ACTION_MAIN + CATEGORY_HOME)` plus home activities with
  priority ≥ 0, excluding Settings' FallbackHome and "android". A new `<queries>` entry makes it visible on Android 11+.
- Recorder: launcher, systemui and ONYX windows never start a macro. A home-screen icon tap becomes `OPEN_APP <app>`
  (label → launch intent). Going home mid-demo becomes `HOME`, and a trailing HOME is dropped.
- Runner: an `OPEN_APP` of the launcher replays as Home. Before launching, it checks for "no launch screen", "not
  installed / hidden by package visibility" or "banking app, blocked", and gives that reason. A refused open, tap or type
  now gets a readable message.
- `MacroStore` migrates saved macros on load and saves them back: leading launcher opens are dropped, later ones become
  HOME, and launcher icon taps become `OPEN_APP`.

### Tests (JVM, fake phone + scripted model)
- `:core:agents` `ScreenRoutingTest` (7): parsing, Triage → SCREEN route, legacy fallback, Chat routing, the
  orchestrator runs `screen_open_app` → `screen_task` and never `app_open`, an `app_open`-only run is reported as not
  done, and plan repair.
- `:core:screen` `ScreenTaskTest` (11): types and stops before Send, sends only when asked (with confirmation), opens
  first and "opening alone" fails, service-not-connected reported before opening, stuck loop and kill switch, Settings page classification, Settings home blocked and
  Display allowed, ask pages confirm every step, status banner states, and end to end "open ChatApp and type hi…" and
  "open Settings > Display and turn on dark mode".
- `:core:runtime` `MacroLauncherTest` (5): home-screen start, Home mid-demo, unknown icon, migration of a saved launcher
  macro, and runner Home and readable failures.

JVM unit tests, all modules with `--rerun-tasks` (2026-11-04): **389/389 pass** — screen 49 · apps 35 · agents 90 ·
runtime 117 · inference 45 · openui 29 · models 20 · mcp-http 4. Both debug APKs compile and package. Nothing in 6.1 has
run on a phone yet.

### On the phone (OnePlus Nord CE 3), after installing 0.6.1
1. Install over 0.6.0. Android may switch the accessibility service off after an update: if the banner says so, tap
   **Open Accessibility settings** and turn "Offline Agent screen automation" back on.
2. OxygenOS kills background accessibility services: Settings → Apps → ONYX → Battery → **Unrestricted** (the banner's
   Battery button opens that page).
3. Agents → Screen agent: switch it off and on once. That also allows Settings → Phone actions → Screen automation (the
   banner offers it too).
4. Try "open WhatsApp and type hi to Mom" (it should stop before Send) and "open Settings > Display and turn on dark mode".
5. The old launcher macro is fixed on first load. If it still starts wrong, record it again from the home screen.

