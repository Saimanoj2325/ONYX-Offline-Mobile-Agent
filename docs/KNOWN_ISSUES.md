# Known issues & limitations (Phase 2)

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
15. Agent runs live in the application scope, not a foreground service: if Android kills the process mid-run, the run is
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
24. Default context is now **4096** (Canvas and agent prompts are long): KV ≈ 448 MiB for Qwen3-0.6B/1.7B, ≈ 576 MiB for 4B.
    On 4 GB phones choose 2048 in Models → Settings.
25. Debug APK is ~48 MB (un-minified Compose + Room + PdfBox resources + 7 CPU variants + libmtmd). Release with R8 will be much smaller.
26. PdfBox-Android is pure Java: a 300-page PDF can take a minute to extract on a mid-range phone (progress + cancel shown).

## Cosmetic / logs
27. On Stop, logcat shows `ggml_backend_sched_graph_compute_async failed with error 1` / `llama_decode: failed to decode, ret = 2`
    — the expected abort path.
28. SmolVLM2 logs `control-looking token … was not control-type` at load — harmless model metadata warning.
29. Links in answers are rendered but never opened (no browser intents); tapping copies the URL.
