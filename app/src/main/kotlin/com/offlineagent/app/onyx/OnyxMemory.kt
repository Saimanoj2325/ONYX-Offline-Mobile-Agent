package com.offlineagent.app.onyx

import com.offlineagent.app.OfflineAgentApp
import com.offlineagent.core.agents.AgentHooks
import com.offlineagent.core.agents.InferenceQueue
import com.offlineagent.core.agents.Risk
import com.offlineagent.core.agents.memory.ChatHistoryIndex
import com.offlineagent.core.agents.memory.ContactRanking
import com.offlineagent.core.agents.memory.Extracted
import com.offlineagent.core.agents.memory.MemoryBook
import com.offlineagent.core.agents.memory.MemoryContext
import com.offlineagent.core.agents.memory.MemoryExtractor
import com.offlineagent.core.agents.memory.MemoryKind
import com.offlineagent.core.agents.memory.MemoryScope
import com.offlineagent.core.agents.memory.MemorySettings
import com.offlineagent.core.agents.memory.MemoryUpdate
import com.offlineagent.core.agents.memory.RollingSummary
import com.offlineagent.core.agents.memory.RoutineBook
import com.offlineagent.core.data.ChatMemoryStateEntity
import com.offlineagent.core.data.RoomContactRepo
import com.offlineagent.core.data.RoomHistoryRepo
import com.offlineagent.core.data.RoomRoutineRepo
import com.offlineagent.core.data.RoomSavedMemoryRepo
import com.offlineagent.core.runtime.schema.Gbnf
import com.offlineagent.core.runtime.schema.Schema
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * Phase 6 M2 on the phone: ChatGPT-style long memory for Chat and the agents, entirely in onyx-agents.db.
 *  - saved memories ([book]): auto-extracted from every NORMAL chat message (rules; a small model pass only when the
 *    rules find nothing and the model is idle), explicit "remember / forget", a "Memory updated" chip per message;
 *  - reference chat history ([history]): BM25 over every finished exchange + rolling summaries of all chats;
 *  - rolling per-chat summaries ([summarize]);
 *  - temporary chats: [isTemporary] chats never read or write memory;
 *  - frequent contacts ([contacts]) learned from approved calls/messages; routines ([routines]) for the Planner.
 * Settings → (a) Saved memories and (b) Reference chat history can each be turned off; nothing leaves the device.
 */
class OnyxMemory(private val app: OfflineAgentApp, private val runtime: OnyxRuntime) {
    private val dao get() = runtime.dao
    private val p6 get() = runtime.db.phase6()
    val book by lazy { MemoryBook(RoomSavedMemoryRepo(dao)) }
    val history by lazy { ChatHistoryIndex(RoomHistoryRepo(p6)) }
    val contacts by lazy { ContactRanking(RoomContactRepo(p6)) }
    val routines by lazy { RoutineBook(RoomRoutineRepo(p6)) }

    /** "Memory updated" chips: chat message id → chip text (shown under that user message). */
    private val _chips = MutableStateFlow<Map<Long, String>>(emptyMap())
    val chips: StateFlow<Map<Long, String>> = _chips.asStateFlow()

    fun settings() = MemorySettings(app.cachedSettings.savedMemories && runtime.prefs.values.value.memoryEnabled,
        app.cachedSettings.referenceChatHistory && runtime.prefs.values.value.memoryEnabled)

    // ---------------------------------------------------------------- temporary chats
    private val temporary = java.util.concurrent.ConcurrentHashMap<Long, Boolean>()
    suspend fun isTemporary(chatId: Long): Boolean = temporary[chatId] ?: withContext(Dispatchers.IO) {
        (p6.chatState(chatId)?.temporary ?: false).also { temporary[chatId] = it }
    }
    suspend fun setTemporary(chatId: Long, on: Boolean) = withContext(Dispatchers.IO) {
        temporary[chatId] = on
        val s = p6.chatState(chatId)
        p6.upsertChatState(ChatMemoryStateEntity(chatId, s?.summary ?: "", s?.summarizedTurns ?: 0, on, System.currentTimeMillis()))
    }
    suspend fun scope(chatId: Long?) = if (chatId != null && isTemporary(chatId)) MemoryScope.TEMPORARY else MemoryScope.NORMAL

    // ---------------------------------------------------------------- write path
    /** Call for every user message (chat or Home). Returns the chip update (also published in [chips]). */
    suspend fun onUserMessage(chatId: Long?, messageId: Long?, text: String): MemoryUpdate = withContext(Dispatchers.IO) {
        val scope = scope(chatId)
        val upd = runCatching { book.onUserMessage(text, chatId?.let { "chat:$it" } ?: "home", scope, settings()) }.getOrDefault(MemoryUpdate())
        (upd.forgotten.isNotEmpty() || upd.explicit).let { explicitForget ->
            val forget = com.offlineagent.core.agents.memory.MemoryExtractor.command(text) as? com.offlineagent.core.agents.memory.MemoryCommand.Forget
            if (explicitForget && forget != null) runCatching { history.forget(forget.query) }
        }
        upd.chip()?.let { c -> if (messageId != null) _chips.update { it + (messageId to c) } }
        upd
    }

