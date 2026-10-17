# Device test plan (Sai's phone) — Phase 1 + Phase 2 + Phase 3

Everything below needs a real phone; the sandbox could not run Android. Note results in the table at the end and send
back `adb logcat` snippets for anything that fails.

## 0. Prepare (needs internet, once)

1. On the phone (or PC) download **`Qwen3-0.6B-Q8_0.gguf`** (~610 MB) from
   https://huggingface.co/Qwen/Qwen3-0.6B-GGUF/tree/main → Files → `Qwen3-0.6B-Q8_0.gguf` → download.
   (Optional second file for comparison: a Q4_K_M build, ~400 MB, e.g. from `unsloth/Qwen3-0.6B-GGUF`.)
   **Phase 2 also needs** (all optional except the chat model): `Qwen3-1.7B-Q4_K_M.gguf` (1.1 GB, unsloth/Qwen3-1.7B-GGUF) for
   Canvas/skills, `bge-small-en-v1.5-q8_0.gguf` (37 MB) for document search, and for photos
   `SmolVLM2-256M-Video-Instruct-Q8_0.gguf` + `mmproj-SmolVLM2-256M-Video-Instruct-Q8_0.gguf` (ggml-org, 280 MB total).
2. Install the APK: `adb install -r offline-agent-phase2-debug.apk` (or copy it to the phone and open it; allow "install unknown apps").
3. Note phone model, SoC, RAM, Android version.

## 1. Install & offline proof (static)

| # | Step | Expected |
|---|---|---|
| 1.1 | Settings → Apps → Offline Agent → Permissions | "No permissions requested" |
| 1.2 | `adb shell dumpsys package com.offlineagent.app \| grep -i permission` | no `android.permission.INTERNET` |
| 1.3 | App info → Mobile data & Wi-Fi | no data usage controls / 0 B used |

## 2. Model provisioning

| # | Step | Expected |
|---|---|---|
| 2.1 | Open app → **Models** tab → Chat → **Add** → choose the downloaded file | Progress bar with MB copied; finishes in seconds–1 min |
| 2.2 | During a copy, tap **Cancel copy** | Copy stops, no model listed, no leftover space used |
| 2.3 | Pick a non-GGUF file (e.g. a PDF/JPG) | Clear error "not a .gguf file" / "not a valid GGUF" |
| 2.4 | Kill the app (swipe away) and reopen | Model is still listed (remembered) |
| 2.5 | You can now delete the downloaded copy in Downloads; the app keeps its own | Load still works |

## 3. Airplane-mode end-to-end (the core offline test)

1. Turn on **Airplane mode**, turn **Wi-Fi and Bluetooth off** too.
2. Force-stop the app, reopen it.
3. Model tab → **Load model**. Expected: "Ready" within ~1–3 s; model info shows `qwen3 0.6B Q8_0`, context 2048, threads = number of big cores.
4. Chat tab → ask *"What is the capital of India?"* Expected: streamed answer token-by-token, "Delhi"/"New Delhi", tok/s shown.
5. Follow-up *"And of Telangana?"* Expected: "Hyderabad" (proves multi-turn context). Prompt time for this turn should be short (KV reuse).
6. Ask something in Telugu/Hindi, e.g. *"తెలుగులో ధన్యవాదాలు అని ఎలా చెప్పాలి?"* Expected: no garbled `�` characters.
7. All of the above works with no network at all.

## 4. Smoothness & speed

| # | Step | Expected / record |
|---|---|---|
| 4.1 | While a long answer streams (*"Write a 500-word essay on Hyderabad"*), scroll the chat, open the keyboard, switch tabs | UI stays smooth (no freezes, no "App not responding") |
| 4.2 | Record decode tok/s (shown under each reply) for Q8_0, thinking OFF | Record. Rough expectation for a 2022–24 mid-range SoC: **15–40 tok/s** |
| 4.3 | Record prompt speed ("prompt N tok in X s") on the first message | Record |
| 4.4 | Model tab → threads −/+ → Unload → Load → repeat 4.2 with recommended, recommended−1, all cores | Recommended should be best or close; record numbers |
| 4.5 | Model tab → ⓘ "Device CPU features" | Record the text (shows NEON/DOTPROD/I8MM/SVE and the chosen CPU device) |
| 4.6 | 5 minutes of continuous chatting | Phone warm, not hot; tok/s may drop a bit (thermal throttling) — record |

