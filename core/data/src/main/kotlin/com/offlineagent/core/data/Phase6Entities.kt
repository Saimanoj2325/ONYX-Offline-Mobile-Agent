package com.offlineagent.core.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import com.offlineagent.core.agents.memory.ContactUse
import com.offlineagent.core.agents.memory.ContactUseRepo
import com.offlineagent.core.agents.memory.HistoryChunk
import com.offlineagent.core.agents.memory.HistoryRepo
import com.offlineagent.core.agents.memory.MemoryKind
import com.offlineagent.core.agents.memory.Routine
import com.offlineagent.core.agents.memory.RoutineRepo
import com.offlineagent.core.agents.memory.SavedMemory
import com.offlineagent.core.agents.memory.SavedMemoryRepo
import kotlinx.coroutines.flow.Flow

/*
 * Phase 6 tables in onyx-agents.db (schema v2, Room AutoMigration 1→2: new tables + new agent_memories columns with
 * defaults, so existing Phase 5 memories are kept). Nothing here ever leaves the device.
 */

/** On-phone benchmark result (Agents → Models → Benchmark). */
@Entity(tableName = "bench_results")
data class BenchResultEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val at: Long,
    val model: String,
    val threads: Int,
    val contextLength: Int,
    val loadMs: Long,
    val promptTokens: Int,
    val promptTps: Double,
    val genTokens: Int,
    val genTps: Double,
    val specGenTps: Double,
    val specAccepted: Int,
    val specDrafted: Int,
    val peakRssMb: Long,
    val thermal: String,
    val battery: Int,
    val note: String,
)

@Entity(tableName = "contact_usage")
data class ContactUsageEntity(
    @PrimaryKey val key: String,
    val name: String,
    val number: String?,
    val uses: Int,
    val lastUsedAt: Long,
    val channels: String,
)

@Entity(tableName = "routines")
data class RoutineEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val example: String,
    val keywords: String,
    val stepsJson: String,
    val successes: Int,
    val uses: Int,
    val enabled: Boolean,
    val createdAt: Long,
    val lastUsedAt: Long,
)

@Entity(tableName = "history_chunks", indices = [Index("chatId")])
data class HistoryChunkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val chatId: Long,
    val chatTitle: String,
    val text: String,
    val createdAt: Long,
    val summary: Boolean,
)

/** Rolling per-chat summary + which chats are temporary (never read/write memory). */
@Entity(tableName = "chat_memory_state")
data class ChatMemoryStateEntity(
    @PrimaryKey val chatId: Long,
    val summary: String,
    val summarizedTurns: Int,
    val temporary: Boolean,
    val updatedAt: Long,
)

@Dao
interface Phase6Dao {
    @androidx.room.Insert suspend fun insertBench(b: BenchResultEntity): Long
    @Query("SELECT * FROM bench_results ORDER BY at DESC LIMIT 50") fun observeBench(): Flow<List<BenchResultEntity>>
    @Query("DELETE FROM bench_results") suspend fun clearBench()

    @Query("SELECT * FROM contact_usage") suspend fun contacts(): List<ContactUsageEntity>
    @Query("SELECT * FROM contact_usage ORDER BY lastUsedAt DESC") fun observeContacts(): Flow<List<ContactUsageEntity>>
    @Upsert suspend fun upsertContact(c: ContactUsageEntity)
    @Query("DELETE FROM contact_usage WHERE `key` = :key") suspend fun deleteContact(key: String)
    @Query("DELETE FROM contact_usage") suspend fun deleteAllContacts()

    @Query("SELECT * FROM routines") suspend fun routines(): List<RoutineEntity>
    @Query("SELECT * FROM routines ORDER BY lastUsedAt DESC") fun observeRoutines(): Flow<List<RoutineEntity>>
    @Upsert suspend fun upsertRoutine(r: RoutineEntity): Long
    @Query("DELETE FROM routines WHERE id = :id") suspend fun deleteRoutine(id: Long)
    @Query("DELETE FROM routines") suspend fun deleteAllRoutines()

    @Query("SELECT * FROM history_chunks ORDER BY createdAt DESC LIMIT 5000") suspend fun history(): List<HistoryChunkEntity>
    @androidx.room.Insert suspend fun insertHistory(h: HistoryChunkEntity): Long
    @Query("DELETE FROM history_chunks WHERE chatId = :chatId") suspend fun deleteHistoryOfChat(chatId: Long)
    @Query("DELETE FROM history_chunks WHERE chatId = :chatId AND summary = 1") suspend fun deleteSummariesOfChat(chatId: Long)
    @Query("SELECT * FROM history_chunks WHERE summary = 1 ORDER BY createdAt DESC") fun observeSummaries(): Flow<List<HistoryChunkEntity>>
    @Query("DELETE FROM history_chunks WHERE text LIKE '%' || :phrase || '%'") suspend fun deleteHistoryMatching(phrase: String): Int
    @Query("DELETE FROM history_chunks") suspend fun deleteAllHistory()
    @Query("SELECT COUNT(*) FROM history_chunks") fun observeHistoryCount(): Flow<Int>

