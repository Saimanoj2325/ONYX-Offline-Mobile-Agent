# Architecture (Phase 2)

> Phase 5 adds the multi-agent ONYX runtime (`:core:agents`, `app/onyx`, `ui/onyx`) on top of this: see docs/PHASE5.md.

Phase 1 was the on-device inference layer. Phase 2 adds persistence, rich Markdown, the Intelligent-UI **Canvas**, local
RAG, skills + an agent loop with tools, and image input. Everything still runs on the phone with **no INTERNET permission**.

```
┌──────────────────────────────── :app (Compose M3) ─────────────────────────────────┐
│ MainActivity (5 tabs + approval dialog + share/calendar intents)                    │
│ Chat ─ ChatViewModel ─ MarkdownView · Canvas (UiComponents, CanvasPanel, charts)     │
│ Docs (DocumentIndexer, Retriever) · Models (ModelStore, EngineController) · Settings │
│ AgentService (runs, approvals, resume) · Ports: DocumentPort, CanvasPort, Memory…    │
│ OfflineAgentApp = manual DI root                                                    │
└──────┬─────────────────────┬───────────────────────────┬────────────────────────────┘
       │                     │ :feature:workflow         │
       │                     │ SkillsScreen, RunsScreen  │
┌──────▼──────── :core:data (Android lib) ──────────────▼─┐
│ Room: chats, messages(+ui json), canvas_versions, documents, chunks(+vectors), chunks_fts │
│       (FTS4), skills, agent_runs, agent_steps, memory, notes, models · DataStore settings  │
│ RoomRunStore (agent checkpoints)                                                        │
└──────┬───────────────────────────────────────────────────────────────────────────────┘
┌──────▼──────────────── :core:runtime (pure Kotlin/JVM, unit-tested) ───────────────────┐
│ schema/  Schema (typed JSON-schema subset) → Gbnf (llama.cpp grammar) + validate()       │
│ ui/      UiSchema v1, UiParser (validate/repair/fallback), UiSerializer, UiMarkdown,     │
│          CanvasRouter (auto mode), CanvasPrompts, PartialJson (streaming preview)        │
│ markdown/ Markdown parser (blocks+inlines), MathText (TeX→Unicode), SyntaxHighlighter    │
│ rag/     Chunker, VectorMath, KeywordScorer (BM25-ish), HybridRanker, RagPrompt          │
│ skills/  SkillParser (front matter), SkillMatcher, BuiltinSkills (resources/skills/*.md) │
│ tools/   ToolSpec/Tool/ToolRegistry, PolicyEngine, built-in tools, Calculator            │
│ agent/   AgentLoop (plan→act→validate→policy→execute→observe, checkpoints), InferenceAgentLlm │
└──────┬───────────────────────────────────────────────────────────────────────────────┘
┌──────▼──────── :core:inference (pure Kotlin) ───────┐   ┌──── :engine:llamacpp ─────────────────┐
│ InferenceAdapter/TokenEngine, EngineInferenceAdapter │◀──│ LlamaCppEngine, LlamaEmbeddingEngine    │
│ (grammar + images in GenerationRequest), templates,  │   │ llama_jni.cpp → llama.cpp b11609 + mtmd │
│ EmbeddingService/EmbeddingEngine                     │   │ (grammar sampler, embeddings, images)   │
└──────────────────────────────────────────────────────┘   └─────────────────────────────────────────┘
```

Rules: nothing above `:core:inference` imports an `:engine:*` module except the DI root and `DeviceInfo`. `:core:runtime`
has no Android dependency (all logic JVM-testable). The LLM **proposes**; Kotlin **validates and executes**.

## Native layer (`engine/llamacpp/src/main/cpp/llama_jni.cpp`)

| function | what |
|---|---|
| `nativeBegin(…, grammar, images, w, h)` | sampler chain + optional **GBNF grammar** sampler; text prompts reuse the KV prefix; prompts with images go through **libmtmd** (`mtmd_tokenize` + `mtmd_helper_eval_chunks`), after which the KV is marked dirty (next turn re-evaluates). |
| `sample_token` | samples normally, checks the token against the grammar (cheap), and only on rejection masks the whole vocab with the grammar and resamples (llama.cpp `common` trick). |
| `nativeAttachMmproj` | loads the vision projector for the loaded model (`mtmd_init_from_file`), `image_max_tokens = 512` (phone budget). |
| `nativeApplyTemplate` | llama.cpp built-in chat templates for non-ChatML models (e.g. SmolVLM). |
| `nativeLoadEmbedder / nativeEmbed` | separate model + context with `embeddings=true`, model's own pooling, L2-normalised output. |

Threads: `llm-engine` (chat/vision, single thread, all JNI), `embed-engine` (embeddings), ggml worker threads.

## Intelligent UI / Canvas

1. **Routing** (`CanvasRouter`): composer toggle Auto / Chat / Canvas. Auto = deterministic keyword rules (table, chart,
   compare, plan, checklist, quiz…) and “edit verbs” when the chat already has a canvas (→ update in place).
2. **Generation**: system prompt lists the component catalogue (`UiSchema.promptCatalogue`), user prompt carries the
   request (+ RAG passages, + current canvas JSON for updates). Sampling uses the grammar from
   `Gbnf.of(UiSchema.docSchema())`: the model can only emit `{"v":1,"title":…,"blocks":[…]}` with known block types,
   exact property names, enum values and bounded array sizes. Temperature 0.3, no repeat penalty.
