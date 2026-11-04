# Phase 6 — device-adaptive runtime (M1) and long memory (M2)

Status: **built and unit-tested in the sandbox; not yet run on a phone** (target: OnePlus CPH2569, Snapdragon 782G, 12 GB RAM).
Version: `0.6.0-phase6` (versionCode 6).

| Milestone | Commit | What |
|---|---|---|
| M1 | `fdc1cef` | Device tiers, new catalogue entries, n-gram speculation, power governor, on-phone benchmark, memory engine (pure Kotlin) |
| M2 | this phase | Long memory wired into the app: saved memories, chat-history reference, threads, routines, frequent contacts, temporary chat |

---

AppBridge (app intents, notifications, quick ask): see [PHASE6-APPS.md](PHASE6-APPS.md).

## M1 — device-adaptive model runtime

### 1. Tiers (`:core:agents` `RuntimeSelector`)

The tier comes from **total** RAM (what class of phone it is); the model picks are then filtered by **free** RAM right now
(`ModelFit`, `ActivityManager.MemoryInfo.availMem`), so a 12 GB phone with 3 GB free gets the same safe default as an 8 GB phone.

| Tier | Total RAM | Context | Candidates (first that fits = primary) |
|---|---|---|---|
| ENTRY | < 4.8 GB or `isLowRamDevice` | 2K | Qwen3 0.6B Q8, Qwen3.5 0.8B, LFM2.5 1.2B, Gemma 3 1B |
| MID | 4.8–6.8 GB | 4K | Qwen3 1.7B, LFM2.5 1.2B, Qwen3.5 2B |
| HIGH | 6.8–10.5 GB | 8K | Qwen3 1.7B, Qwen3.5 2B, Qwen3 4B Instruct 2507, Gemma 4 E2B |
| FLAGSHIP | ≥ 10.5 GB | 8K | Qwen3 1.7B, Qwen3 4B Instruct 2507, LFM2.5 8B-A1B (MoE), Qwen3.5 4B, Gemma 4 E2B |

- Default on every tier ≥ 6 GB: **Qwen3 1.7B Q4_K_M** (~2.2 GB resident at 8K).
- Free RAM < 2 GB caps context at 4K; < 1.2 GB forces 2K and the smallest model.
- No dot-product CPU feature → small models only. Bigger models are hidden (with a note) until enough RAM is free; a
  hand-picked model gets a warn-before-load message.
- Downloads are blocked if they would leave < 1.5 GB of storage; partial downloads resume.

### 2. Catalogue entries added in `fdc1cef` (`core/models/.../Catalog.kt`)

Exact byte sizes from the Hugging Face tree API (verified 2026-11-03):

| Id | Model | File | Bytes | Notes |
|---|---|---|---|---|
| `qwen3.5-0.8b` | Qwen3.5 0.8B | `unsloth/Qwen3.5-0.8B-GGUF` · `Qwen3.5-0.8B-Q4_0.gguf` | 507 154 688 | hybrid, TINY |
| `lfm2.5-1.2b` | LFM2.5 1.2B Instruct | `LiquidAI/LFM2.5-1.2B-Instruct-GGUF` · `LFM2.5-1.2B-Instruct-Q4_0.gguf` | 695 751 488 | hybrid, TINY |
| `qwen3.5-2b` | Qwen3.5 2B | `unsloth/Qwen3.5-2B-GGUF` · `Qwen3.5-2B-Q4_0.gguf` | 1 214 873 856 | hybrid, SMALL |
| `qwen3-4b-2507` | Qwen3 4B Instruct 2507 | `unsloth/Qwen3-4B-Instruct-2507-GGUF` · `Qwen3-4B-Instruct-2507-Q4_0.gguf` | 2 375 773 280 | MEDIUM |
| `qwen3.5-4b` | Qwen3.5 4B | `unsloth/Qwen3.5-4B-GGUF` · `Qwen3.5-4B-Q4_0.gguf` | 2 583 221 408 | hybrid, MEDIUM |
| `gemma4-e2b` | Gemma 4 E2B | `ggml-org/gemma-4-E2B-it-GGUF` · `gemma-4-E2B-it-Q4_0.gguf` | 2 841 481 184 | MEDIUM |
| `lfm2.5-8b-a1b` | LFM2.5 8B-A1B (MoE) | `LiquidAI/LFM2.5-8B-A1B-GGUF` · `LFM2.5-8B-A1B-Q4_0.gguf` | 4 844 678 368 | MoE + hybrid, LARGE |

Existing entries whose sizes were made exact in the same commit: Qwen3 1.7B Q4_K_M 1 107 409 472 B, Qwen3 0.6B Q8_0 639 446 688 B.
Every chat entry now also carries `kvBytesPerToken`, `moe`, `hybrid` and `sizeClass`.

### 3. N-gram self-speculation (JNI, `llama_jni.cpp`)

Prompt-lookup drafting from the context itself (no draft model): output is identical in design, works with GBNF grammars,
adaptive draft length, switches itself off on low acceptance, and is disabled for recurrent/hybrid models (KV rollback impossible).

Measured on the **2-vCPU sandbox host** (not a phone), host harness `Phase6Harness`:

| Model | Plain decode | Speed-up with speculation |
|---|---|---|
| Qwen3 0.6B Q8_0 | 39.8 tok/s | 0.94–2.68× |
| Qwen3 1.7B Q8_0 | 11.4 tok/s | 1.02–4.40× |