    @Query("SELECT * FROM chat_memory_state WHERE chatId = :chatId") suspend fun chatState(chatId: Long): ChatMemoryStateEntity?
    @Query("SELECT * FROM chat_memory_state WHERE chatId = :chatId") fun observeChatState(chatId: Long): Flow<ChatMemoryStateEntity?>
    @Upsert suspend fun upsertChatState(s: ChatMemoryStateEntity)
    @Query("DELETE FROM chat_memory_state") suspend fun deleteAllChatStates()
    @Query("DELETE FROM chat_memory_state WHERE chatId = :chatId") suspend fun deleteChatState(chatId: Long)
    @Query("UPDATE chat_memory_state SET summary = '', summarizedTurns = 0 WHERE chatId = :chatId") suspend fun clearSummaryOf(chatId: Long)
    @Query("UPDATE chat_memory_state SET summary = '', summarizedTurns = 0") suspend fun clearSummaries()
}

// ---------------------------------------------------------------- Room implementations of the :core:agents ports

class RoomSavedMemoryRepo(private val dao: Phase5Dao) : SavedMemoryRepo {
    override suspend fun all() = dao.memories().map { it.toSaved() }
    override suspend fun upsert(m: SavedMemory): Long {
        val id = dao.upsertMemory(m.toEntity())
        return if (m.id != 0L) m.id else id
    }
    override suspend fun delete(id: Long) = dao.deleteMemory(id)
    override suspend fun deleteAll() = dao.deleteAllMemories()
}

fun AgentMemoryEntity.toSaved() = SavedMemory(id, text, runCatching { MemoryKind.valueOf(kind) }.getOrDefault(MemoryKind.NOTE), inferred, confidence, confirmed,
    source, slot, pinned, createdAt, updatedAt, useCount, lastUsedAt)
fun SavedMemory.toEntity() = AgentMemoryEntity(id, text, category = kind.name.lowercase(), source = source, pinned = pinned, createdAt = createdAt, updatedAt = updatedAt,
    kind = kind.name, inferred = inferred, confidence = confidence, confirmed = confirmed, slot = slot, useCount = useCount, lastUsedAt = lastUsedAt)

class RoomContactRepo(private val dao: Phase6Dao) : ContactUseRepo {
    override suspend fun all() = dao.contacts().map { ContactUse(it.key, it.name, it.number, it.uses, it.lastUsedAt, it.channels.split(',').filter { c -> c.isNotBlank() }.toSet()) }
    override suspend fun upsert(c: ContactUse) = dao.upsertContact(ContactUsageEntity(c.key, c.name, c.number, c.uses, c.lastUsedAt, c.channels.joinToString(",")))
    override suspend fun delete(key: String) = dao.deleteContact(key)
    override suspend fun deleteAll() = dao.deleteAllContacts()
}

class RoomRoutineRepo(private val dao: Phase6Dao) : RoutineRepo {
    override suspend fun all() = dao.routines().map { Routine(it.id, it.name, it.example, it.keywords.split(' ').filter { k -> k.isNotBlank() }.toSet(),
        Routine.stepsFromJson(it.stepsJson), it.successes, it.uses, it.enabled, it.createdAt, it.lastUsedAt) }
    override suspend fun upsert(r: Routine): Long {
        val id = dao.upsertRoutine(RoutineEntity(r.id, r.name, r.example, r.keywords.joinToString(" "), Routine.stepsToJson(r.steps), r.successes, r.uses, r.enabled, r.createdAt, r.lastUsedAt))
        return if (r.id != 0L) r.id else id
    }
    override suspend fun delete(id: Long) = dao.deleteRoutine(id)
    override suspend fun deleteAll() = dao.deleteAllRoutines()
}

class RoomHistoryRepo(private val dao: Phase6Dao) : HistoryRepo {
    override suspend fun all() = dao.history().map { HistoryChunk(it.id, it.chatId, it.chatTitle, it.text, it.createdAt, it.summary) }
    override suspend fun add(c: HistoryChunk) = dao.insertHistory(HistoryChunkEntity(0, c.chatId, c.chatTitle, c.text, c.createdAt, c.summary))
    override suspend fun deleteChat(chatId: Long) = dao.deleteHistoryOfChat(chatId)
    override suspend fun deleteSummaries(chatId: Long) = dao.deleteSummariesOfChat(chatId)
    override suspend fun deleteMatching(phrase: String) = dao.deleteHistoryMatching(phrase)
    override suspend fun deleteAll() = dao.deleteAllHistory()
}
