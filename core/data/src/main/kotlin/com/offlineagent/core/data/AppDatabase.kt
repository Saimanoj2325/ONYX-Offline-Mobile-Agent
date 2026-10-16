package com.offlineagent.core.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.offlineagent.core.runtime.agent.RunCheckpoint
import com.offlineagent.core.runtime.agent.RunStatus
import com.offlineagent.core.runtime.agent.RunStore
import com.offlineagent.core.runtime.agent.StepKind
import com.offlineagent.core.runtime.agent.StepRecord
import com.offlineagent.core.runtime.ui.OutputPreference
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

@Database(
    entities = [
        ChatEntity::class, MessageEntity::class, CanvasVersionEntity::class, DocumentEntity::class, ChunkEntity::class,
        ChunkFts::class, SkillEntity::class, AgentRunEntity::class, AgentStepEntity::class, MemoryEntity::class,
        NoteEntity::class, ModelEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun chats(): ChatDao
    abstract fun documents(): DocumentDao
    abstract fun skills(): SkillDao
    abstract fun agent(): AgentDao
    abstract fun misc(): MiscDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "offline-agent.db")
                // Write-ahead logging keeps UI reads from blocking on the indexer's writes.
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .build()
    }
}

/** [RunStore] over Room: every agent checkpoint is a transaction (run row + steps). */
class RoomRunStore(private val dao: AgentDao) : RunStore {
    override suspend fun save(checkpoint: RunCheckpoint) {
        val c = checkpoint
        dao.saveCheckpoint(
            AgentRunEntity(
                runId = c.runId, chatId = c.chatId, skillId = c.skillId, request = c.request, status = c.status.name,
                planJson = JsonArray(c.plan.map { JsonPrimitive(it) }).toString(), iteration = c.iteration,
                finalAnswer = c.finalAnswer, error = c.error, consecutiveErrors = c.consecutiveErrors,
                sideEffects = c.sideEffects, canvasRendered = c.canvasRendered, createdAt = c.createdAt, updatedAt = c.updatedAt,
            ),
            c.steps.map { s ->
                AgentStepEntity(c.runId, s.index, s.kind.name, s.tool, s.thought, s.input, s.output, s.error, s.startedAt, s.durationMs)
            },
        )
    }

    override suspend fun load(runId: String): RunCheckpoint? {
        val r = dao.run(runId) ?: return null
        return toCheckpoint(r, dao.steps(runId))
    }

    companion object {
        fun toCheckpoint(r: AgentRunEntity, steps: List<AgentStepEntity>) = RunCheckpoint(
            runId = r.runId, chatId = r.chatId, skillId = r.skillId, request = r.request,
            status = runCatching { RunStatus.valueOf(r.status) }.getOrDefault(RunStatus.FAILED),
            plan = runCatching { Json.parseToJsonElement(r.planJson).jsonArray.map { it.jsonPrimitive.content } }.getOrDefault(emptyList()),
            iteration = r.iteration,
            steps = steps.map { toStep(it) },
            finalAnswer = r.finalAnswer, consecutiveErrors = r.consecutiveErrors, sideEffects = r.sideEffects,
            canvasRendered = r.canvasRendered, error = r.error, createdAt = r.createdAt, updatedAt = r.updatedAt,
        )

        fun toStep(s: AgentStepEntity) = StepRecord(
            s.idx, runCatching { StepKind.valueOf(s.kind) }.getOrDefault(StepKind.ERROR), s.tool, s.thought, s.input, s.output,
            s.error, s.startedAt, s.durationMs,
        )
    }
}

// ---------------------------------------------------------------- settings (DataStore)

data class AppSettings(
    /** 0 = automatic (by device RAM: 16K on 8 GB phones, 8K on 6 GB, 4K on 4 GB). */
    val contextLength: Int = 0,
    val threads: Int = 0, // 0 = recommended
    val thinking: Boolean = false,
    val output: OutputPreference = OutputPreference.AUTO,
    val useDocuments: Boolean = true,
    val alwaysAllowedTools: Set<String> = emptySet(),
    val lastChatId: Long = 0,
    // ---- Phase 3: performance & long context
    /** KV cache precision: AUTO (f16 when it fits, else q8_0) | F16 | Q8_0 | Q4_0 */
    val kvCache: String = "AUTO",
    /** AUTO | ON | OFF */
    val flashAttention: String = "AUTO",
    val batchSize: Int = 512,
    val ubatchSize: Int = 512,
    /** 0 = automatic by RAM (up to 8192). */
    val maxOutputTokens: Int = 0,
    /** Experimental YaRN rope scaling beyond the model's trained context (64K/128K). */
    val yarn: Boolean = false,
    val speculative: Boolean = false,
    val draftTokens: Int = 6,
    val prefixCache: Boolean = true,
    /** Keep the model loaded when the app goes to the background (unloaded only under real memory pressure). */
    val keepWarm: Boolean = true,
    val autoSummarize: Boolean = true,
    val longTermMemory: Boolean = true,
    val pollLevel: Int = 50,
    // ---- Phase 3: phone actions & automation
    /** Enabled PhoneGroup names (empty set on first run = only the harmless defaults, see PhoneDefaults). */
    val phoneGroups: Set<String> = DEFAULT_PHONE_GROUPS,
    val automationAllowedApps: Set<String> = emptySet(),
    val automationBlockedApps: Set<String> = emptySet(),
    /** Let the screen agent also send a screenshot description from the vision model (slower). */
    val screenVision: Boolean = false,
    /** Accessibility automation kill switch (false = every automation request is refused). */
    val automationEnabled: Boolean = true,
    /** Enabled third-party MCP server ids (binder component names / LAN URLs). */
    val mcpServers: Set<String> = emptySet(),
) {
    companion object {
        /** Groups that need no special access are on by default; everything sensitive is opt-in. */
        val DEFAULT_PHONE_GROUPS = setOf("ALARMS", "APPS_LINKS", "MESSAGING", "CAMERA", "DEVICE_SETTINGS", "DEVICE_INFO", "CLIPBOARD")
    }
}

