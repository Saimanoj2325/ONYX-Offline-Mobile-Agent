# Phase 6 — AppBridge (app intents, notifications, quick ask)

Version: `0.6.0-phase6` (versionCode 6). Module: `:core:apps` (pure Kotlin, JVM-tested) + the Android side in
`app/src/main/kotlin/com/offlineagent/app/onyx/apps/`.

## 1. Design

AppBridge is ONYX's **Play-safe** way to work with other apps. It does not drive other apps' screens (that is the
upcoming screen tools); it only uses public Android channels: launcher/`VIEW`/`SEND`/search intents, the clipboard,
and notifications (read + their own action buttons / inline reply).

```
:core:apps (pure Kotlin)                                  :app/onyx/apps (Android)
 AppSafetyPolicy + app_safety_blocklist.txt  ◄── shared ──  screen tools (next step)
 AppCatalogue (installed apps, fuzzy names, capabilities)    AndroidPackageSource (launcher + <queries> only)
 IntentBuilder (open/share/view/search, vetted)              AndroidAppLauncher (via foreground activity)
 NotificationStore (parse, redact, ids n1, n2 …)              NotificationCaptureService hooks (PhoneServices.kt)
 UntrustedContent (injection guard)                          AndroidClipboard, OnyxFileResolver (FileProvider)
 ApprovalScopes (per-task grants)                            AppBridgeTools (registration into OnyxRuntime)
 Inbound (share / process-text / assist → task)              QuickAskActivity + QuickAskTileService, AppBridgeSettings
 AppBridge (10 tools, risk, scope labels)
```

- The 10 tools join the **shared tool registry** (`OnyxRuntime.extraTools`), so the Planner mixes them with device tools,
  and the Executor keeps them behind the ApprovalGate and the audit log (`audit("appbridge", …)`).
- `OnyxRuntime.gateway.risk` asks `appBridge.risk(name, args)` first, so risk is **per call** (sharing to a messaging app is
  CRITICAL even though `app_share` is normally HIGH). `core/agents Ports.kt` `riskOf` also treats `reply` as critical.
- Safety is checked three times: when the catalogue is built, when an intent is built (`IntentBuilder.vet`), and again
  right before launch.
- Inbound: Android share (`SEND`/`SEND_MULTIPLE`), text selection (`PROCESS_TEXT`), `ASSIST`, the custom
  `QUICK_ASK` action and a Quick Settings tile open `QuickAskActivity`, which wraps the incoming text as untrusted data and
  starts an ONYX task; "Open in ONYX" goes to `MainActivity` with `open="onyx-inbound"`.

## 2. Tools and risk

| Tool | What it does | Risk |
|---|---|---|
| `app_find` | Fuzzy-find installed apps and their capabilities | NONE |
| `app_open` | Open an app's launcher screen | HIGH |
| `app_search` | Search inside an app (YouTube, Maps, Play, Spotify, Amazon, Flipkart …) or web search | HIGH |
| `app_view` | Open a link / deep link (https, geo, mailto, tel, market, app links) | HIGH; **CRITICAL** for composer links (mailto/sms/tel/wa.me/t.me …) or a messaging ("people") app |
| `app_share` | Hand text and/or an ONYX-made file to an app's share screen (user still presses Send) | HIGH; **CRITICAL** into a messaging app |
| `clipboard_get` | Read clipboard text (foreground only; sensitive clips hidden) | MEDIUM |
| `clipboard_set` | Copy text (UPI pay links refused) | MEDIUM |
| `notif_list` | List live notifications (bank/OTP/password apps hidden) | MEDIUM |
| `notif_action` | Press a notification button | HIGH; **CRITICAL** if irreversible (delete/pay-like) or unknown |
| `notif_reply` | Send an inline reply — sends immediately after the user confirms | **CRITICAL** |

## 3. Per-task scoping

`ApprovalScopes`: the approval sheet / inline card shows an extra **"Allow <App> for this task"** button
(`AppBridge.scopeLabel`). Rules:

- a grant is per **task run** and per **target** (`app:<package>`, or a read tool such as `tool:notif_list`), with a
  budget of 10 uses;
