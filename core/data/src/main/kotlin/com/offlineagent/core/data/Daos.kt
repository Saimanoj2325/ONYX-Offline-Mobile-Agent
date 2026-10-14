package com.offlineagent.core.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatDao {
    @Query("SELECT * FROM chats ORDER BY updatedAt DESC")
    fun observeChats(): Flow<List<ChatEntity>>

    @Query("SELECT * FROM chats WHERE id = :id")
    suspend fun chat(id: Long): ChatEntity?

    @Query("SELECT * FROM chats WHERE id = :id")
    fun observeChat(id: Long): Flow<ChatEntity?>

    @Insert suspend fun insertChat(c: ChatEntity): Long
    @Update suspend fun updateChat(c: ChatEntity)

    @Query("UPDATE chats SET updatedAt = :t WHERE id = :id") suspend fun touch(id: Long, t: Long)
    @Query("UPDATE chats SET title = :title WHERE id = :id") suspend fun rename(id: Long, title: String)
    @Query("UPDATE chats SET canvasVersion = :v WHERE id = :id") suspend fun setCanvasVersion(id: Long, v: Int)
    @Query("DELETE FROM chats WHERE id = :id") suspend fun deleteChat(id: Long)

    @Query("SELECT * FROM messages WHERE chatId = :chatId ORDER BY id")
    fun observeMessages(chatId: Long): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE chatId = :chatId ORDER BY id")
    suspend fun messages(chatId: Long): List<MessageEntity>

    @Insert suspend fun insertMessage(m: MessageEntity): Long
    @Update suspend fun updateMessage(m: MessageEntity)
    @Query("SELECT * FROM messages WHERE id = :id") suspend fun message(id: Long): MessageEntity?
    @Query("DELETE FROM messages WHERE id = :id") suspend fun deleteMessage(id: Long)

    @Query("SELECT * FROM canvas_versions WHERE chatId = :chatId ORDER BY version")
    fun observeCanvasVersions(chatId: Long): Flow<List<CanvasVersionEntity>>

    @Query("SELECT * FROM canvas_versions WHERE chatId = :chatId AND version = :version")
    suspend fun canvasVersion(chatId: Long, version: Int): CanvasVersionEntity?

    @Query("SELECT COALESCE(MAX(version), 0) FROM canvas_versions WHERE chatId = :chatId")
    suspend fun maxCanvasVersion(chatId: Long): Int

    @Insert suspend fun insertCanvasVersion(v: CanvasVersionEntity): Long

    /** Adds a canvas version and makes it current, atomically. */
    @Transaction
    suspend fun addCanvasVersion(chatId: Long, docJson: String, note: String, now: Long): Int {
        val v = maxCanvasVersion(chatId) + 1
        insertCanvasVersion(CanvasVersionEntity(chatId = chatId, version = v, docJson = docJson, note = note, createdAt = now))
        setCanvasVersion(chatId, v)
        touch(chatId, now)
        return v
    }
}

@Dao
interface DocumentDao {
    @Query("SELECT * FROM documents ORDER BY createdAt DESC") fun observeDocuments(): Flow<List<DocumentEntity>>
    @Query("SELECT * FROM documents ORDER BY createdAt DESC") suspend fun documents(): List<DocumentEntity>
    @Query("SELECT * FROM documents WHERE id = :id") suspend fun document(id: Long): DocumentEntity?
    @Insert suspend fun insertDocument(d: DocumentEntity): Long
    @Update suspend fun updateDocument(d: DocumentEntity)
    @Query("DELETE FROM documents WHERE id = :id") suspend fun deleteDocument(id: Long)

    @Insert suspend fun insertChunks(c: List<ChunkEntity>)
    @Query("DELETE FROM chunks WHERE documentId = :docId") suspend fun deleteChunks(docId: Long)
    @Query("SELECT COUNT(*) FROM chunks") suspend fun chunkCount(): Int

