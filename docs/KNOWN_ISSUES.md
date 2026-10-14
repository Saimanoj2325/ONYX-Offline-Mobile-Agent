# Known issues & limitations (Phase 1)

## Not yet verified (needs a physical phone)
1. **Nothing Android-specific has run on a device yet.** The sandbox has no emulator/phone. Verified in sandbox: unit tests, the
   real JNI bridge + real Qwen3-0.6B on an ARM64 Linux JVM (host harness), APK contents/permissions/16 KB alignment. Not verified:
   UI, SAF import, CPU-variant selection on a phone SoC, onTrimMemory behaviour, thermals, real tok/s. See DEVICE_TEST_PLAN.md.
2. **APK built with a community aarch64-host NDK r30** (the sandbox is aarch64 Linux; Google only ships x86_64/macOS hosts) and a
   static aarch64 aapt2. Output targets are standard (arm64-v8a, API 28, 16 KB-aligned), but for a release build, rebuild on a normal
   x86_64 machine with Google's NDK r30 / Android Studio. No source changes needed.

## Behaviour / UX
3. **Android 14+ unloads the model soon after the app goes to the background.** From API 34 only `TRIM_MEMORY_UI_HIDDEN`,
   `TRIM_MEMORY_BACKGROUND` and `COMPLETE` are delivered; we unload at `BACKGROUND` to avoid the OS killing the process. Coming back
   shows a snackbar; tap **Load** (≈1–3 s for 0.6B; pages are usually still in the page cache). Generation does not continue in the
   background (no foreground service yet — Phase 2).
4. **Chat history and settings are in memory only.** Killing the app clears the chat and resets context/threads/thinking
   (the imported model *is* remembered). Room persistence is Phase 5; DataStore settings Phase 2.
5. **Thinking ON can ramble** with a 0.6B model and hit the token limit (1536) before `</think>`, leaving an empty answer.
   Thinking OFF is the default for this reason.
6. **Small-model quality**: Qwen3-0.6B answers simple factual questions well but is weak at Telugu/Hindi and long reasoning.
   The bytes are decoded correctly (no `�`), the content itself can be poor. Larger GGUFs (1.7B/4B) will work if RAM allows.
7. **Non-Qwen GGUFs use the ChatML fallback template.** Llama/Gemma/Phi models will load and run but may answer poorly until a
   template registry / Jinja support lands (Phase 2).
8. **Previous answer is re-evaluated each turn.** History re-renders past assistant turns without the empty think block (as the
   official template does), so KV reuse stops at the last user turn; cost ≈ the previous answer's length in prompt tokens (fast).
9. **Importing temporarily needs 2× storage** (the downloaded file + the app's private copy). Delete the download afterwards.
10. **Only one model is kept**; importing another deletes the previous copy.

## Performance / memory
11. **KV cache is f16**: ≈224 MiB at 2048 ctx, ≈448 MiB at 4096 for Qwen3-0.6B. On 3–4 GB phones keep 2048.
    KV quantisation (q8_0 + flash attention) is Phase 2.
12. **Thread recommendation is heuristic** (cores faster than the slowest cluster). Some SoCs report identical max-freq for mid and
    big cores; then all "fast" cores are used. Override with −/+ on the Model screen.
13. **Debug APK is ~29 MB**, mostly un-minified Compose dex (~23 MB uncompressed) plus 7 CPU-variant libraries (~5.4 MB stripped). Release
    builds with R8 will be much smaller.

## Cosmetic / logs
14. On Stop, logcat shows `graph_compute: ggml_backend_sched_graph_compute_async failed with error 1` and
    `llama_decode: failed to decode, ret = 2`. That is the expected abort path, not a crash.
15. The merged manifest contains androidx.core's signature-level `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`. It is not a network
    permission and is invisible to users.
16. No x86_64 ABI → the APK does not install on a typical x86_64 emulator (intentional; see DECISIONS D3).
