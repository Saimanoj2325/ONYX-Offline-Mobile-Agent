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
