package com.offlineagent.core.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Upsert
import com.offlineagent.core.agents.OutboxItem
import com.offlineagent.core.agents.OutboxStatus
import com.offlineagent.core.agents.OutboxStore
import com.offlineagent.core.agents.Risk
import com.offlineagent.core.agents.TaskCheckpoint
import com.offlineagent.core.agents.TaskStatus
import com.offlineagent.core.agents.TaskStore
import kotlinx.coroutines.flow.Flow

/**
 * Phase 5 state lives in its OWN database file (onyx-agents.db) so the Phase 1–4 database (version 1, no migrations)
 * is never touched: an upgrade can never lose chats, and this file can be wiped on its own ("Delete all agent data").
 */
@Database(
    entities = [AgentMemoryEntity::class, TaskRunEntity::class, TaskStepEntity::class, OutboxEntity::class, AuditEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class Phase5Database : RoomDatabase() {
    abstract fun dao(): Phase5Dao

    companion object {
        fun create(context: Context): Phase5Database =
            Room.databaseBuilder(context.applicationContext, Phase5Database::class.java, "onyx-agents.db")
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .build()
    }
}

/** A user-visible, editable long-term memory. [pinned] memories are always offered to the Memory agent first. */
@Entity(tableName = "agent_memories")
data class AgentMemoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val text: String,
    val category: String = "note",
    val source: String = "user",
    val pinned: Boolean = false,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(tableName = "task_runs", indices = [Index("status")])
data class TaskRunEntity(
    @PrimaryKey val runId: String,
    val request: String,
    val status: String,
    val checkpointJson: String,
    val answer: String?,
    val error: String?,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(tableName = "task_steps", primaryKeys = ["runId", "nodeId"])
data class TaskStepEntity(
    val runId: String,
    val nodeId: String,
    val agent: String,
    val goal: String,
    val status: String,
    val attempts: Int,
    val output: String,
    val error: String?,
    val verified: Boolean?,
    val updatedAt: Long,
)

@Entity(tableName = "outbox", indices = [Index("status")])
data class OutboxEntity(
    @PrimaryKey val id: String,
    val runId: String,
    val tool: String,
    val argsJson: String,
    val risk: String,
    val status: String,
    val attempts: Int,
    val maxAttempts: Int,
    val createdAt: Long,
    val nextAttemptAt: Long,
    val lastError: String?,
    val result: String?,
)

@Entity(tableName = "audit_log")
data class AuditEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val at: Long,
    val runId: String,
    val actor: String,
    val action: String,
    val detail: String,
    val risk: String,
)

@Dao
interface Phase5Dao {
    // memories
    @Query("SELECT * FROM agent_memories ORDER BY pinned DESC, updatedAt DESC") fun observeMemories(): Flow<List<AgentMemoryEntity>>
    @Query("SELECT * FROM agent_memories ORDER BY pinned DESC, updatedAt DESC") suspend fun memories(): List<AgentMemoryEntity>
    @Upsert suspend fun upsertMemory(m: AgentMemoryEntity): Long
    @Query("DELETE FROM agent_memories WHERE id = :id") suspend fun deleteMemory(id: Long)
    @Query("DELETE FROM agent_memories") suspend fun deleteAllMemories()

    // task runs + step checkpoints
    @Upsert suspend fun upsertRun(r: TaskRunEntity)
    @Upsert suspend fun upsertSteps(s: List<TaskStepEntity>)
    @Transaction suspend fun saveCheckpoint(r: TaskRunEntity, s: List<TaskStepEntity>) { upsertRun(r); upsertSteps(s) }
    @Query("SELECT * FROM task_runs WHERE runId = :id") suspend fun run(id: String): TaskRunEntity?
    @Query("SELECT * FROM task_runs ORDER BY updatedAt DESC LIMIT 100") fun observeRuns(): Flow<List<TaskRunEntity>>
    @Query("SELECT * FROM task_runs WHERE status NOT IN ('COMPLETED','FAILED','CANCELLED') ORDER BY updatedAt DESC") suspend fun unfinishedRuns(): List<TaskRunEntity>
    @Query("SELECT * FROM task_steps WHERE runId = :id") fun observeSteps(id: String): Flow<List<TaskStepEntity>>
    @Query("DELETE FROM task_runs WHERE runId = :id") suspend fun deleteRunRow(id: String)
    @Query("DELETE FROM task_steps WHERE runId = :id") suspend fun deleteSteps(id: String)
    @Transaction suspend fun deleteRun(id: String) { deleteRunRow(id); deleteSteps(id) }

    // outbox
    @Upsert suspend fun upsertOutbox(o: OutboxEntity)
    @Query("SELECT * FROM outbox WHERE status IN ('QUEUED','RUNNING') ORDER BY createdAt") suspend fun openOutbox(): List<OutboxEntity>
    @Query("SELECT * FROM outbox ORDER BY createdAt DESC LIMIT 100") fun observeOutbox(): Flow<List<OutboxEntity>>
    @Query("UPDATE outbox SET status = 'CANCELLED' WHERE id = :id AND status = 'QUEUED'") suspend fun cancelOutbox(id: String)

    // audit
    @androidx.room.Insert suspend fun insertAudit(a: AuditEntity)
    @Query("SELECT * FROM audit_log ORDER BY at DESC LIMIT 300") fun observeAudit(): Flow<List<AuditEntity>>
    @Query("SELECT * FROM audit_log ORDER BY at") suspend fun audit(): List<AuditEntity>
    @Query("DELETE FROM audit_log") suspend fun clearAudit()
}

/** Task checkpoints: whole checkpoint as JSON (exact resume) + one row per step (queryable, shown in Tasks). */
class RoomTaskStore(private val dao: Phase5Dao) : TaskStore {
    override suspend fun save(checkpoint: TaskCheckpoint) {
        val now = System.currentTimeMillis()
        dao.saveCheckpoint(
            TaskRunEntity(checkpoint.runId, checkpoint.request, checkpoint.status.name, checkpoint.toJson(), checkpoint.answer, checkpoint.error,
                checkpoint.createdAt.takeIf { it > 0 } ?: now, now),
            checkpoint.nodes.map { TaskStepEntity(checkpoint.runId, it.id, it.agent.name, it.goal, it.status.name, it.attempts, it.output.take(4000), it.error, it.verified, now) },
        )
    }

    override suspend fun load(runId: String): TaskCheckpoint? = dao.run(runId)?.let { runCatching { TaskCheckpoint.fromJson(it.checkpointJson) }.getOrNull() }

    /** Runs that were interrupted (process death) — offered for Resume and resumed by the WorkManager job. */
    suspend fun interrupted(): List<TaskCheckpoint> = dao.unfinishedRuns().mapNotNull { e ->
        runCatching { TaskCheckpoint.fromJson(e.checkpointJson) }.getOrNull()?.takeIf { !it.status.isTerminal }
    }

    suspend fun markInterrupted(runId: String) {
        val cp = load(runId) ?: return
        if (!cp.status.isTerminal) save(cp.copy(status = TaskStatus.INTERRUPTED))
    }
}

class RoomOutboxStore(private val dao: Phase5Dao) : OutboxStore {
    override suspend fun insert(item: OutboxItem) = dao.upsertOutbox(item.toEntity())
    override suspend fun update(item: OutboxItem) = dao.upsertOutbox(item.toEntity())
    override suspend fun open(): List<OutboxItem> = dao.openOutbox().map { it.toItem() }
}

fun OutboxItem.toEntity() = OutboxEntity(id, runId, tool, argsJson, risk.name, status.name, attempts, maxAttempts, createdAt, nextAttemptAt, lastError, result)
fun OutboxEntity.toItem() = OutboxItem(id, runId, tool, argsJson, runCatching { Risk.valueOf(risk) }.getOrDefault(Risk.HIGH),
    runCatching { OutboxStatus.valueOf(status) }.getOrDefault(OutboxStatus.QUEUED), attempts, maxAttempts, createdAt, nextAttemptAt, lastError, result)