    /** All chunks with embeddings of a given dimension (brute-force vector search; fine for a few thousand). */
    @Query("SELECT c.* FROM chunks c JOIN documents d ON d.id = c.documentId WHERE d.status = 'READY'")
    suspend fun readyChunks(): List<ChunkEntity>

    @Query("SELECT chunks.* FROM chunks JOIN chunks_fts ON chunks.rowid = chunks_fts.rowid WHERE chunks_fts MATCH :match LIMIT :limit")
    suspend fun keywordSearch(match: String, limit: Int): List<ChunkEntity>

    @Query("SELECT * FROM chunks WHERE id IN (:ids)") suspend fun chunks(ids: List<Long>): List<ChunkEntity>
}

@Dao
interface SkillDao {
    @Query("SELECT * FROM skills ORDER BY builtIn DESC, id") fun observeSkills(): Flow<List<SkillEntity>>
    @Query("SELECT * FROM skills ORDER BY builtIn DESC, id") suspend fun skills(): List<SkillEntity>
    @Upsert suspend fun upsert(s: SkillEntity)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertIfAbsent(s: SkillEntity)
    @Query("DELETE FROM skills WHERE id = :id") suspend fun delete(id: String)
}

@Dao
interface AgentDao {
    @Upsert suspend fun upsertRun(r: AgentRunEntity)
    @Upsert suspend fun upsertSteps(s: List<AgentStepEntity>)
    @Query("SELECT * FROM agent_runs WHERE runId = :id") suspend fun run(id: String): AgentRunEntity?
    @Query("SELECT * FROM agent_steps WHERE runId = :id ORDER BY idx") suspend fun steps(id: String): List<AgentStepEntity>
    @Query("SELECT * FROM agent_steps WHERE runId = :id ORDER BY idx") fun observeSteps(id: String): Flow<List<AgentStepEntity>>
    @Query("SELECT * FROM agent_runs ORDER BY createdAt DESC LIMIT 200") fun observeRuns(): Flow<List<AgentRunEntity>>
    @Query("SELECT * FROM agent_runs WHERE status IN ('PLANNING','RUNNING','AWAITING_APPROVAL') ORDER BY updatedAt DESC")
    suspend fun unfinishedRuns(): List<AgentRunEntity>
    @Query("DELETE FROM agent_runs WHERE runId = :id") suspend fun deleteRun(id: String)

    @Transaction
    suspend fun saveCheckpoint(run: AgentRunEntity, steps: List<AgentStepEntity>) {
        upsertRun(run)
        if (steps.isNotEmpty()) upsertSteps(steps)
    }
}

@Dao
interface MiscDao {
    @Upsert suspend fun upsertMemory(m: MemoryEntity)
    @Query("SELECT * FROM memory ORDER BY updatedAt DESC") suspend fun memory(): List<MemoryEntity>
    @Query("SELECT * FROM memory ORDER BY updatedAt DESC") fun observeMemory(): Flow<List<MemoryEntity>>
    @Query("DELETE FROM memory WHERE `key` = :key") suspend fun deleteMemory(key: String)

    @Insert suspend fun insertNote(n: NoteEntity): Long
    @Query("SELECT * FROM notes ORDER BY createdAt DESC") fun observeNotes(): Flow<List<NoteEntity>>
    @Query("DELETE FROM notes WHERE id = :id") suspend fun deleteNote(id: Long)

    @Query("SELECT * FROM models ORDER BY addedAt DESC") fun observeModels(): Flow<List<ModelEntity>>
    @Query("SELECT * FROM models ORDER BY addedAt DESC") suspend fun models(): List<ModelEntity>
    @Insert suspend fun insertModel(m: ModelEntity): Long
    @Update suspend fun updateModel(m: ModelEntity)
    @Query("DELETE FROM models WHERE id = :id") suspend fun deleteModel(id: Long)
    @Query("UPDATE models SET active = (id = :id) WHERE role = :role") suspend fun activate(role: String, id: Long)
}