## 5. Stop / cancellation

| # | Step | Expected |
|---|---|---|
| 5.1 | Start the long essay, press the red **Stop** square after 2 s | Text stops within ~0.5 s; footer says "· stopped"; Send button comes back |
| 5.2 | Immediately send another message | Works normally |
| 5.3 | Paste a very long message (~3,000 words) and press Stop during "…" | Stops quickly even during prompt processing |

## 6. Thinking toggle

| # | Step | Expected |
|---|---|---|
| 6.1 | Thinking OFF (default): *"Is 17 prime?"* | Direct answer, no "Thinking…" section |
| 6.2 | Thinking ON: same question | "Thinking…" streams, then answer; "Show reasoning" expands it; slower overall |

## 7. Lifecycle, memory and errors

| # | Step | Expected |
|---|---|---|
| 7.1 | Model tab → Unload | Status "Not loaded"; chat input disabled |
| 7.2 | Load → rotate the phone / switch dark mode during generation | No crash; generation continues |
| 7.3 | With model loaded, open several heavy apps (camera, games, Chrome tabs) then come back | Either still loaded, or snackbar "unloaded because the phone ran low on memory" and Load works again. **Never a crash.** |
| 7.4 | Simulate: `adb shell am send-trim-memory com.offlineagent.app RUNNING_CRITICAL` (also try `COMPLETE`) | Model unloads, snackbar shown |
| 7.5 | Context 4096 + long conversation (~15 turns) | Keeps answering; oldest turns silently dropped when full |
| 7.6 | Replace model with a truncated file (e.g. `head -c 100000000 model.gguf > broken.gguf`) and Load | "Could not load model: …" error, Retry button, no crash |

## 9. Phase 2 — persistence

| # | Step | Expected |
|---|---|---|
| 9.1 | Chat a few turns, swipe the app away, reopen | Same chat reopens with all messages; ☰ lists earlier chats |
| 9.2 | Models tab: context/threads changed, kill app, reopen | Settings kept |
| 9.3 | Upgrade from the Phase 1 APK (install over it) | The Phase 1 model appears under Models → Chat (migrated) |

## 10. Rich markdown