The gain depends on how much of the answer repeats the prompt (code edits and rewrites gain most; free-form chat little).
Phone numbers will differ; use the on-phone benchmark.

Also in M1: per-role static system prompts (planner/args) so their KV prefix is cached; prefix-cache files 8 → 16.

### 4. Power governor (`PowerGovernor`)

| Condition | Threads | Batch | Other |
|---|---|---|---|
| Normal | big cores (1–8) | 256 (128 on ENTRY) | speculation on |
| Thermal LIGHT | −1 | — | |
| Thermal MODERATE | ÷2 | ÷2 (≥ 64) | busy-poll off, speculation off |
| Thermal SEVERE+ | 1 | ≤ 64 | new model work paused |
| Battery saver | ≤ 2 | ≤ 128 | poll off, idle unload 2 min |
| Battery ≤ 15 % and not charging | ≤ 2 | ≤ 128 | poll off, idle unload 2 min |

Idle unload defaults to 10 minutes (configurable); `onTrimMemory` release is kept. Threads/poll can change live.

### 5. On-phone benchmark (Agents → Models → Benchmark)

Reloads the active model (load time), then measures prompt tok/s, generation tok/s with speculation off and on
(accepted/drafted), peak RSS, thermal state and battery. Results are stored in `onyx-agents.db` (`bench_results`, schema v2
via AutoMigration). Nothing leaves the phone.

---

## M2 — long memory

Everything is on-device in `onyx-agents.db`. Engine: `:core:agents` `memory/LongMemory.kt`, `memory/RoutineBook.kt`
(pure Kotlin, JVM-tested). App wiring: `app/onyx/OnyxMemory.kt`, `ChatViewModel`, `ChatScreen`, `ui/onyx/MemoryScreen.kt`.

### 1. Features

- **Saved memories.** Rule-based extraction turns first-person statements into third-person facts ("I live in Hyderabad" →
  "Lives in Hyderabad"). Questions, hypotheticals and other people's statements are skipped. A small model pass runs only
  when the rules find nothing, at BACKGROUND priority, and is skipped on battery < 30 % (not charging). Auto-saved items are
  marked *inferred* with a confidence until the user confirms them. Single-value slots (name, home, employer, role, diet,
  birthday) replace the old value; near-duplicates merge.
- **Explicit commands.** "remember that …" saves (works even when auto-save is off); "forget …" deletes matching memories
  and matching chat-history snippets. "remember to …" is treated as a reminder, not a memory.
- **"Memory updated" chip** under the user message that changed memory.
- **Reference chat history.** Every finished exchange of every normal chat is indexed; BM25 (k1 1.2, b 0.75) with a recency
  boost retrieves snippets from *other* chats for new chats and agent runs. BM25 was chosen over embeddings: no second model
  in RAM, < 10 ms for thousands of chunks, good with names and numbers, explainable.
- **Rolling summaries.** When older turns fall out of the context window they are folded into the chat's running summary
  (model at BACKGROUND priority, extractive fallback). The summary is indexed for cross-chat recall. **Each chat has one
  summary in the index: a new summary replaces the old one** (it already contains it).
- **Threads (Memory screen).** Lists the current summary of each chat (title + preview). *View* opens the full summary;
  *Delete* forgets the thread's summary and its indexed snippets. The chat itself stays.
- **Chat deletion.** Deleting a chat also deletes its indexed history, its summary and its chat-memory state.
- **Temporary chat.** Toggle in Chat (before or during a chat). A temporary chat neither reads nor writes saved memories,
  chat history, summaries index, routines or contacts.
- **Frequent contacts.** Learned only from approved call/message tool runs; ranked by frecency (14-day half-life); used to
  resolve "message Anu". Listed on the Memory screen with delete.
- **Routines.** A multi-step task that succeeded twice becomes a routine; the Planner reuses it without a planning call.
  Enable/disable, rename and delete on the Memory screen.
- **Controls.** Settings: *Use memory* (master), *Saved memories*, *Reference chat history* (each on its own). Memory screen:
  view/add/edit/pin/confirm/delete memories, export JSON, clear chat-history index, delete all memories, wipe ALL memory.

### 2. Token budget

Memory is injected as one block, built by `MemoryContext.build`, within a fixed token budget (≈ 4 characters per token):

| Caller | Budget |
|---|---|
| Chat, context ≤ 4096 | 200 tokens |
| Chat, context > 4096 | 320 tokens |
| `OnyxMemory.contextFor` default | 280 tokens |
| Agents (`profile`) | budget passed by the Planner; saved memories only |

Order inside the budget: up to 6 relevant saved memories first (pinned, confidence and recency boosts; inferred ones
labelled "(inferred)"), then up to 3 chat-history snippets from other chats (each ≤ 360 chars, only if ≥ 24 tokens remain).
Agent memory search returns up to 2 chat snippets alongside saved memories and documents. Temporary chats get an empty block.

### 3. Tests

`Phase6MemoryTest` (`:core:agents`) covers extraction, remember/forget, slots, BM25 history, temporary scope, contacts,
routines, the budgeted context block, plus M2: summary replacement, chat/thread deletion, temporary summaries not indexed.

### 4. Known gaps

See `docs/KNOWN_ISSUES.md` → Phase 6.
