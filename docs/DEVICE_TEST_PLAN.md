# Device test plan (Sai's phone) — Phase 1 + Phase 2

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

## 8. Logs to send back if something fails

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
