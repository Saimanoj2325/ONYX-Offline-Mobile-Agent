# Mika vs ONYX — background screen, handoff, screen reading, Settings policy

Research only. No ONYX source code changed. Written 2026-11-04 against:

- **Mika** — https://github.com/wangzan101/Mika, Apache-2.0, Kotlin, shallow clone of `main` at
  `6b903941fca6ac5fe11b4deb40a1d5f72e219ea4` (commit date 2026-11-04). The clone stayed in `/workspace/work/mika` and is not committed.
- **ONYX** — `master` at `eb73f5c` ("Phase 6: screen tools v2 (sideload only)"): `docs/PHASE6-SCREEN.md`,
  `docs/PHASE6-APPS.md`, `core/screen`, `core/apps`, `app/src/main/kotlin/com/offlineagent/app/onyx/screen/ScreenTools.kt`.

Mika path shorthand (this is Mika's own convention): `app/…` = `app/src/main/java/com/wangzan101/mika/`,
`core/…` = `core/src/main/kotlin/com/wangzan101/mika/core/`. Most of Mika's code comments and its main design doc
(`docs/dev/architecture.md`, ~258 KB) are in Chinese. The user docs have English versions (`docs/*.en.md`).

**Biggest difference.** Mika is a cloud-model agent (OpenAI, Anthropic, Gemini or ChatGPT sign-in) that runs apps on a
**hidden virtual display** owned by a **shell-uid helper**, which it starts over the phone's own Wireless debugging.
ONYX is offline-first, runs a small on-device LLM, and drives the **user's main screen** through an
AccessibilityService. Mika's guardrails are thin (only money is gated; it ships an arbitrary shell tool). ONYX's are
much stricter. So the plan is to borrow Mika's **mechanics** and keep ONYX's **policy**.

---

## 1. Comparison table

| Area | Mika | ONYX (eb73f5c) |
|---|---|---|
| Where it acts | Its own `VirtualDisplay` ("background screen"); the user keeps using the phone | The user's main screen (the overlay shows a preview and Allow/Deny) |
| Privilege | Shell uid 2000 helper (`app_process`) started over Wireless debugging (own ADB client) or USB `adb shell`; plus AccessibilityService | AccessibilityService only |
| Input | Accessibility gesture with `GestureDescription.Builder.setDisplayId()` first; shell `InputManager.injectInputEvent` (with `setDisplayId`) for paced swipes and as fallback; `input -d` as last resort | Accessibility node actions by path, then gesture tap fallback (main display) |
| Launch apps | `IActivityTaskManager.startActivityAsUser` with `ActivityOptions.setLaunchDisplayId`; fallback `am start --display` | Launcher / VIEW intents on the main screen |
| Screen reading | Accessibility tree per display (`windowsOnAllDisplays`) + **bundled ML Kit OCR** (Latin + Chinese) when the tree is poor (`TreeQuality`) + optional vision model | Accessibility tree; skeleton/regions, `@eN`/`@rN` refs; **no OCR, no vision fallback** (known gap) |
| Refs | Stable across reads (`RefStabilizer`): unchanged elements keep their ID; stale IDs are relocated or refused | Bound to one snapshot; a structural change gives `STALE`, so the model has to re-read |
| Multi-step | `do_steps` (1–20 actions, each with `expect`/`expect_gone`/`wait_stable` checked by code), `phone_run`, `phone_collect` | Planner DAG + per-step Verifier; `screen_do`, `screen_transfer`; idempotent retry |
| Long lists | `phone_collect`: scroll + read + merge overlapping lines, stops when no new text appears twice | None (manual scroll + snapshot) |
| Handoff | `ScreenDiagnosis` (captcha / payment / SMS-code words, password field, 2 black frames = protected) → `hand_to_user` → the app is **moved to the main screen** with `moveTask`, then moved back and re-verified | `FLAG_SECURE` probe (`takeScreenshot` error 5) + CAPTCHA words → overlay "take over"; password/OTP fields never typed |
| Money gate | Ask mode (default): only the **payment** step asks; Autonomous mode asks nothing. Unattended runs skip payments | CRITICAL steps (send/pay/delete/…) **always** ask, per step, never scoped. Banking/UPI apps are blocked outright |
| Settings | No page restriction. The agent may change any setting (the `android-settings` knowledge file warns about it). Optional per-app denylist ("App access") | `com.android.settings` blocked for SCREEN (`system_admin`), plus `SETTINGS_FORBIDDEN` words |
| Shell tool for the model | `phone_system_command` (any shell command as uid 2000, 10 s) | None |
| Loop guards | `StopRules` + `OperationGuards` (same action repeated, same state revisited, no progress, re-read loops) + `StuckAdvice` | Verifier retry/skip; idempotent retries |
| Human-like pacing | Gesture/typing/dwell pacing, per-site page caps (anti-bot) | None |
| Eval | `eval/tasks/` 128 JSON tasks (read/write/irreversible/impossible/barrier/web/skills/memory), tune/holdout split, `answer_grounded` checks, on-device instrumentation runner | JVM tests (`ScreenToolsTest`, `CrossAppWorkflowTest`); not yet run on a phone |
| Self-check | 12-step on-device compatibility check (~1 min, 90 s cap), report with sensitive data removed | None |
| Extras | Built-in WebView browser on a private virtual display driven over CDP; PRoot + Alpine Linux sandbox (Python/Node); MCP/CLI from a PC | AppBridge (intents/share/notifications), MCP, offline LLM/VLM |
| Android floor | App minSdk 30; phone control needs Android 12+; Android 14+ recommended (own focus) | — |
| Play | Not on Play (GitHub APK) | Screen agent ships sideload-only (`-PscreenAgent=false` for Play) |

---

## 2. How Mika's hidden background screen works

### 2.1 The shell-uid helper (no root)

- **Own ADB client**, pure Kotlin, in `core/adb/` (~1,300 lines): `AdbPairing.kt` (TLS 1.3 → export keying material
  `"adb-label\0"` → SPAKE2 (`Spake2.kt`, `PairingAuth.kt`, `Ed25519.kt`) → AES-GCM PeerInfo exchange that sends Mika's
  ADB public key), `AdbIdentity.kt` (RSA-2048 key + self-signed cert, PKCS#8 wrapped with an Android Keystore AES key),
  `AdbTls.kt` (exporter = `android.net.ssl.SSLSockets.exportKeyingMaterial`), `AdbConnection.kt` / `AdbMessage.kt`
  (ADB wire protocol over TLS), `AdbConnectPort.kt` + `LocalPortScan.kt` + `app/cap/phone/shell/adb/NsdAdbServiceFinder.kt`
  (find the port through mDNS `_adb-tls-pairing._tcp` / `_adb-tls-connect._tcp`, the last port used, or a local
  port scan, loopback only).
- **Starting it**: through adbd's `exec:` service, as shell:
  `CLASSPATH=<base.apk> app_process /system/bin --nice-name=<pkg>:helper com.wangzan101.mika.shell.HelperMain <pkg> <uid> <cert-sha256>`,
  with a one-time nonce on stdin (`app/cap/phone/shell/HelperProcess.kt`, `core/shell/HelperHandoff`). The helper hands
  its Binder (`IMikaShell`) to Mika through a provider (`HelperHub.kt`). `ShellService` accepts calls **only from Mika's
  uid**, and the handoff checks the APK signing-cert fingerprint and the nonce.
- **USB alternative**: `adb shell sh /sdcard/Android/data/com.wangzan101.mika/files/usb-start.sh` (written by
  `app/cap/phone/setup/UsbStartScript.kt`). It has to be run again after every reboot.
- **What the helper does for itself**: `pm grant <pkg> WRITE_SECURE_SETTINGS`, turns on Mika's own accessibility by
  writing `enabled_accessibility_services` (this gets around Android 13+'s "Restricted setting" for sideloaded APKs),
  `cmd deviceidle whitelist +<pkg>` (battery), turns Wireless debugging back on and restores the helper after a reboot
  (`core/shell/RecoveryPermissions.kt`, `HelperRestore.kt`, `app/cap/phone/setup/BuiltinShellRecovery.kt`,
  `BootRestoreHandler.kt`). If Mika is killed, the helper notices through the binder's death notice, waits 8 s, then
  re-enables a11y and restarts Mika's process.

### 2.2 Pairing flow and permissions the user grants (`docs/setup-guide.en.md`, `app/cap/phone/setup/*`)

1. Developer options (tap Build number ×7). 2. USB debugging on (must stay on). 3. Accessibility: the suggestion is
**skip**, because Mika turns it on itself after pairing. 4. Wireless debugging on, ticking "Always allow on this
network" (Wi-Fi needs to be connected; internet is not needed). 5. "Pair device with pairing code": the
6-digit code is either **read from the Settings dialog by Mika's accessibility service** (`PairingCodeReader.kt`) or
typed into a **notification with RemoteInput** (`PairingNotifier.kt`), so the user never leaves the dialog (leaving
cancels the code). 6. Auto-connect. Manifest permissions include `WRITE_SECURE_SETTINGS` (granted by shell),
`POST_NOTIFICATIONS`, `FOREGROUND_SERVICE_SPECIAL_USE`, `RECEIVE_BOOT_COMPLETED`, `REQUEST_INSTALL_PACKAGES`,
`INTERNET`.

### 2.3 Creating the VirtualDisplay (`app/cap/phone/shell/ShellService.kt` `createDisplay`, `displayFlags`)

- Created **inside the shell process** with a `DisplayManager` built by reflection over a fake shell `Context`
  (`ShellContext.kt`, adapted from scrcpy). The output `Surface` is an `ImageReader` in Mika's process
  (`app/cap/phone/display/AgentDisplay.kt`, RGBA_8888, same size/dpi as the main screen).
- Flags: `PUBLIC | OWN_CONTENT_ONLY | SUPPORTS_TOUCH | ROTATES_WITH_CONTENT | DESTROY_CONTENT_ON_REMOVAL`; API 33+
  adds `TOUCH_FEEDBACK_DISABLED` and (if trusted) `TRUSTED | OWN_DISPLAY_GROUP | ALWAYS_UNLOCKED`; API 34+ trusted adds
  `OWN_FOCUS | STEAL_TOP_FOCUS_DISABLED` so the hidden screen never takes focus from the user's typing. If shell lacks
  `ADD_TRUSTED_DISPLAY` (`SecurityException`), it retries untrusted (and the self-check warns that focus is shared).
- Then: fullscreen windowing mode (some ROMs default new displays to freeform), `setDisplayImePolicy(LOCAL)` so the
  keyboard appears on the hidden screen and not the user's, and an orientation watcher (`DisplayOrientationWatch.kt`).
- **Keep-alive** (`DisplayAwake.kt`): an `OWN_DISPLAY_GROUP` display has its own sleep timer, and injected input does
  not count as user activity. So the helper calls `IPowerManager.userActivity(displayId, …)` every period. If a ROM
  lacks the per-display overload, it gives up quietly.

### 2.4 Launching and input on the hidden display

- **Launch**: `startActivityAsUser(…, ActivityOptions.makeBasic().setLaunchDisplayId(id), …)` called as the shell package,
  fallback `am start --display <id> -n <component>` (`ShellService.start/startView/startShare/startSystemAction`). It
  refuses if the app is currently in use **on the main screen** or on another task's display. It reuses a kept task
  (`resumeKept`).
- **Input**: accessibility gestures **can** target a virtual display. `GestureDescription.Builder.setDisplayId()`
  exists since API 30, and Mika uses it (`app/cap/phone/a11y/ScreenAccessibility.kt` `dispatch`). The shell path is
  `InputManager(Global).injectInputEvent(event, mode)` after `event.setDisplayId(id)` (reflection; `ShellService.inject`,
  `injectTap`, `injectStroke` with per-point timestamps for human-like swipes, `injectKey`, `injectCtrlKey` for
  Ctrl+A/Ctrl+V into custom-drawn fields), falling back to `input -d <id> tap x y`. Order in
  `app/cap/phone/a11y/AndroidScreen.kt` `touch()`: a11y gesture → paced shell stroke → shell tap.
- **Reading**: the a11y tree per display through `AccessibilityService.getWindowsOnAllDisplays()`. Screenshots come from the
  `ImageReader` (not `takeScreenshot`). A `FLAG_SECURE` window renders **black** into the reader, which is how
  "protected" is detected.
- **Escapes to the main screen**: before every action the helper watches the main screen's task stack for 6 s
  (`watchMainScreen`, `ITaskStackListener`). If the app jumped to the user's screen (a payment app or a chooser), it
  is detected and handled.
- **Live preview**: `AgentDisplay.frames` — frames throttled to ≤10 fps and scaled so the short edge is ≤360 px, shown in a draggable
  in-app picture-in-picture (`app/ui/AgentPip.kt`; green border = working, orange = needs you), and in the side menu's
  "Background screens" list. When parallel tasks run, there is one display per conversation (Android 14+, max 1–3).
- **End of task**: the display is released (`DESTROY_CONTENT_ON_REMOVAL`). Apps the model marked to keep are moved to the
  main stack behind the user (`AppsAfterTask.kt`, `finishDisplay`). Apps that are playing audio are closed.

### 2.5 Android versions

Install: Android 11+. Phone control: **Android 12+**. On 12–13 the main screen can briefly lose focus (no
`OWN_FOCUS`). **Android 14+ recommended**: own focus, and parallel tasks need it.

### 2.6 Could ONYX do this without root on the OnePlus Nord CE 3 (Android 14/15, OxygenOS)?

**Yes, technically, but only with a shell-uid helper. An app alone cannot do it.**

- An app's own `VirtualDisplay` is **private and untrusted**. The system lets other apps' activities onto an untrusted
  display only if they declare `allowEmbedded`, which normal apps don't. `PUBLIC`/`TRUSTED` displays need
  `CAPTURE_VIDEO_OUTPUT` / `ADD_TRUSTED_DISPLAY`, which are signature/system permissions that the **shell uid has** (on most
  builds). MediaProjection only mirrors the main screen. (Mika's own WebView browser uses an app-private display only
  because the content is its own.) This is from AOSP behaviour as I understand it. The 1-day spike below will confirm it.
- Android 14/15 meets Mika's recommended floor, so `OWN_FOCUS | STEAL_TOP_FOCUS_DISABLED` should be available, and
  OxygenOS ships standard Wireless debugging.
- **OxygenOS unknowns (must test on the device)**: whether shell gets `ADD_TRUSTED_DISPLAY` on this ROM (Mika falls back
  to untrusted). Whether OxygenOS needs an OEM developer toggle for shell input injection or `pm grant`. Xiaomi needs
  "USB debugging (Security settings)" per scrcpy's FAQ, and ColorOS-family ROMs are *reported* to have a "Disable
  permission monitoring" toggle; I did not verify this for OxygenOS 14/15. Also whether OxygenOS's background killer
  leaves the `app_process` helper alone. Mika has **no OEM-specific code** for OnePlus/OPPO, and its self-check exists
  for exactly this.
- **Route options for ONYX**: (a) borrow Mika's built-in ADB client plus helper (no second app, more code to own); or
  (b) use **Shizuku** (Apache-2.0; the user installs the Shizuku app and pairs it the same way; ONYX runs a Shizuku
  `UserService` as shell). Less code, but a second app. Recommend a **1-day spike first**: from a PC,
  `adb shell` → `app_process` a tiny helper that creates the display, launches Calculator on it, injects a tap and reads the
  a11y tree on the Nord CE 3. Only then commit to (a) or (b).
- Offline fit: Wireless debugging needs **Wi-Fi association, not internet**. That fits ONYX, but a phone on mobile data
  only cannot restart the helper after a reboot (a USB-start fallback is needed).

---

## 3. Handoff: logins, OTPs, passwords, payments, captchas

- **Detector**: `core/uistate/ScreenDiagnosis.kt` `classify(ScreenFacts)` → `ScreenState` NORMAL / BLANK / LOADING /
  PROTECTED / NEEDS_HUMAN, with a suggested `hand_to_user` reason:
  - CAPTCHA words (`captcha`, `verify you are human`, `not a robot`, Chinese slider phrases),
  - PAYMENT words (`payment password`, `confirm payment`, `pay now`, 支付密码…),
  - an editable **password field** → `login`,
  - SMS-code words (`get code`, `send code`, `verification code`) → `login`,
  - **protected** = a window exists and **two consecutive quiet frames are all black** (one black frame might just be
    loading; black while changing = LOADING). That is FLAG_SECURE seen through the ImageReader.
- **Model-side**: `handle_obstacle(kind=popup|login|captcha|access_blocked|payment)` returns canned guidance
  (`core/general/HandleObstacle.kt`). `end_not_done` subtypes include `login_incomplete` and `captcha_incomplete`.
  For the web, `WebBarrier` also recognises rate-limit and "unusual traffic" block pages → `access_blocked`. `page.js`
  never returns the values of password, card-number, CVC, expiry or OTP fields (only their length).
- **Takeover** (`app/takeover/Takeover.kt`, `TakeoverActivity.kt`, `HandoverActivityCounter.kt`): a high-priority
  notification "needs you". "I'll handle it" **moves the task from the hidden display to the main screen**
  (`ShellService.moveTask`). "Done" moves it back, and the agent **re-reads and verifies** before continuing. "Skip" never
  counts as success. Takeovers from several tasks are queued. The web version moves the same WebView into a full-screen activity.
- **Ask vs Autonomous** (`docs/modes.en.md`, `core/action/Approval.kt`): one global switch. **Ask (default) gates only
  the money-spending step**: `Risk.payment` = `IrreversibleDetector` (`core/gate/IrreversibleDetector.kt`, mostly
  Chinese word lists: 支付/付款/下单/购买/结算…, `submit`, plus "outward irreversible action while an amount with a
  currency sign is on screen"). Sending, posting, deleting and **changing settings are not asked**. **Autonomous** asks
  nothing. `ApprovalPolicy.decide`: payment + unattended or non-UI origin (schedule/MCP/CLI) → **Deny** (it is skipped and
  reported). The card shows where, what and how much, and the user still pays in person. Outward actions are spaced 8–30 s
  apart (`core/action/OutwardPacing.kt`).
- **vs ONYX**: ONYX is stricter on policy (CRITICAL every step, banking blocked, no shell tool) but weaker on detection.
  It has no password-field or SMS-code takeover trigger at the *screen* level (it only refuses to type into them), no
  payment-text trigger, and its FLAG_SECURE probe is a single `takeScreenshot` call on the main display, untested.

---

## 4. Screen reading

- **Tree first, OCR when unsure** (`core/uistate/TreeQuality.kt`): the tree is "clearly adequate" only if it has
  ≥6 named elements, most controls are named, and no text-free area covers ≥20 % of the screen. Otherwise OCR runs. If OCR adds
  ≥3 lines that are missing from the tree → `partial`. An empty tree → `unusable` (OCR always runs). Protected screens are never OCR'd.
- **OCR engine**: Google **ML Kit Text Recognition v2, bundled** (`com.google.mlkit:text-recognition` +
  `text-recognition-chinese` 16.0.1), both run in parallel on the same frame (`app/cap/phone/a11y/PhoneOcr.kt`, 79
  lines). Google documents about **4 MB per script per ABI** for bundled models
  (https://developers.google.com/ml-kit/vision/text-recognition/v2/android). Devanagari also exists, which matters for
  Hindi. **Caveat for ONYX**: ML Kit is a closed Google SDK under ML Kit terms, and **even the bundled SDK sends
  diagnostics/usage analytics** (device info, per-install ID, latency) to Google
  (https://developers.google.com/ml-kit/android-data-disclosure). That conflicts with ONYX's offline/no-telemetry stance.
  Prefer an open engine (PaddleOCR mobile det+rec via ONNX/ncnn, or Tesseract), or ONYX's existing VLM.
- **Merge** (`core/uistate/VisibleText.kt`, `ScreenMap.kt`): OCR lines are filtered (low confidence, symbol-only, stray
  Latin fragments on CJK screens). Each line gets 0–999 normalised boxes and a tap centre, and is linked to the tree node
  that contains most of it (not when overlapping nodes make ownership unclear, and never to a node hidden under an upper window).
  `StillFrameRead.kt` waits for the frame to settle before OCR. For fields that are not in the tree, it pastes and then checks
  the text before and after the paste (`core/uistate/UnseenField.kt`).
- **Compression for the model**: `UiTreeJson.kt` keeps the full hierarchy and only drops empty fields and default
  states. The tool layer offers `phone_observe` (summary), `phone_list_elements`, and `phone_query` (search the full tree by
  text/id/class) instead of dumping everything. The evidence ledger + `ContextCompressor` fold old observations. Tools load
  in groups (phone/web/sandbox only expand after their entry tool is used: `core/tool/ToolGroups.kt`), so the system
  prompt stays small. ONYX's skeleton/regions + drill-down is already the more compact approach for a small local model.
- **Element refs**: per-display prefix + counter (`p1`, `w2` …). `RefStabilizer.kt` keeps a ref across reads when
  role+name+resource-id is unique on both screens, the app is the same, the previous named neighbour is the same, and the
  centre moved by less than a share of the short edge. List rows never keep their ref after a scroll. Refs are never reused.
  Vision-found points are `@vN`. ONYX's `@eN` refs are per-snapshot and fail `STALE` on a structural change. Mika's approach
  saves a re-read per step.

---

## 5. Agent loop, verification, recovery, long lists, eval

- **Loop** (`core/agent/AgentLoop.kt`, scheduling only): `ModelCaller` (retries, compression, big/small model split),
  `TurnPolicy` (nudges on an empty, truncated or leaked tool-call reply, or a language switch), `StopRules` (give up,
  guard stop, model failure, step cap), `BatchRunner` (`do_steps`), `IntentGate` (chat vs act, once per run),
  `StuckAdvice`, `RouteReplay` (replays a previously successful route), `UserExchange` (ask/confirm/takeover). There is no
  "done" tool: a reply without tool calls is the answer. `end_not_done` gives a typed failure. Steering messages
  from the user are injected between steps (`RunInbox`).
- **Single action path**: every world-changing action goes through `ActionExecutor` (guard → lock check →
  irreversible check/consent → execute → post-action check → record). `ActionPass` has an internal constructor, so
  nothing can bypass it at compile time.
- **Verification**: each action reports an `ActionEffect` (`core/uistate/ActionEffect.kt`: changed / `no_effect` /
  `treeBlind`; typed text is read back). `do_steps` waits **in code** for `expect` / `expect_gone` / `expect_app` /
  `wait_stable` (default 8 s, max 20 s) instead of asking the model "is it loaded yet". It stops early on dialogs,
  foreign windows or an app switch. `OutcomeJudge` sorts the finished run into Done/Partial/NotPossible. The independent
  `Verifier`/`Grounding` exist but are **switched off in the product** (`reviewFinalAnswer = false`). ONYX's per-step
  Verifier is stronger here.
- **Recovery / guards** (`core/guard/OperationGuards.kt`, `GuardRules.kt`, `Fingerprints.kt`): action signatures and
  screen fingerprints catch the same action repeated, the same state revisited (an escalating ladder), cycles, no new state, re-reading
  the same screen, and too many failures → warn, then stop.
- **Long lists**: `phone_collect` (`core/cap/phone/PhoneAutomationTools.kt` `PhoneCollect`): scroll toward the content,
  read, merge overlapping lines, and stop when no new text appears twice, the app changes, an action fails or a limit is hit
  (`fromY`/`toY` exclude sticky headers or bottom navigation). Each scroll is recorded as an action. Pacing caps screen changes per app (24/task).
- **Eval** (`eval/README.md`, `eval/tasks/`, runner `app/src/androidTest/.../eval/EvalRunTest`): 128 JSON tasks —
  read 43 (system/content/shopping), write 11, irreversible 9 (expected outcome `confirmation`, not run by default),
  impossible 8, barrier 5 (expected outcome `takeover`), web 22, skills 20, memory 10. Each task has `goal`, `requires`
  (`installed:pkg`, `app:calculator` capability, `signedIn:`), `expect`, `checks` (`answer_regex`, `answer_contains`,
  `answer_grounded` = the numbers and quotes in the answer must appear in recent screen reads, `screen_has`, `asked_user`,
  `confirmation_shows`), a `marker` for write tasks, `maxSteps`, `timeoutSec`, `variants`, and **`partition` tune (~70 %) /
  holdout (~30 %)**. Holdout tasks are not used for tuning. `TaskSetTest` lints the set. The output is `summary.md` +
  `scores.jsonl` with time and token metrics. Manual re-grading needs evidence from the run record.
- **Self-check** (`core/selfcheck/SelfCheck.kt`, `SelfCheckRunner.kt`, `app/selfcheck/AndroidSelfCheck.kt`,
  `SelfCheckTargetActivity.kt`): DEVICE → PERMISSIONS → HELPER → VIRTUAL_DISPLAY → APP_LAUNCH (its own test page, then
  Settings, look-only) → SCREEN_READING → ACTIONS (tap, type EN+ZH, scroll, back, all read back) → STAYS_ALIVE (frames keep
  changing for 10–25 s) → MAIN_SCREEN (the user's top task never changed) → BROWSER → SANDBOX → APP_COPIES. Each step has a
  timeout of 5–25 s and declared dependencies (a dependant is skipped when its parent fails), with a 90 s total cap. The
  report has English keys, sensitive data removed, and a GitHub issue template.

---

## 6. Settings pages

- **Mika does not restrict Settings at all.** "Changing settings" is explicitly *not* asked about in either mode
  (`docs/modes.en.md`). The `android-settings` knowledge file (`core/src/main/resources/knowledge/apps/android-settings/APP.md`)
  only warns the model that permissions, developer options, accounts, security, unknown sources and reset "really change the
  device". The user can deny whole packages in "App access" (`docs/dev/app-access.md`). That denylist only binds the standard
  phone tools: `phone_system_command` (shell) is not covered, as Mika's own doc admits. Its eval reads Settings
  (`smoke-settings-version`). For its **own** settings, Mika has a good pattern: `settings_list` / `settings_set` expose
  only an allowlist (no model, keys, network or data-deleting items) (`core/settings/SelfConfig.kt`).
- **ONYX today** blocks `com.android.settings` (and OEM settings/permission/installer packages) for screen use through the
  shared `system_admin` category, plus `SETTINGS_FORBIDDEN` words. The Play Store is blocked by `store_purchase`.

### Recommendation: default-deny, page-allowlist for Settings; keep the Play Store blocked

Prefer **APIs first** (no screen at all) wherever ONYX already has the permission: brightness and screen timeout
(`WRITE_SETTINGS`, already held), DND (`ACCESS_NOTIFICATION_POLICY`, already held), volumes (`AudioManager`), and
Settings **panels** (`Settings.Panel.ACTION_INTERNET_CONNECTIVITY`, `ACTION_WIFI`, `ACTION_VOLUME`, `ACTION_NFC`), which
the user taps. Use the screen only where no API exists (apps targeting API 33+ cannot toggle Bluetooth; `setWifiEnabled`
is gone for target ≥29).

**Allow (screen), entered only via its intent and kept to that page subtree:**

| Page | Entry intent | Allowed | Gate |
|---|---|---|---|
| Wi-Fi | `ACTION_WIFI_SETTINGS` | read list, toggle, connect to a **saved** network | toggle = HIGH; typing a Wi-Fi password → take over |
| Bluetooth | `ACTION_BLUETOOTH_SETTINGS` | toggle, connect/disconnect a **paired** device | new pairing (PIN) → take over |
| Display | `ACTION_DISPLAY_SETTINGS` | brightness, dark theme, screen timeout, font/display size, eye comfort/night light, refresh rate | HIGH |
| Sound & vibration | `ACTION_SOUND_SETTINGS` | volumes, ring/vibrate/silent, ringtone | HIGH |
| Do Not Disturb | `ACTION_ZEN_MODE_PRIORITY_SETTINGS` / DND page | on/off, schedules | HIGH |
| Battery | `ACTION_BATTERY_SAVER_SETTINGS` | battery saver toggle, read usage | HIGH |
| Location | `ACTION_LOCATION_SOURCE_SETTINGS` | master toggle only | **CRITICAL** (privacy) |
| Airplane / mobile data / hotspot | `ACTION_AIRPLANE_MODE_SETTINGS`, `ACTION_WIRELESS_SETTINGS` | toggles | **CRITICAL** (cuts connectivity) |
| Storage, About phone | `ACTION_INTERNAL_STORAGE_SETTINGS`, `ACTION_DEVICE_INFO_SETTINGS` | **read-only** | redact IMEI/serial/MAC from screen data |

**Keep blocked (hard, not overridable):** Accessibility; device admin apps; **developer options** (USB/Wireless
debugging; some toggles erase data, such as "Boot with 16 KB page size"); app permissions / permission manager; special app
access (display over other apps, notification access, usage access, **install unknown apps**, all-files access, device &
app notifications, alarms & reminders); default apps / digital assistant; **accounts & sync** (Google account); security
& privacy, **screen lock / fingerprint / face**, Find My Device, SIM lock, encryption & credentials, certificate install,
VPN; passwords & autofill / Google Password Manager; **reset options / erase all data / erase eSIM**; system update;
users & multiple users; work profile; NFC payments / default wallet; OxygenOS **App lock / Hidden apps / Private safe**;
backup; app info pages (force stop, clear storage, uninstall). Also the Settings **search box** and the **top-level
Settings home** (both can jump anywhere).

**How to implement in ONYX:** add a `SettingsPagePolicy` in `:core:screen` and consult it before `system_admin` for
`com.android.settings` (and `com.oplus.*` settings packages) only. The page identity is the entry intent the agent launched,
plus the window title / `TYPE_WINDOW_STATE_CHANGED` class, plus a title allowlist. On OxygenOS many pages are
`SubSettings`, so the title is needed, and it must be localised (English plus the user's UI language). Leaving the
subtree, or an unknown title, means stop (Back is allowed). Keep `permissioncontroller`, `packageinstaller`,
`com.oplus.securitypermission` and `SETTINGS_FORBIDDEN` as they are. Keep the **Play Store blocked** for screen use and
use `app_view market://details?id=…` for "open this app's page".

---

## 7. Linux sandbox and built-in browser (brief)

- **Sandbox**: PRoot 5.4.0 (GPL-2.0+, 4 patches) + talloc (LGPL-3.0) as `libproot.so` + an Alpine 3.22 aarch64 rootfs
  with Python 3.12, Node 22, Pillow and pypdf, **bundled in the APK** (`app/src/main/assets/runtime/rootfs.tar.xz`) and
  unpacked offline. One workspace per conversation. Offline by default, with a "allow scripts to go online" switch.
  arm64 only. GPL sources are published per release. This is not needed for ONYX now; borrowing it means GPL
  compliance work.
- **Browser**: WebView rendered on an **app-private VirtualDisplay** (no helper needed), driven over the WebView
  DevTools protocol (`app/cap/web/cdp/WebDevtools.kt`) with real input events. It never enables `Runtime` (sites notice
  debuggers). Read scripts run in an isolated world, and `web_evaluate_js` is read-only with network blocked. Desktop UA by
  default, a 4 tab/task pool, persistent cookies shared with a user-visible browser page, pacing and anti-bot page
  detection, and takeover moves the WebView full-screen. A useful later reference if ONYX adds web tasks.

---

## 8. Top 8 ideas to adopt (ranked by value ÷ effort)

Effort is in focused dev-days for ONYX, including JVM tests, and excludes device testing unless stated.

| # | Idea | Borrow / adapt from Mika | Into ONYX | Effort |
|---|---|---|---|---|
| 1 | **Handoff classifier**: password field → login, SMS-code words → login, payment words → payment, captcha words, **two quiet black frames = protected** (black while changing = loading). Add English + Hindi/Telugu words and Indian payment words (UPI PIN, "Pay ₹", "Enter UPI PIN") | `core/uistate/ScreenDiagnosis.kt` (logic, 120 lines); idea from `core/general/HandleObstacle.kt` | `core/screen/Safety.kt` `screenVerdict` → `TakeOver` reasons; reuse ONYX's `takeScreenshot` probe as the frame source | **0.5–1 d** |
| 2 | **Code-checked waits inside multi-step batches**: `expect` / `expect_gone` / `expect_app` / `wait_stable` per step, early stop on dialog or app switch, per-step `ActionEffect` (`no_effect`, `treeBlind`, typed-text read-back). This saves LLM round-trips, which matters most for a slow on-device model | `core/agent/BatchRunner.kt`, `core/uistate/UntilWait.kt`, `core/uistate/ActionEffect.kt` | `core/screen/ScreenSession.kt` (`screen_do`/`screen_act` gain `steps[]`), ONYX diff → effect kinds | **2–3 d** |
| 3 | **Loop guards**: action signatures + screen fingerprints → same-action repeat, same-state revisit ladder, cycles, no progress, re-read loops; `StuckAdvice` text in the tool result | `core/guard/OperationGuards.kt`, `GuardRules.kt`, `Fingerprints.kt`, `core/agent/StuckAdvice.kt` | `:core:screen` (new `ScreenGuards.kt`) + `:core:agents` executor stop | **1–1.5 d** |
| 4 | **Stable refs across reads** (keep `@eN` when the element is unchanged; list rows always get new refs; refuse when ambiguous) instead of failing `STALE` on any structural change | `core/uistate/RefStabilizer.kt` (129 lines) | `core/screen/ScreenView.kt` | **1–2 d** |
| 5 | **Long-list reader** `screen_collect` (scroll + read + merge overlap, stop after two reads with nothing new, `fromY/toY` to exclude sticky bars, page cap) | `PhoneCollect` in `core/cap/phone/PhoneAutomationTools.kt` + `core/uistate/ScreenText.kt` | new tool in `core/screen/ScreenToolkit.kt` (risk MEDIUM) | **1.5–2 d** |
| 6 | **OCR fallback gated by tree quality** + OCR↔tree linking (only OCR when the tree is not clearly adequate; never on protected screens) | `core/uistate/TreeQuality.kt`, `VisibleText.kt`, `ScreenMap.kt`, `app/cap/phone/a11y/StillFrameRead.kt`; **not** ML Kit itself (telemetry) — use PaddleOCR-mobile/Tesseract or ONYX's VLM behind the same interface | `:core:screen` (pure logic) + `app/onyx/screen` (`OcrPort`) | **3–4 d** (+ engine choice) |
| 7 | **On-device self-check + eval task format** (dependency-ordered checks with timeouts and a report with sensitive data removed; JSON tasks with `answer_grounded`, `marker`, tune/holdout) | `core/selfcheck/*`, `app/selfcheck/*`, `eval/README.md` + task schema | new `app/onyx/selfcheck`, `docs/eval/` + an androidTest runner | **2 d** self-check, **3–4 d** eval runner |
| 8 | **Hidden background screen** (sideload "Pro mode"): shell helper, trusted VirtualDisplay with OWN_FOCUS, launch with `setLaunchDisplayId`, a11y gestures with `setDisplayId` + shell injection fallback, `DisplayAwake`, ImageReader preview PiP, takeover by `moveTask` to the main screen | `core/adb/*` (or Shizuku instead), `app/cap/phone/shell/{ShellService,HelperProcess,HelperHub,ShellContext,DisplayAwake,ShellBinding}.kt`, `app/cap/phone/display/{AgentDisplay,DisplayFrameGeometry}.kt`, `app/cap/phone/setup/{PairingCoordinator,PairingNotifier,PairingCodeReader,BuiltinShellManager}.kt`, `app/ui/AgentPip.kt`, `app/takeover/Takeover.kt` | new `:app` package `onyx/shell` + an `AIDL`; `ScreenPort` gains a `displayId` | **1 d spike**, then **12–18 d** (or **7–10 d** with Shizuku) |

Honourable mentions: the per-site/app page caps and outward-action spacing (`OutwardPacing.kt`) for when ONYX sends
things in batches. The `settings_list`/`settings_set` allowlist pattern for letting the model change ONYX's *own*
settings safely (`core/settings/SelfConfig.kt`).

**Do not adopt**: Mika's payment-only gating, Autonomous mode, `phone_system_command` (arbitrary shell for the model), or
the auto-enabling of its own accessibility service through `WRITE_SECURE_SETTINGS` without an explicit ONYX consent screen.

---

## 9. Risks

- **Play policy**: the screen agent is already sideload-only (Accessibility API policy). A self-ADB helper adds
  hidden-API reflection, `WRITE_SECURE_SETTINGS` self-grants, and turning on its own accessibility service. Treat all of
  it as **sideload-only, never in the Play flavour** (compile it out behind `-PscreenAgent=false` /
  a new `-PshellHelper=false`). Mika itself is not on Play.
- **ADB-pairing UX**: six steps, Developer options and **USB debugging left on permanently**. Wireless debugging turns off on
  Wi-Fi change and on reboot. A new Wi-Fi network needs "Always allow on this network". Pairing codes die if the dialog closes
  (Mika works around this with a notification RemoteInput + a11y code reader). The Developer options screen has
  data-wiping toggles. **Some Indian banking/UPI apps are reported to refuse to run while Developer options or USB
  debugging is on.** That is a real cost for this user and needs checking on the device.
- **Security**: a persistent shell-uid process is a powerful foothold (it can grant permissions, install packages, read logs,
  inject input anywhere). Mika limits it to its uid, an APK-cert pin and a one-time nonce. ONYX would need the same, plus no
  shell tool for the model, plus a visible "helper running" notification and a one-tap kill.
- **Battery/thermal**: the helper stays resident. The hidden display keeps apps resumed and rendering. The ImageReader preview
  runs up to 10 fps. `DisplayAwake` pokes user activity on a timer. Mika also adds itself to the doze whitelist. Mitigations:
  create the display per task and release it at the end, preview only while the PiP is visible, and lower fps when idle.
- **OxygenOS specifics** (unverified): `ADD_TRUSTED_DISPLAY` for shell, OEM toggles needed for input injection or
  `pm grant`, the background killer, and orientation quirks (`STEAL_TOP_FOCUS_DISABLED` can letterbox landscape apps). Run a
  self-check (idea 7) before building on it.
- **ML Kit**: closed source with analytics even when bundled (see §4). That conflicts with ONYX's offline promise.
- **Model gap**: Mika is tuned for frontier cloud models. Its prompts and tool count assume a strong model. Borrow
  mechanisms that *remove* model calls (ideas 2, 3, 5), not ones that add reasoning load.

---

## 10. Licence implications

- Mika is **Apache-2.0** (`LICENSE`) with a `NOTICE` that reads: `Mika` / `Copyright 2026 wangzan101`.
- ONYX at `eb73f5c` has **no top-level LICENSE file** (the README "Licences" section lists only third-party licences).
  Apache-2.0 code can go into a project under any licence, including none or proprietary, but **choose and add ONYX's own
  licence** before distributing builds that include borrowed code.
- If code is copied or adapted (Apache-2.0 §4):
  1. Ship a copy of the Apache-2.0 text (e.g. `third_party/mika/LICENSE`, or `licenses/Apache-2.0.txt`).
  2. Add (or create) a top-level **`NOTICE`** in ONYX that carries Mika's NOTICE text verbatim, e.g.
     `This product includes software adapted from Mika (https://github.com/wangzan101/Mika), Copyright 2026 wangzan101, licensed under the Apache License 2.0.`
     It must be shown wherever ONYX shows third-party notices (README "Licences" + the in-app licences screen).
  3. In each borrowed/adapted file, keep any existing copyright/attribution header. Mika's files mostly have none, so add
     one: "Adapted from Mika `<path>` @ 6b90394, Copyright 2026 wangzan101, Apache-2.0", plus a **"Modified by ONYX"**
     statement (§4(b) requires changed files to carry a prominent notice).
  4. `app/…/cap/phone/shell/ShellContext.kt` is itself **adapted from scrcpy** (Copyright 2018 Genymobile, 2018–2024
     Romain Vimont, Apache-2.0). Keep that header and add scrcpy to ONYX's NOTICE/licences too.
  5. **Do not copy** the sandbox binaries or rootfs (PRoot GPL-2.0+, talloc LGPL-3.0, Alpine packages under mixed GPL
     licences) unless ONYX takes on GPL source-distribution duties. **ML Kit** is not Apache, it is under Google's ML Kit terms.
  6. Re-implementing ideas from the docs without copying code carries no obligation, but a credit line is good practice.
- Apache-2.0 also includes a patent grant and a trademark clause: don't use the "Mika" name or logo for ONYX features.

---

## Sources

Mika repo files cited above (commit `6b90394`), mainly `README.md`, `docs/features.en.md`, `docs/modes.en.md`,
`docs/setup-guide.en.md`, `docs/dev/architecture.md`, `docs/dev/app-access.md`, `eval/README.md`,
`licenses/THIRD_PARTY_NOTICES.md`, `NOTICE`. ML Kit sizes: https://developers.google.com/ml-kit/vision/text-recognition/v2/android.
ML Kit data collection: https://developers.google.com/ml-kit/android-data-disclosure. scrcpy FAQ (Xiaomi input toggle):
https://github.com/Genymobile/scrcpy/blob/master/FAQ.md. ONYX files at `eb73f5c` as listed at the top.
