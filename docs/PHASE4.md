# Phase 4

Phase 4 starts with a **P0 fix** for problems seen on Sai's phone with the Phase 3 offline APK, then continues with the
ChatGPT-style single-chat UI, OpenUI rendering and the in-app model downloader (sections further below).

## P0 — garbage output, crashes, settings (fixed first)

### What was reported (real device, Phase 3 offline APK)

* Qwen3-1.7B Q8_0 (and an older model) loaded, but every answer was garbage: `oughtought…`, `THTHTH…`,
  `ações / ités` repeated, at ~3.7 tok/s. Header: `235/32.8K tokens` → Auto had picked a **32K** window.
* The same prompt appeared twice (duplicate user bubble).
* After changing Settings (context 8K, KV f16) / reloading, the app crashed; Settings could not be changed at all.

### Root cause (what we know, and how sure we are)

Nothing in the sandbox can run the exact Android binaries on the exact phone CPU, so this is an evidence-based
diagnosis, not a reproduction:

1. **KleidiAI micro-kernels were active on the phone but never on the test host — most likely cause of the garbage
   and the low speed.** Phase 3 built llama.cpp with `GGML_CPU_KLEIDIAI=ON` (vendored, pruned to the files ggml
   references). KleidiAI picks its matmul kernels **at runtime from the CPU's features**, independently of which
   `libggml-cpu-android_armv*.so` variant is loaded. On an SME-capable phone it routes Q8_0/F32 matmuls to the SME
   kernels; the log line `kleidiai: SME detected; SMCU count unavailable, using conservative SME thread cap=1` shows
   it then runs them on **one thread**, which matches 3.7 tok/s for a 1.7B model on a 12 GB flagship. The Phase 3 host
   harness CPU (sandbox: NEON + dotprod + i8mm, **no SVE/SME**) only ever exercised the I8MM kernels, so the SME/SVE
   paths were untested. Both models failing the same way points at a shared kernel path, not at one model file.
2. **Auto chose 32K context + quantised KV + forced flash attention** on a ≥ 10.5 GB phone (Phase 3 "XL" tier).
   That is the least-tested llama.cpp configuration on phones and multiplies memory use; switching it to f16 32K
   from Settings needs ~3.5 GB of KV on top of the weights, which would get the app killed while loading (the crash).
3. **Fragile failure handling**: a failed load or a background job exception could take the app down; settings and the
   prompt-cache directory had no fail-safe; the "Prompt cache" switch in Settings was not wired to anything.
4. **Duplicate send**: not reproduced in code review (the send path checks `busy`), but nothing prevented two sends
   arriving before the first coroutine registered; now guarded (below).

What we checked in the sandbox (ARM64 host + `qemu-aarch64` emulating SVE 128-bit and SME, same llama.cpp b11609,
same CMake flags as the APK incl. `GGML_CPU_ALL_VARIANTS` + KleidiAI): greedy "capital of France" with Qwen3-0.6B and
1.7B Q8_0 gave correct answers on the native host (I8MM kernels, f16 and q8_0+FA, 512 – 32K context) and under
emulated SVE (`armv8.6_2` variant) and SME (`armv9.2_2` variant, KleidiAI SME q8/f32 kernels) at short context. The
emulated SME run at 32K context did not finish (see `docs/verification/phase4-p0.txt`). So the emulator does **not**
reproduce the garbage; real SME hardware (and its streaming vector length) differs from QEMU. That is why the fix
removes the unverified paths instead of trying to patch them.

### Fixes

| Area | Change |
|---|---|
| Native build | **`GGML_CPU_KLEIDIAI=OFF`** (APK and host harness). The CPU variants now use plain ggml-cpu kernels + weight repack — llama.cpp's upstream-default Android configuration. KleidiAI symbols are gone from every `libggml-cpu-android_*.so` (checked with `grep -a kleidiai`). |
| Runtime CPU fallback | `nativeInit(libDir, cpuVariant)`: a **compatibility mode** loads only `libggml-cpu-android_armv8.2_1.so` (NEON + dotprod, no SVE/SME/i8mm). Chosen automatically when the self-test still fails in safe mode (takes effect after a restart; banner has "Restart now"), or by hand in Settings → Diagnostics. |
| Defaults | **8K context + f16 KV** on 8 GB+ phones (4K on ≤ 6 GB). **16K/32K/64K/128K are opt-in** ("Allow large context"). Auto KV is always f16; q8_0/q4_0 only by explicit choice. Before every load the window is lowered until weights + KV + buffers fit the RAM budget (`ContextPlanner.fitContext`). |
| Settings migration | Schema v4: context > 8K → Auto, KV/flash attention → Auto, speculative/YaRN off, large-context off; all Phase 3 prompt-cache files deleted. Corrupted settings files are replaced by defaults (`ReplaceFileCorruptionHandler`); wrong-typed values read as defaults; saving never throws. |
| Self-test | After every model load: greedy `What is 2 + 3? Reply with only the number.` (≤ 12 tokens, no prompt cache). Degenerate output (repeated 1–10-char patterns, repeated words, mostly non-ASCII, empty, invalid UTF-8) → prompt cache cleared, **safe mode** on, reload, test again; still broken → compatibility CPU kernels for the next start. A sane-but-wrong answer (tiny models) is not treated as broken. |
| Safe mode | 4K context, f16 KV, flash attention off, no prompt cache (memory or disk), no draft model, ≤ 4 threads, no core pinning, batch 512. Banner in chat with "Exit safe mode". |
| Prompt cache | Settings switch now really turns it off. File key includes cache format version, model path/size/mtime, context, KV type, flash attention, batch, rope, CPU variant and the prefix text; the native side keeps validating the saved token list and deletes mismatches; any error now deletes the file too. |
| Crash safety | Uncaught-exception handler writes a crash report; a **native marker** file brackets every model load and self-test, so a native crash or OOM kill there is detected on the next start; on Android 11+ the system's last exit reason (crash / native crash / ANR / low memory) is read too. Next launch → **safe mode, no automatic model load, banner "Last run crashed" with Share crash log**. The app-wide coroutine scope has an exception handler (a failing background job can no longer crash the app). Every model load error is shown as a message. |
| Settings | Opens instantly with or without a model (GGUF header reads moved off the main thread). Context / KV / flash-attention / batch / YaRN / large-context changes are saved and then applied by **unload + reload + self-test in the background**; while an answer is being generated the reload is deferred to the next request. |
| Diagnostics | Settings → **Diagnostics & crash logs**: self-test result, safe mode switch, compatibility-kernels switch, system info (CPU features, CPU variant), crash report viewer, diagnostics log, **Export & share** (one text file incl. this app's own logcat; nothing leaves the phone unless the user shares it), clear prompt cache. |
| Duplicate send | `send()` sets a `sending` flag synchronously before launching, and ignores an identical text within 1.5 s. |

### What Sai should do

1. Install the Phase 4 APK over the old one (settings migrate automatically, old caches are deleted).
2. Send a message. If the chat shows a safe-mode or self-test banner, use **Share log** and send us the file.
3. If answers are fine, optionally re-enable bigger windows in Settings → Performance ("Allow large context").