    /** Optional model pass for facts the rules missed (BACKGROUND priority: never delays the user's own requests). */
    suspend fun modelExtract(chatId: Long?, messageId: Long?, text: String) {
        if (!settings().savedMemories || scope(chatId) == MemoryScope.TEMPORARY) return
        if (!MemoryExtractor.mightHoldFact(text) || MemoryExtractor.extract(text).isNotEmpty() || MemoryExtractor.command(text) != null) return
        if (!runtime.hasChatModel.value) return
        val (pct, charging) = runtime.monitor.battery()
        if (!charging && (pct ?: 100) < 30) return // battery-friendly: skip the extra model call on low battery
        val schema = Schema.Obj(listOf("facts" to Schema.Arr(Schema.Obj(listOf(
            "text" to Schema.Str(maxLength = 120, minLength = 4),
            "kind" to Schema.Str(enum = listOf("fact", "preference", "contact", "project", "none")),
        )), maxItems = 2)))
        val out = runCatching {
            runtime.queue.complete(InferenceQueue.Priority.BACKGROUND, "memory-extract",
                "Extract at most 2 durable facts about the user (who they are, preferences, people, ongoing projects) from their message. " +
                    "Write each in third person, e.g. \"Prefers window seats\". Output kind none and no facts when there is nothing lasting.",
                text.take(600), Gbnf.of(schema), 120)
        }.getOrNull() ?: return
        val facts = runCatching { (com.offlineagent.core.agents.AgentJson.parseToJsonElement(out).jsonObject["facts"] as? JsonArray) }.getOrNull() ?: return
        val items = facts.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val kind = (o["kind"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            val t = (o["text"] as? JsonPrimitive)?.contentOrNull?.trim() ?: return@mapNotNull null
            if (kind == "none" || t.length < 4) null else Extracted(t, runCatching { MemoryKind.valueOf(kind.uppercase()) }.getOrDefault(MemoryKind.NOTE), 0.6)
        }
        if (items.isEmpty()) return
        book.addInferred(items, chatId?.let { "chat:$it" } ?: "home")
        if (messageId != null) _chips.update { it + (messageId to "Memory updated") }
    }

    /** Call when an answer finished: indexes the exchange for cross-chat retrieval (never for temporary chats). */
    suspend fun onExchange(chatId: Long, title: String, user: String, assistant: String) = withContext(Dispatchers.IO) {
        if (!settings().referenceChatHistory) return@withContext
        runCatching { history.addExchange(chatId, title, user, assistant, scope(chatId)) }
    }

    // ---------------------------------------------------------------- read path
    /** Memory block for a prompt: saved memories + snippets from OTHER chats, within [budgetTokens]. Empty when off/temporary. */
    suspend fun contextFor(query: String, chatId: Long?, budgetTokens: Int = 280): MemoryContext.Block = withContext(Dispatchers.IO) {
        val s = settings()
        if (scope(chatId) == MemoryScope.TEMPORARY || (!s.savedMemories && !s.referenceChatHistory)) return@withContext MemoryContext.Block("", emptyList(), emptyList(), 0)
        val mem = if (s.savedMemories) runCatching { book.relevant(query, 6) }.getOrDefault(emptyList()) else emptyList()
        val hist = if (s.referenceChatHistory) runCatching { history.search(query, 3, excludeChatId = chatId) }.getOrDefault(emptyList()) else emptyList()
        MemoryContext.build(mem, hist, budgetTokens)
    }

    /**
     * 0.6.2.2: context for a question ABOUT earlier chats ("summarise our previous chats"): the Threads summaries and
     * newest exchanges of recent chats, newest first. Temporary chat / Use memory / Reference chat history are honoured
     * (the block then says why there is nothing, so the model does not invent chats).
     */
    suspend fun previousChats(query: String, chatId: Long?, budgetTokens: Int): com.offlineagent.core.agents.memory.PreviousChats.Block = withContext(Dispatchers.IO) {
        com.offlineagent.core.agents.memory.PreviousChats.contextFor(query, history, chatId, scope(chatId), settings(), budgetTokens)
    }

    // ---------------------------------------------------------------- rolling summary (c)
    /**
     * Folds turns that fall out of [windowTokens] into the chat's running summary (model at BACKGROUND priority,
     * extractive fallback). Returns the current summary (empty if none). Temporary chats keep it only in memory.
     */
    suspend fun summarize(chatId: Long, title: String, turns: List<RollingSummary.Turn>, windowTokens: Int): String = withContext(Dispatchers.IO) {
        val st = p6.chatState(chatId)
        val already = st?.summarizedTurns ?: 0
        val overflow = RollingSummary.overflow(turns, windowTokens)
        if (overflow <= already) return@withContext st?.summary.orEmpty()
        val dropped = turns.subList(already, overflow)
        val prev = st?.summary.orEmpty()
        val summary = if (runtime.hasChatModel.value) runCatching {
            val (sys, user) = RollingSummary.prompt(prev, dropped)
            runtime.queue.complete(InferenceQueue.Priority.BACKGROUND, "summary", sys, user, null, 220).trim()
        }.getOrNull()?.takeIf { it.length > 20 } ?: RollingSummary.extractive(prev, dropped) else RollingSummary.extractive(prev, dropped)
        val temp = isTemporary(chatId)
        p6.upsertChatState(ChatMemoryStateEntity(chatId, summary, overflow, temp, System.currentTimeMillis()))
        if (!temp && settings().referenceChatHistory) runCatching { history.addSummary(chatId, title, summary, MemoryScope.NORMAL) }
        summary
    }

    suspend fun summaryOf(chatId: Long): String = withContext(Dispatchers.IO) { p6.chatState(chatId)?.summary.orEmpty() }

    // ---------------------------------------------------------------- learning hooks (contacts)
    val hooks = object : AgentHooks {
        override suspend fun onToolSucceeded(tool: String, args: JsonObject, risk: Risk) {
            if (!ContactRanking.CONTACT_TOOLS.containsMatchIn(tool) || !settings().savedMemories) return
            fun s(vararg k: String) = k.firstNotNullOfOrNull { (args[it] as? JsonPrimitive)?.contentOrNull?.takeIf { v -> v.isNotBlank() } }
            val number = s("number", "phone", "to", "recipient_number")?.takeIf { it.any(Char::isDigit) }
            val name = s("name", "contact", "recipient", "to_name") ?: number ?: return
            contacts.record(if (name.any(Char::isLetter)) name else (lookupName(name) ?: name), number, tool)
        }
    }

    /** Name for a number from the phone's contacts (READ_CONTACTS; null without the permission). */
    private fun lookupName(number: String): String? = runCatching {
        if (androidx.core.content.ContextCompat.checkSelfPermission(app, android.Manifest.permission.READ_CONTACTS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return null
        val uri = android.net.Uri.withAppendedPath(android.provider.ContactsContract.PhoneLookup.CONTENT_FILTER_URI, android.net.Uri.encode(number))
        app.contentResolver.query(uri, arrayOf(android.provider.ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull()

    /** Contact suggestions for a name: learned frequent contacts first, then the phone's address book (READ_CONTACTS). */
    suspend fun resolveContact(query: String): List<Pair<String, String?>> = withContext(Dispatchers.IO) {
        val learned = contacts.resolve(query)?.let { listOf(it.name to it.number) } ?: emptyList()
        val book = runCatching {
            if (androidx.core.content.ContextCompat.checkSelfPermission(app, android.Manifest.permission.READ_CONTACTS) != android.content.pm.PackageManager.PERMISSION_GRANTED) emptyList()
            else app.contentResolver.query(android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER),
                "${android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?", arrayOf("%$query%"), null)?.use { c ->
                buildList { while (c.moveToNext() && size < 5) add(c.getString(0) to c.getString(1)) }
            } ?: emptyList()
        }.getOrDefault(emptyList())
        (learned + book).distinctBy { it.second?.filter(Char::isDigit)?.takeLast(10) ?: it.first }
    }

    // ---------------------------------------------------------------- threads + chat deletion
    /** Deleting a chat deletes everything memory kept about it: indexed exchanges, its summary and its chat state. */
    suspend fun onChatDeleted(chatId: Long) = withContext(Dispatchers.IO) {
        runCatching { history.deleteChat(chatId) }
        runCatching { p6.deleteChatState(chatId) }
        temporary.remove(chatId)
    }

    /** Memory screen → Threads → delete: forgets the chat's summary and indexed snippets; the chat itself stays. */
    suspend fun deleteThread(chatId: Long) = withContext(Dispatchers.IO) {
        runCatching { history.deleteThread(chatId) }
        runCatching { p6.clearSummaryOf(chatId) }
    }

    // ---------------------------------------------------------------- wipe
    /** Deletes EVERYTHING memory-related: saved memories, chat-history index, summaries, contacts, routines. */
    suspend fun wipeAll() = withContext(Dispatchers.IO) {
        book.wipe(); history.wipe(); contacts.wipe(); routines.wipe(); p6.clearSummaries()
        runCatching { app.chatMemory.clear() } // Phase 3 vector conversation memory
        _chips.value = emptyMap()
    }
}
