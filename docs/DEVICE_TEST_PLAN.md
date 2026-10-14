# Device test plan (Sai's phone)

Everything below needs a real phone; the sandbox could not run Android. Note results in the table at the end and send
back `adb logcat` snippets for anything that fails.

## 0. Prepare (needs internet, once)

1. On the phone (or PC) download **`Qwen3-0.6B-Q8_0.gguf`** (~610 MB) from
   https://huggingface.co/Qwen/Qwen3-0.6B-GGUF/tree/main → Files → `Qwen3-0.6B-Q8_0.gguf` → download.
   (Optional second file for comparison: a Q4_K_M build, ~400 MB, e.g. from `unsloth/Qwen3-0.6B-GGUF`.)
2. Install the APK: `adb install -r offline-agent-phase1-debug.apk` (or copy it to the phone and open it; allow "install unknown apps").
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
| 2.1 | Open app → Model tab → **Pick .gguf file** → choose the downloaded file | Progress bar with MB copied; finishes in seconds–1 min |
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

## 8. Logs to send back if something fails

```
adb logcat -s OfflineAgentLlama llama.cpp OfflineAgent AndroidRuntime DEBUG
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