| # | Step | Expected |
|---|---|---|
| 10.1 | Chat mode: *"Explain Kotlin coroutines with a code example, a table of builders and the formula for speed-up"* | Headings, bold, bullet lists, a table, a highlighted code block with **Copy**, math shown with Unicode symbols |
| 10.2 | Scroll while it streams | Smooth; code block appears as code even before the closing ``` |

## 11. Canvas (use Qwen3-1.7B or 4B)

| # | Step | Expected |
|---|---|---|
| 11.1 | Auto mode: *"Compare iPhone 15, Pixel 8 and Galaxy S24 in a table with a bar chart of battery life"* | Canvas opens and fills in component by component; chat shows a canvas card; tok/s shown |
| 11.2 | Follow-up: *"make the table shorter"*, then *"add a pie chart of prices"* | Canvas updates in place (v2, v3) — same title, other blocks kept |
| 11.3 | Canvas ‹ › arrows and ⋮ history | Moves between versions; chat follow-ups continue from the shown version |
| 11.4 | *"Quiz me on the solar system"* / *"Flashcards for French greetings"* | Quiz with scoring, flashcards flip on tap |
| 11.5 | Switch to **Chat** mode and ask for a table | Plain markdown table, no canvas |
| 11.6 | With Qwen3-0.6B | Still valid canvases (simpler); note quality |
| 11.7 | ⋮ → Copy as Markdown / Share as text | Markdown copied / share sheet opens |

## 12. Documents (RAG)

| # | Step | Expected |
|---|---|---|
| 12.1 | Models → Embedding → Add bge-small. Docs → Import a text PDF (10–50 pages) | Progress "Reading PDF page x/y", then "Embedding n/N"; Cancel works and leaves nothing behind |
| 12.2 | Ask a question answered in the PDF | Answer cites [1]/[2]; source chips below; tapping shows the passage |
| 12.3 | Import a scanned (image-only) PDF | Clear "no extractable text" error |
| 12.4 | Remove the embedding model, ask again | Still finds passages by keywords |

## 13. Skills + agent

| # | Step | Expected |
|---|---|---|
| 13.1 | Chat: + → Run a skill → *Compare options* with text *"tea vs coffee for night study"* | Card shows Plan, live "Step n", then a canvas (comparison/chart/callout); **Steps** expands tool calls with inputs/outputs/timing |
| 13.2 | `/study-plan exam in 5 days` (with a PDF imported) | search_docs calls, then a plan table + checklist canvas |
| 13.3 | *"/daily-planner gym at 7, report by 3pm, call mom; add the gym to my calendar"* | Approval dialog for create_calendar_event (shows title/time) → Allow → calendar app opens pre-filled; Deny → run finishes without it |
| 13.4 | Press Stop during a run | Run ends "cancelled"; Runs tab shows it |
| 13.5 | Start a run, then force-stop the app (`adb shell am force-stop com.offlineagent.app`) during a step | On reopen: banner "Unfinished agent run — Resume"; Resume continues from the saved step |
| 13.6 | Skills tab: Duplicate & edit a built-in, change the instructions, Save; Import a .md skill; disable/delete | Validation errors shown for bad front matter; edited skill runs |
| 13.7 | Runs tab → open a run | Every step with input, output, errors, ms |

## 14. Photos (vision)

| # | Step | Expected |
|---|---|---|
| 14.1 | Models → Vision → Add SmolVLM2 gguf, then **Add projector** → mmproj | Card shows the projector name |
| 14.2 | Chat: + → Photo from gallery → *"What is in this photo?"* | "Looking at the image…", answer about the photo; record seconds |
| 14.3 | + → Take a photo (camera app) | Photo attaches and works the same |
| 14.4 | Next text-only message | Chat model reloads automatically, answer works |

## 15. Offline + smoothness (Phase 2)

| # | Step | Expected |
|---|---|---|
| 15.1 | Airplane mode, repeat 11.1, 12.2, 13.1, 14.2 | All work offline |
| 15.2 | Scroll and type while a canvas streams / documents index | No jank, no ANR |
| 15.3 | `aapt dump permissions` / Settings → Apps → Permissions | Still **no** internet permission |

## 16. Memory

| # | Step | Expected |
|---|---|---|
| 16.1 | Qwen3-1.7B, context 4096: watch RAM (Developer options → Memory) during a canvas | Record peak |
| 16.2 | Background the app during a run | Run continues or is resumable; no crash |

## 17. Phase 3 — install both builds

| # | Step | Expected |
|---|---|---|
| 17.1 | Install `offline-agent-phase3-offline-debug.apk` (package `com.offlineagent.app`, upgrades Phase 2 in place) | Opens; old chats still there (DB migration) |
| 17.2 | Optional: install `…-connected-debug.apk` (package `com.offlineagent.app.connected`, separate app) | Both apps coexist |
| 17.3 | Settings → Apps → Offline Agent → Permissions on the offline build | No "Network access" listed |

## 18. Long context & speed (8 GB phone, Qwen3-1.7B Q4_K_M recommended)

| # | Step | Expected |
|---|---|---|
| 18.1 | Settings → Performance | Shows RAM class "8 GB", Auto = 16K, memory estimate per size, 64K/128K disabled or marked experimental |
| 18.2 | Auto (16K), q8_0 KV, flash attention Auto; ask a short question | Record prompt tok/s and gen tok/s from the stats line |
| 18.3 | Same with q4_0 KV and with F16 KV | Note speed + RAM differences (host: q4_0 was slower) |
| 18.4 | Paste a ~10K-token text (e.g. a long article) and ask a question about its middle | Correct answer; record time to first token and peak RAM |
| 18.5 | Kill the app, reopen, ask anything | First answer noticeably faster than a cold first answer (prompt-prefix cache from disk) |
| 18.6 | Long chat (> 40 turns) | "Summarising older messages…" appears once in a while; the chat keeps working; earlier facts recalled via memory |
| 18.7 | Max answer 8192, ask for a long essay | Keeps writing past the window (context shift) without crashing |
| 18.8 | With Qwen3-4B as chat model, enable speculative decoding with Qwen3-0.6B | Record gen tok/s with/without; keep whichever is faster |
| 18.9 | 32K context | Works but first answer of a long chat is slow; watch heat and RAM (record) |
| 18.10 | 10 minutes continuous use | Record temperature feel / throttling (tok/s drop) |

## 19. PDF

| # | Step | Expected |
|---|---|---|
| 19.1 | "Write a 4-page report on solar energy in India as a PDF" (Write report skill) | Notification with progress + Cancel; PDF has title page, headings, lists, a table, page numbers |
| 19.2 | Open the PDF preview, Share, Save as… | Preview pages render; share sheet; file saved where picked |
| 19.3 | Canvas → Export PDF; chat menu → Export chat as PDF | Both produce readable PDFs in Downloads/OfflineAgent |

## 20. MCP

| # | Step | Expected |
|---|---|---|
| 20.1 | Settings → MCP servers | Built-in servers listed (files, notes, clipboard, calendar/contacts, share, phone groups) |
| 20.2 | Install `mcp-server-sample-debug.apk` ("MCP Study Tools"), Scan again, Connect | Its tools appear; calling one asks for approval |
| 20.3 | Connected build only: add a LAN Streamable-HTTP MCP server URL | Connects; tools listed; offline build shows "not available" |

## 21. Phone actions (enable each group in Settings → Phone actions)

| # | Step | Expected |
|---|---|---|
| 21.1 | "Set an alarm for 6:30 am", "timer 5 minutes", "remind me at 9 pm to call home" | Approval → Clock app alarm/timer; reminder notification fires |
| 21.2 | "What's on my calendar tomorrow?", "find Ravi's number" (grant permissions) | Correct data; nothing leaves the phone |
| 21.3 | "Open YouTube", "draft a WhatsApp to +91… saying I'm late", "email draft to …" | App opens / draft prefilled; you press Send |
| 21.4 | Flashlight on/off, volume 30 %, DND on, battery/storage info, clipboard read | Works; DND asks for access first |
| 21.5 | Grant a folder; "find PDFs about internships in my folder and move them to a new folder Applications" | Approval per change; files moved |
| 21.6 | Notification access on; "summarise my notifications" | Summary; nothing stored |

## 22. Form filling & automation

| # | Step | Expected |
|---|---|---|
| 22.1 | Settings → Profile: fill name, email, phone, college, CGPA, LinkedIn; Save | Saved encrypted; asks fingerprint/PIN to unlock later |
| 22.2 | Make Offline Agent the autofill service; open a Google Form / internship form in Chrome | Autofill suggestion "Fill from Offline Agent profile" → unlock → fields filled; password/OTP/card fields left empty |
| 22.3 | Enable screen automation (read the disclosure); in chat: "fill this internship form in Chrome" with the form open | Overlay shows steps; Pause/Stop work; asks approval before Submit |
| 22.4 | Open a banking/UPI app and ask the agent to tap something | Refused (blocked by default) |
| 22.5 | A page with a CAPTCHA | Agent stops and asks you to solve it |
| 22.6 | OTP field | Agent asks you for the code; types it only if you give it |
| 22.7 | Record a macro (e.g. open an app and search a fixed term), save, mark the term as a value, run by name; schedule daily | Replays; scheduled run never presses submit/pay/send |
| 22.8 | Settings → Automation → action log; "Stop everything"; turn the service off | Every step logged; everything stops |

## 23. Phase 3 results to record

Phone model / SoC / RAM; 16K prompt tok/s, gen tok/s, peak RAM; 32K feasible?; heat after 10 min; autofill works in
Chrome + one app; screen agent success on one real form; any crash logs (§8).


## 24. Phase 4 — P0 checks first (garbage output / crashes)

1. Install the Phase 4 offline APK **over** Phase 3 (keep data). Open the app: it must start (no crash) and show the chat.
2. Settings (drawer → Settings) must open with no model loaded. Change Performance → context Auto/4K/8K and KV f16/q8_0:
   each change saves at once and shows "Saved…"/"Applied: model reloaded"; the UI never freezes.
3. Send "What is the capital of France?". Expected: a sane answer, header shows ≤ 8.2K context. Record tok/s.
4. Settings → Diagnostics: self-test should read "pass — “5”". Note the "CPU variant" line and the CPU feature line
   (SVE/SME/I8MM…) and send them to us.
5. If you see a safe-mode or "broken output" banner: tap **Share log** and send the file. If it says "Restart now", do
   it and test again (compatibility CPU kernels).
6. Crash test: while a model is loading, swipe the app away. Reopen: it must start normally (a swipe is not a crash). If
   the app ever crashes, the next start must show "Last run crashed — safe mode" with Share crash log.
7. Duplicate send: tap Send twice quickly → exactly one user bubble.

## 25. Phase 4 — chat UI

1. Drawer: search, New chat, rename and delete a chat, Settings at the bottom.
2. Model picker: switch between two installed models; "Download models…" opens the models sheet.
3. Empty state: greeting + 4 chips; tapping a chip sends it.
4. Long answer: Stop works; auto-scroll follows while at the bottom and stops when you scroll up.
5. Long-press your message → Edit & resend → the old turn and answer are replaced. Regenerate an answer.
6. Thinking on (⋮): the "Thought" section is collapsible.
7. + → Photo / Camera / Document; Tools → each mode (Agent, Canvas, PDF report, Phone actions, Automation) shows its
   card in the thread; approvals appear as cards (Allow once / Always / Deny).
8. Dark mode, rotation, keyboard open/close: composer stays above the keyboard.

## 26. Phase 4 — OpenUI answers

1. "Compare Python and Java in a table with a bar chart" → table + bar chart render inline while streaming.
2. "Show my monthly spending as a pie chart: rent 40%, food 25%, travel 15%, other 20%" → pie chart.
3. "Help me plan a 3-day trip to Goa. Ask me what you need with a form." → a form; fill and submit → the values arrive
   as your next message and the model continues.
4. Tools → Canvas: "Make a study plan with steps and a checklist", then "add a chart of hours per day" → canvas v2;
   Undo/Redo switch versions; Export PDF works.
5. Settings → Performance → Rich answers Off → plain Markdown only.

## 27. Phase 4 — model downloader

1. Fresh install (or delete all chat models): the onboarding sheet recommends a model for your RAM; Download shows
   progress in the sheet, the strip under the top bar and a notification with Cancel.
2. Turn on airplane mode mid-download, then off, and tap Resume → it continues (not from 0).
3. Cancel from the notification → state "Cancelled"; Resume continues.
4. Paste `https://huggingface.co/Qwen/Qwen3-0.6B-GGUF/blob/main/Qwen3-0.6B-Q8_0.gguf` → downloads; a non-HF link is
   rejected.
5. Download SmolVLM2 (2 files) → attach a photo and ask about it.
6. Disconnect the network after downloading: chatting, documents and tools keep working.

## 8. Logs to send back if something fails

Phase 4: Settings → Diagnostics & crash logs → **Export & share logs** collects everything below in one file.


```
adb logcat -s OfflineAgentLlama llama.cpp OfflineAgent AndroidRuntime DEBUG SQLiteLog
```

## Results table (fill in)

| Item | Result | Notes |
|---|---|---|
| Phone / SoC / RAM / Android | | |
| 1 offline proof | | |
| 2 provisioning | | |
| 3 airplane mode E2E | | |
| 4.2 tok/s Q8_0 (threads=?) | | |
| 4.3 prompt tok/s | | |
| 4.5 CPU features line | | |
| 5 stop | | |
| 6 thinking | | |
| 7 lifecycle/memory | | |
| 9 persistence | | |
| 10 markdown | | |
| 11 canvas (model?) | | |
| 12 RAG | | |
| 13 skills/agent | | |
| 14 vision (s per photo) | | |
| 15/16 offline, smoothness, RAM | | |
