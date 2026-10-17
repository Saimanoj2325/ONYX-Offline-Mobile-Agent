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

---

## 1. One ChatGPT-style chat screen

The whole app is `ui/chat/ChatScreen.kt` (+ Settings from the drawer). The Phase 1–3 bottom tabs (Chat / Docs / Skills /
Runs / Settings) are gone; their screens open as bottom sheets from the chat.

| Part | What it does |
|---|---|
| Drawer | Search (all words, accent-insensitive), **New chat**, conversations grouped Today / Yesterday / Previous 7 days / Older, ⋮ → Rename / Delete (with confirm), **Settings** at the bottom. |
| Top bar | **Model picker**: installed chat models (radio = active; picking one reloads in the background) + **Download models…**; status line (Ready · tokens used/context, Loading, Failed). New-chat icon; ⋮ = Thinking, Use my documents, Export chat as PDF, Rename. |
| Thread | User messages: right-aligned bubbles (long-press → Copy / **Edit & resend**). Assistant: full-width, Markdown + **OpenUI components inline**, collapsible **Thought** section for Qwen3 `<think>`, Copy / **Regenerate**, stats. Streaming with live OpenUI rendering, auto-scroll only while you're at the bottom. |
| Inline cards | Agent runs (plan, tool calls with in/out, live status), **approvals** (Allow once / Always / Deny — no more dialogs), Canvas (OpenUI, version label, Undo/Redo, Export PDF), PDF report results (Open PDF preview), automation & macro runs (result + action log). |
| Empty state | Greeting by time of day + 4 suggestion chips. |
| Composer | Multiline, IME-aware, Send / **Stop**; **+** → Photo, Camera, Document (RAG); **Tools** chip → mode: Chat, Agent, Canvas, PDF report, Phone actions, Automation; plus Documents / Skills / Agent runs / Macros / Models sheets and "Run a skill". Edit-and-resend banner. |
| Safety banners | "Last run crashed — safe mode", self-test / compatibility-kernel messages with Share log / Restart / Exit safe mode. |
| First launch | If no chat model is installed, an **onboarding sheet** recommends one by RAM (Qwen3-1.7B Q4_K_M on ≥ 5 GB, Qwen3-0.6B below) with one **Download** button and progress. |

Pure logic (search, grouping, edit/regenerate targets, auto-scroll rule, streaming reducer, modes, suggestions) is in
`core/runtime/chat/ChatLogic.kt` and unit-tested.

## 2. OpenUI rendering