private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsRepository(context: Context) {
    private val store = context.applicationContext.settingsStore
    private object K {
        val ctx = intPreferencesKey("context_length_v3")
        val threads = intPreferencesKey("threads")
        val thinking = booleanPreferencesKey("thinking")
        val output = stringPreferencesKey("output_pref")
        val useDocs = booleanPreferencesKey("use_documents")
        val allowed = stringSetPreferencesKey("always_allowed_tools")
        val lastChat = stringPreferencesKey("last_chat")
        val kv = stringPreferencesKey("kv_cache")
        val fa = stringPreferencesKey("flash_attention")
        val batch = intPreferencesKey("batch")
        val ubatch = intPreferencesKey("ubatch")
        val maxOut = intPreferencesKey("max_output")
        val yarn = booleanPreferencesKey("yarn")
        val spec = booleanPreferencesKey("speculative")
        val draftTokens = intPreferencesKey("draft_tokens")
        val prefix = booleanPreferencesKey("prefix_cache")
        val warm = booleanPreferencesKey("keep_warm")
        val summarize = booleanPreferencesKey("auto_summarize")
        val ltm = booleanPreferencesKey("long_term_memory")
        val poll = intPreferencesKey("poll_level")
        val groups = stringSetPreferencesKey("phone_groups")
        val autoAllow = stringSetPreferencesKey("automation_allowed_apps")
        val autoBlock = stringSetPreferencesKey("automation_blocked_apps")
        val vision = booleanPreferencesKey("screen_vision")
        val autoOn = booleanPreferencesKey("automation_enabled")
        val mcp = stringSetPreferencesKey("mcp_servers")
    }

    private fun read(p: Preferences) = AppSettings(
        contextLength = p[K.ctx] ?: 0,
        threads = p[K.threads] ?: 0,
        thinking = p[K.thinking] ?: false,
        output = p[K.output]?.let { runCatching { OutputPreference.valueOf(it) }.getOrNull() } ?: OutputPreference.AUTO,
        useDocuments = p[K.useDocs] ?: true,
        alwaysAllowedTools = p[K.allowed] ?: emptySet(),
        lastChatId = p[K.lastChat]?.toLongOrNull() ?: 0,
        kvCache = p[K.kv] ?: "AUTO", flashAttention = p[K.fa] ?: "AUTO", batchSize = p[K.batch] ?: 512, ubatchSize = p[K.ubatch] ?: 512,
        maxOutputTokens = p[K.maxOut] ?: 0, yarn = p[K.yarn] ?: false, speculative = p[K.spec] ?: false, draftTokens = p[K.draftTokens] ?: 6,
        prefixCache = p[K.prefix] ?: true, keepWarm = p[K.warm] ?: true, autoSummarize = p[K.summarize] ?: true, longTermMemory = p[K.ltm] ?: true,
        pollLevel = p[K.poll] ?: 50, phoneGroups = p[K.groups] ?: AppSettings.DEFAULT_PHONE_GROUPS,
        automationAllowedApps = p[K.autoAllow] ?: emptySet(), automationBlockedApps = p[K.autoBlock] ?: emptySet(),
        screenVision = p[K.vision] ?: false, automationEnabled = p[K.autoOn] ?: true, mcpServers = p[K.mcp] ?: emptySet(),
    )

    val settings: Flow<AppSettings> = store.data.map { read(it) }

    suspend fun current(): AppSettings = settings.first()

    suspend fun update(f: (AppSettings) -> AppSettings) {
        store.edit { p ->
            val n = f(read(p))
            p[K.ctx] = n.contextLength; p[K.threads] = n.threads; p[K.thinking] = n.thinking; p[K.output] = n.output.name
            p[K.useDocs] = n.useDocuments; p[K.allowed] = n.alwaysAllowedTools; p[K.lastChat] = n.lastChatId.toString()
            p[K.kv] = n.kvCache; p[K.fa] = n.flashAttention; p[K.batch] = n.batchSize; p[K.ubatch] = n.ubatchSize
            p[K.maxOut] = n.maxOutputTokens; p[K.yarn] = n.yarn; p[K.spec] = n.speculative; p[K.draftTokens] = n.draftTokens
            p[K.prefix] = n.prefixCache; p[K.warm] = n.keepWarm; p[K.summarize] = n.autoSummarize; p[K.ltm] = n.longTermMemory
            p[K.poll] = n.pollLevel; p[K.groups] = n.phoneGroups; p[K.autoAllow] = n.automationAllowedApps; p[K.autoBlock] = n.automationBlockedApps
            p[K.vision] = n.screenVision; p[K.autoOn] = n.automationEnabled; p[K.mcp] = n.mcpServers
        }
    }
}