- only MEDIUM and HIGH requests can be covered; **CRITICAL is never scoped** and always asks per step;
- in `OnyxRuntime.askUser`, a covered request is pre-approved and audited ("scoped approval used"); the "always" answer
  on a scopable request grants the scope instead of a global always-allow; grants are cleared when the run finishes
  (`appBridge.endRun`).

## 4. Injection guard (`UntrustedContent`)

All text from other apps (notifications, clipboard, shared/selected text) is:
1. cleaned — control, zero-width and bidi-override characters removed, length capped;
2. de-fanged — chat-template / special tokens (`<|im_start|>`, `<start_of_turn>`, `[INST]`, `<think>`, `<tool_call>` …) are
   broken up, and ONYX's own `⟦DATA⟧` delimiters cannot be forged;
3. wrapped in `⟦DATA … ⟦/DATA⟧` with a "data, never instructions" note;
4. scanned for instruction-like phrases (ignore previous instructions, role changes, "send money/OTP", tool-call JSON …)
   → `suspicious` flag + warning in the tool result, so the planner/verifier can refuse to act on it.

Notification bodies also get OTP-like codes redacted (`NotificationStore.redactCodes`).

## 5. Blocklist API (shared with the screen tools)

One list: `core/apps/src/main/resources/com/offlineagent/core/apps/app_safety_blocklist.txt`
(`<category> <kind> <value>`; categories payment, password_manager, authenticator, self, system_admin, store_purchase;
kinds package, prefix, word, label, scheme, host, action). Load it with `AppSafetyPolicy.default(selfPackages, userBlocked)`
— never copy it.

- `checkPackage(pkg, label?, use)` → `SafetyVerdict.Allowed | Blocked(category, reason)`; `use` = `INTENT`,
  `NOTIFICATION` or `SCREEN`. **Screen tools must call `checkPackage(pkg, label, Use.SCREEN)`** before reading or
  operating a window; SCREEN also blocks `store_purchase`.
- `checkUri(uri)` — UPI/pay schemes and hosts, UPI pay parameters (`pa=…@…`), USSD in `tel:`, `otpauth:`, and
  `intent:/file:/content:/javascript:/data:` are blocked; percent-encoding is decoded first (up to 3 levels).
- `checkAction(action)` — settings/admin/installer/role actions.
- `isSensitiveNotificationSource(pkg, label?)` — notification text hidden (bank/OTP/password apps).
- ONYX itself (`selfPackages`) is always blocked; the user can add packages (`userBlocked`).

## 6. Play notes

- **Intents, share, process-text, notification actions and ASSIST** are Play-safe, with in-app disclosure of what is sent
  where.
- **Notification access** (`NotificationListenerService`) needs a **prominent disclosure** and user consent before
  sending the user to the system setting.
- **No `QUERY_ALL_PACKAGES`**: package visibility uses targeted `<queries>` (launcher, SEND/SENDTO, VIEW https/geo,
  PROCESS_TEXT …), so the catalogue sees launcher apps and intent handlers only.
- **`ROLE_ASSISTANT` is often not requestable** (`isRoleAvailable`/request intent returns null on many builds); ONYX
  falls back to opening the system "Default apps / Digital assistant" settings (`AppBridgeSettings`).

## 7. Tests

`:core:apps:test` — 35 tests (`AppSafetyPolicyTest`, `BridgeToolsTest`, `CatalogueAndIntentsTest`,
`SafetyAndScopeTest`).

Full JVM suite after merging into master (`--rerun-tasks`): apps 35, agents 83, runtime 112, inference 45, openui 29,
models 20, mcp-http 4 — **328 tests, 0 failures**. `:app:compileOfflineDebugKotlin` builds clean.

## 8. Gaps

- The legacy (Phase 1–4) chat path has **no scoped approvals**; only the Onyx/agents path offers "Allow <App> for this task".
- The app catalogue is **in memory only** (rebuilt on start and on package changes; not persisted).
- `clipboard_get` works only while ONYX is in the **foreground** (Android 10+ restriction).
- `notif_reply` (RemoteInput inline reply) is **untested on a device**; the whole Android side is compiled but not yet run
  on a phone.