3. **Streaming**: every ~300 ms `PartialJson.close()` cuts the prefix back to the last closed object and the canvas shows
   finished components while the rest streams.
4. **Validation**: `UiParser` (trusted) validates each block against its schema, repairs safe things (ragged table rows,
   chart series length, quiz answer index, missing simple fields), drops bad blocks with warnings, and returns
   `Failure(fallbackMarkdown)` if nothing usable is left → shown as normal markdown.
5. **Persistence + versions**: each successful canvas is a new row in `canvas_versions`; `chats.canvasVersion` points at the
   current one. Undo/redo/history move the pointer; follow-ups create the next version from the *current* one.

Components (v1): markdown, heading, callout, keyvalue, table, list, checklist, steps (timeline), chart (bar/line/pie drawn
with Compose `Canvas` + `TextMeasurer`), code (highlighted, copy), flashcards (flip), quiz (scored), image (only photos the
user attached, `attachment:N`), comparison grid, actions (buttons that send a follow-up prompt).

## Markdown

`core/runtime/markdown/Markdown.kt` parses headings, paragraphs, emphasis/strike, inline code, links (rendered, never
opened), `[n]` citations (tappable), lists (nested, tasks), GFM tables (alignment), block quotes, fenced code (unterminated
fences render as code while streaming), rules, `$…$`/`$$…$$` math (TeX → Unicode). The chat re-parses the text at most
20×/s while streaming (≈<1 ms per parse).

## Local RAG

Import (SAF, multi-select) → text (PdfBox-Android for PDF page by page; UTF-8 for txt/md) → `Chunker` (≈900 chars, 150
overlap, paragraph/sentence boundaries) → embeddings (active embedding model, `embed-engine` thread) → Room (`chunks.embedding`
BLOB float32 LE) + FTS4 via Room triggers. Query: embed query → brute-force cosine over READY chunks (fine for a few
thousand) blended 0.7/0.3 with keyword scores (`HybridRanker`); without an embedding model, FTS4 keyword search only.
Top passages are injected as `[n] (title) text`; the answer's `[n]` become tappable source chips showing the passage.
Indexing reports progress and can be cancelled (partial rows removed).

## Skills + agent

A **skill** is markdown with front matter (`id, name, description, triggers, tools, output, max_steps`) + instructions.
Five built-ins ship in `core/runtime/src/main/resources/skills/`; users create/edit/import/enable/delete in the Skills tab
(validated by `SkillParser`; unknown tools/components are dropped with warnings).

`AgentLoop`:
1. **Plan** (grammar `{"steps":[…≤5]}`) → shown in the chat card and stored as step 0.
2. **Act**: each iteration samples one JSON action under a grammar that is a `oneOf` of `{thought, tool:"<name>", args:<that
   tool's exact schema>}` for every *allowed* tool, plus `{thought, final}`. Until the first successful tool call, if the
   plan/request names a tool, `final` is removed from the grammar (small models otherwise skip tools).
3. **Validate** args with the same `Schema` (ranges, lengths, enums, unknown keys) → **Policy** (`PolicyEngine`):
   read-only/UI → allow; WRITE_LOCAL (notes, memory) → ask unless “always allow”; EXTERNAL_INTENT (share sheet, calendar
   insert) → ask every time; skill allow-list; side-effect budget 3/run. Approval dialog shows tool, effect and arguments.
4. **Execute** with per-tool timeout → observation (truncated) → next iteration.
5. Bounds: max iterations (min(skill.max_steps, 6)), 2 consecutive errors (fail only if nothing useful happened),
   repeated identical call blocked, LLM timeout, coroutine cancellation (Stop button). A UI skill finishes when its
   `render_ui` succeeds; if the model never renders, a **compose** step builds the canvas from the observations.
6. **Checkpoint** after every step (`RoomRunStore`, one transaction). On restart the chat shows “Unfinished agent run —
   Resume / Discard”; resume continues from the saved iteration with the saved observations (no re-planning).

Tools: `search_docs`, `read_document`, `summarize`, `calculator`, `get_datetime`, `create_note`, `memory_read`,
`memory_write`, `render_ui`, `canvas_update`, `share_text`, `create_calendar_event`. There is **no** tool that opens URLs,
runs code or shell commands, or touches files outside the app.

## Multimodal

Input: photo picker (no permission) or camera via `ACTION_IMAGE_CAPTURE` + FileProvider (no CAMERA permission). Photos are
copied/downscaled into app storage, decoded to RGB ≤768 px and passed to `nativeBegin`; the vision model (role VISION, with
its mmproj) is loaded automatically when a message has a photo. Output: Canvas components (charts, tables, the attached
photo via `image` blocks). No on-device image generation.

## Persistence

`offline-agent.db` (Room, WAL). Chats/messages/canvas versions survive restarts; the last open chat is restored. Settings in
DataStore (`settings`). Model files in `filesDir/models/<role>/`, mmproj in `filesDir/models/mmproj/`, document text in
`filesDir/docs/`, photos in `filesDir/attachments/`. Schema JSON exported to `core/data/schemas/` (version 1).

## Memory & lifecycle

One chat/vision model in memory at a time (switching is automatic and costs a reload); the embedding model (tens of MB)
is separate. `onTrimMemory`/`onLowMemory` unload the models unless an agent run or indexing job is active. Agent runs and
indexing live in the application scope (they survive leaving the screen, not process death → resume).
