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
    val contextLength: Int = 4096,
    val threads: Int = 0, // 0 = recommended
    val thinking: Boolean = false,
    val output: OutputPreference = OutputPreference.AUTO,
    val useDocuments: Boolean = true,
    val alwaysAllowedTools: Set<String> = emptySet(),
    val lastChatId: Long = 0,
)

private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsRepository(context: Context) {
    private val store = context.applicationContext.settingsStore
    private object K {
        val ctx = intPreferencesKey("context_length")
        val threads = intPreferencesKey("threads")
        val thinking = booleanPreferencesKey("thinking")
        val output = stringPreferencesKey("output_pref")
        val useDocs = booleanPreferencesKey("use_documents")
        val allowed = stringSetPreferencesKey("always_allowed_tools")
        val lastChat = stringPreferencesKey("last_chat")
    }

    val settings: Flow<AppSettings> = store.data.map { p ->
        AppSettings(
            contextLength = p[K.ctx] ?: 4096,
            threads = p[K.threads] ?: 0,
            thinking = p[K.thinking] ?: false,
            output = p[K.output]?.let { runCatching { OutputPreference.valueOf(it) }.getOrNull() } ?: OutputPreference.AUTO,
            useDocuments = p[K.useDocs] ?: true,
            alwaysAllowedTools = p[K.allowed] ?: emptySet(),
            lastChatId = p[K.lastChat]?.toLongOrNull() ?: 0,
        )
    }

    suspend fun current(): AppSettings = settings.first()

    suspend fun update(f: (AppSettings) -> AppSettings) {
        store.edit { p ->
            val cur = AppSettings(
                contextLength = p[K.ctx] ?: 4096, threads = p[K.threads] ?: 0, thinking = p[K.thinking] ?: false,
                output = p[K.output]?.let { runCatching { OutputPreference.valueOf(it) }.getOrNull() } ?: OutputPreference.AUTO,
                useDocuments = p[K.useDocs] ?: true, alwaysAllowedTools = p[K.allowed] ?: emptySet(),
                lastChatId = p[K.lastChat]?.toLongOrNull() ?: 0,
            )
            val n = f(cur)
            p[K.ctx] = n.contextLength; p[K.threads] = n.threads; p[K.thinking] = n.thinking; p[K.output] = n.output.name
            p[K.useDocs] = n.useDocuments; p[K.allowed] = n.alwaysAllowedTools; p[K.lastChat] = n.lastChatId.toString()
        }
    }
}