[OpenUI](https://www.openui.com) (thesysdev/openui, **MIT licence**) defines *OpenUI Lang*, a compact line-oriented
language (`identifier = Component(arg, …)`, positional args, forward references) that models write instead of JSON. We
implemented it natively — no WebView, no JS:

* `:core:openui` (pure Kotlin, JVM-tested)
  * `Parser.kt` — tolerant recursive-descent parser + **streaming parser**: complete statements are kept, the trailing
    partial statement is parsed with open strings/arrays/calls closed virtually; multi-line statements; named-argument
    and alias tolerance for small models; never throws.
  * `Library.kt` — the component library with OpenUI's default names, positional prop order and enum values:
    Stack, Card, CardHeader, TextContent, MarkDownRenderer, Callout, TextCallout, Image, ImageBlock, CodeBlock, Separator,
    Table/Col, BarChart, LineChart, AreaChart, HorizontalBarChart, Series, PieChart/Slice, Tabs/TabItem,
    Accordion/AccordionItem, Steps/StepsItem, Form, FormControl, Input, TextArea, Select/SelectItem, Slider,
    CheckBoxGroup/CheckBoxItem, RadioGroup/RadioItem, SwitchGroup/SwitchItem, Button, Buttons, FollowUpBlock/FollowUpItem,
    ListBlock/ListItem, TagBlock, Tag. `compact()` = core subset for small models.
  * `Resolver.kt` — OpenUI semantics: entry `root` (else first Stack), last definition wins, unreferenced statements not
    rendered, defaults for wrong optional props, missing-required drops the component (kept while streaming), unknown
    components and broken statements **degrade to text**, cycles reported, actions `@ToAssistant`, `@OpenUrl`,
    `Action([...])` and the string forms `"submit:form"` / `"action:x"`.
  * `Message.kt` — splits a reply into Markdown and ```` ```openui-lang ```` blocks (OpenUI "inline mode"); a whole-reply
    program is detected too; plain Markdown when there is no OpenUI. Text view for copy/PDF/history.
  * `Forms.kt` — field model, OpenUI validation rules (required, email, min/max, minLength/maxLength, pattern, url,
    numeric), submit → next user turn (`Form submitted: …`, passwords masked) and parse back.
  * `Prompt.kt` — **system-prompt generator** like OpenUI's `library.prompt()`: syntax rules, signatures grouped by
    category (`Name(prop: type, opt?: "a" | "b") — description`), streaming rules, examples (only with available
    components), inline-mode rules; PROGRAM mode for the Canvas.
  * `Grammar.kt` — llama.cpp **GBNF** for whole programs (root first, library component names only, balanced syntax).
* `app/ui/openui/OpenUiView.kt` — Compose renderer (Material 3, dark/light): layouts, cards, text/markdown, callouts,
  code with highlighting, tables, **bar/line/area/horizontal-bar/pie/donut charts drawn on Canvas**, tabs, accordion,
  steps, list blocks, tags, follow-up chips, buttons, **forms** (inputs, select, checkboxes, radio, switches, slider;
  validation; submit sends values as the next user message), images (`attachment:N` = your photo; remote images are
  never fetched).
* Chat mode: the system prompt contains the generated OpenUI section (Auto = full library for ≥ 3B models, core
  subset for smaller ones; Settings → Performance → Rich answers: Auto / All / Core / Off). Older answers are sent back
  to the model as their visible text to save tokens.
* Canvas mode: the model writes one program under the GBNF grammar; edits send the current program and merge the new
  statements (OpenUI incremental editing: last definition wins). Every result is a canvas **version** (undo/redo).
  Old Phase 2/3 JSON canvases and the agent's `render_ui` output are bridged to OpenUI (`OpenUiBridge`) and render
  with the same renderer.
* Not done: OpenUI reactive state (`$vars`, `@Set`/`@Reset` only parsed), Query/Mutation, `@Each`; llama.cpp **lazy**
  grammar for inline blocks (the JNI exposes only full grammars) — inline OpenUI is validated by the tolerant parser.

## 3. In-app model downloader

* `:core:models` (pure JVM): curated catalogue (Qwen3-1.7B Q4_K_M default, Qwen3-4B Q4_K_M, Qwen3-0.6B Q8_0,
  bge-small-en-v1.5 q8_0, SmolVLM2-256M + mmproj, Qwen3-VL-2B + mmproj), `https://huggingface.co/<repo>/resolve/main/<file>`
  URLs, pasted-link parser (`/resolve/` or `/blob/`, huggingface.co or hf.co), and `Downloader`: manual redirects with
  every hop checked against the allow-list (**HTTPS to huggingface.co / *.hf.co only**), HEAD for `X-Linked-Size` and
  `X-Linked-Etag` (SHA-256), resume via HTTP Range (206 append / 200 restart / 416 complete), retries, free-space check,
  GGUF magic + size + SHA-256 verification, cancel keeps the `.part` for resuming.
* App: `models/ModelDownloads.kt` — WorkManager foreground job (dataSync) with a progress notification and Cancel,
  live progress in the models sheet / onboarding / a strip under the top bar; when done the model (and projector) is
  registered and activated. Existing file import stays (Models sheet → "Import a .gguf file").
* **Network policy**: the offline flavor now has `android.permission.INTERNET`, used **only** by this downloader.
  Inference, chats, documents, tools and MCP (binder) stay on the phone; no telemetry, no cloud fallback. The connected
  flavor additionally keeps the LAN/remote MCP transport.

## 4. Phase 3 features kept

Long context (now opt-in above 8K), prompt cache, summaries + long-term memory, PDF tool and report skill, MCP (built-in,
binder, connected-flavor HTTP), phone tools with approvals (now inline cards), autofill + accessibility automation with
the same safety rules, macros (now runnable from the chat), skills, agent runs and resume.

## 5. Licences

OpenUI Lang syntax, component names and prompt format: thesysdev/openui, MIT, © Thesys Inc. — no OpenUI code is
bundled; this is an independent Kotlin implementation. llama.cpp MIT. Models are downloaded by the user from
Hugging Face under their own licences (Apache-2.0 / MIT for the catalogue).

---

## Phase 4b — fast first token, live streaming, reload ANR fix

### What Sai reported (OnePlus CPH2569, Android 15, 8 GB, NEON/FP16/DOTPROD, no SVE/SME)

* Good: Qwen3-4B Q4_K_M loads at 8K / f16 KV / 4 threads on CPUs 4–7 and the self-test passes ("5" in 1.7 s) — the
  KleidiAI fix works on this phone.
* "It is taking too much time for response… I want its stream of thinking and answering."
* An **ANR** at 11:56:37 on a model reload: the main thread was stuck in `GgufMetadata.read → Reader.u64/le →
  RandomAccessFile.readFully`, called from `EngineController.config → archOf` inside a coroutine on `Dispatchers.Main`.

### Diagnosis

| Suspect | Finding |
|---|---|
| Long system prompt | **Main cause of the slow first token.** Chat mode put the generated OpenUI library in every system prompt: **2 163 tokens** (full library, chosen by Auto for ≥ 3B models such as Qwen3-4B) or 1 760 (compact) — measured with the real Qwen3 tokenizer. On a phone CPU all of it is prefilled before the first token. |
| Self-test before every reply | No — it runs once per model load (`EngineController.loadChecked`); `ensure()` only reloads after a settings change. Kept that way. |
| Prompt/KV cache between turns | Already reused (native `eval_text` keeps the common token prefix; the system prompt is also cached on disk). Kept, plus memory pages are now capped (below). |
| UI buffering | Tokens reached the UI (≤ 20 updates/s), but: the Thought section was **collapsed** while the model thought (so a thinking answer looked frozen), there was no prompt-progress, a partial OpenUI block showed only "Building the answer…", and the footer had no time-to-first-token. |
| Agent / Canvas / PDF / Automation | `InferenceAgentLlm` collected the whole completion and returned it at the end — agent steps, report sections, summaries and the screen agent showed nothing until each call finished. Canvas streamed. |
| Thinking default | **Off** (and saved as off for everyone). |
| Threads / batch | Threads: already performance cores only (4 on CPUs 4–7 on this phone), unchanged. Batch: 512/512 → 256/256 (measured below). |
| Main thread | **Blocked** by the GGUF header read on reload (the ANR), plus a few small file reads in Compose. |
| Long-term memory | Up to 4 memory pages (15 % of the window, ~1 200 tokens at 8K) were added to every new user message — prefilled again each turn. |

### Fixes

| Area | Change |
|---|---|
| **ANR (P0)** | `GgufMetadata` rewritten: 64 KB buffered reads over a `FileChannel` (big skips reposition the channel), **stops as soon as the needed keys are known** (before the 151 k-entry tokenizer arrays), cached per **path + size + mtime**. `EngineController.ensure/load/config/archOf/planFor` run on **`Dispatchers.IO`**; `planWithoutModel()` for UI code that must not read files. `DeviceInfo` caches /proc and sysfs. Diagnostics log lines are written on a background thread. Other main-thread file reads found in the audit moved to IO (canvas image decode, crash-report list/viewer, skill import, camera file check). **StrictMode** (debug builds): disk/network/slow calls on the main thread are logged to logcat and the first 30 to the diagnostics log. |
| Compact chat prompt | Chat mode now sends a **321-token** system prompt: the base instructions + a short OpenUI cheat sheet (names and argument order checked against the library by a unit test). The full library is used only in Canvas mode, when the message asks for UI (chart, form, dashboard, …), or with Settings → Rich answers → All. Document passages (RAG) moved from the system prompt into the user turn so the system prompt — and its KV cache — stays identical turn after turn. |
| Prompt progress | Native atomics `pf_done/pf_total/pf_cached` updated per batch in `eval_text`; `nativePrefillProgress()`; the adapter polls it every 200 ms during prompt evaluation and emits `GenerationEvent.PromptProgress` → **"Reading prompt… n/N"** with a progress bar. |
| Streaming reducer | `core/runtime/chat/StreamingTurn.kt` (pure, tested): status → prompt progress → **live reasoning (Thought section auto-expanded, shows the latest text)** → **collapses when the answer starts** → answer token by token. Footer: **tokens · tok/s · first token X s** (saved message adds "prompt N tok (M cached)"). Never a blank bubble: a status line is shown whenever no text is visible. |
| Think-splitting | Half-written `</think` is held back while streaming (never flashes); templates that pre-fill `<think>` (only `</think>` arrives) are split correctly; the final split holds nothing back. |
| OpenUI while streaming | `OpenUiStreaming.view()`: a block renders as components as soon as the partial program resolves; until then its **text streams as-is** ("Building UI…"); a finished block that cannot be resolved degrades to code. Markdown before/after a block streams immediately. |
| Agent / PDF / Automation | `InferenceAgentLlm(onPartial, onPrompt)`: agent steps stream their text into the agent card (and "Reading prompt… n/N" in its status), report sections stream into the bubble, the screen agent's output streams into the automation card. Phone-actions mode uses the agent path. |
| Thinking | **Default ON** (settings schema v5 migrates the old saved "off"), Qwen3 thinking sampling (T 0.6, top-p 0.95, top-k 20). Settings → Answers & memory → **Thinking**, plus a **Think: on/off** chip in the composer for the next message only. |
| Memory pages | At most 2 pages / 320 tokens per message (`ContextConfig.memoryMaxTokens`). |
| Kept | All P0 fixes: KleidiAI off, 8K/f16 defaults, self-test once per load, safe mode, compatibility kernels, crash markers/log, duplicate-send guard, prompt-cache validation (file key unchanged, native token check). |

### Measured (sandbox ARM64 host, 2 threads, Qwen3-1.7B Q8_0, 8K f16 KV, batch 512 for every row; `docs/verification/phase4b-host-harness.txt`)

| | Phase 4 (full library — Auto on 4B) | Phase 4 (compact — Auto < 3B) | **Phase 4b** |
|---|---|---|---|
| System prompt tokens | 2 163 | 1 760 | **321** |
| First message after load + self-test, no disk cache: time to first token | 45.0 s | 35.3 s | **3.7 s** |
| Second turn (KV prefix reuse): time to first token | 2.5 s | 1.7 s | **0.9 s** |
| Thinking ON, system prompt from disk cache: first visible (reasoning) token | — | — | **0.8 s** |

Prefill batch (same ~2 190-token prompt): 128 → 41.8 tok/s, **256 → 70.2 tok/s**, 512 → 51.2 tok/s, so the default is
now **256/256** (migrated from the old 512/512 default; a hand-picked value is kept; safe mode unchanged). Smaller
batches also make "Reading prompt… n/N" move in finer steps.

GGUF header (1.8 GB Qwen3-1.7B file on the host): old reader 320–337 ms (the phone's ANR trace shows it is far slower
there, on the main thread), new `arch()` 13–121 ms cold, **17–33 µs cached**.

The phone prefill rate is not measurable here; the first-token wait scales with the prompt tokens, so expect roughly
the same ~6–12× reduction on the first message of a chat (fewer tokens to prefill) and live text from then on.

### Phase 4b build checks

* JVM tests: **206/206 pass** (188 before + 18 new: GGUF early stop/cache/skip/truncation, think-splitting while
  streaming, prefill-progress events, streaming reducer, throttle, footer, prompt budget and level choice, memory cap,
  progressive OpenUI fallback, cheat-sheet ↔ library consistency).
* Host harness (`runPhase4b`): all checks passed (see `docs/verification/phase4b-host-harness.txt`).
* APK permissions (parsed from the binary manifests with Python): offline = INTERNET, no ACCESS_NETWORK_STATE;
  connected = INTERNET + ACCESS_NETWORK_STATE. No KleidiAI symbols in any `libggml-cpu-android_*.so`.
* Not verified here: anything on the phone itself (real prefill speed, the ANR fix under load, Compose rendering).
