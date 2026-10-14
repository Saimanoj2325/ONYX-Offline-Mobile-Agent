package com.offlineagent.core.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "chats")
data class ChatEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    /** Version of the canvas currently shown (0 = no canvas). Undo/redo move this pointer. */
    val canvasVersion: Int = 0,
)

/** A chat message. Structured answers keep their validated UI JSON in [uiJson] (schema version inside). */
@Entity(
    tableName = "messages",
    foreignKeys = [ForeignKey(entity = ChatEntity::class, parentColumns = ["id"], childColumns = ["chatId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("chatId")],
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val chatId: Long,
    val role: String,
    val text: String,
    val reasoning: String = "",
    /** "markdown" | "canvas" | "agent" */
    val kind: String = "markdown",
    val uiJson: String? = null,
    val canvasVersion: Int? = null,
    /** JSON array of {n,title,text,documentId} for RAG answers. */
    val citationsJson: String? = null,
    /** JSON array of attached image file paths (app-private copies). */
    val attachmentsJson: String? = null,
    val statsText: String? = null,
    val error: String? = null,
    val runId: String? = null,
    val createdAt: Long,
)

@Entity(
    tableName = "canvas_versions",
    foreignKeys = [ForeignKey(entity = ChatEntity::class, parentColumns = ["id"], childColumns = ["chatId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["chatId", "version"], unique = true)],
)
data class CanvasVersionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val chatId: Long,
    val version: Int,
    val docJson: String,
    val note: String,
    val createdAt: Long,
)

@Entity(tableName = "documents")
data class DocumentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val mime: String,
    val chars: Int = 0,
    val chunkCount: Int = 0,
    /** PENDING | INDEXING | READY | FAILED */
    val status: String,
    /** File name of the embedding model used (null = keyword-only index). */
    val embeddingModel: String? = null,
    val error: String? = null,
    /** App-private file with the extracted plain text. */
    val textPath: String,
    val createdAt: Long,
)

@Entity(
    tableName = "chunks",
    foreignKeys = [ForeignKey(entity = DocumentEntity::class, parentColumns = ["id"], childColumns = ["documentId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("documentId")],
)
data class ChunkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val documentId: Long,
    val idx: Int,
    val text: String,
    /** Little-endian float32 vector (L2-normalised), or null when no embedding model was available. */
    @ColumnInfo(typeAffinity = ColumnInfo.BLOB) val embedding: ByteArray? = null,
)

/** Full-text index over chunk text (keyword half of hybrid retrieval). */
@Fts4(contentEntity = ChunkEntity::class)
@Entity(tableName = "chunks_fts")
data class ChunkFts(val text: String)

@Entity(tableName = "skills")
data class SkillEntity(
    @PrimaryKey val id: String,
    val markdown: String,
    val builtIn: Boolean,
    val enabled: Boolean = true,
    val updatedAt: Long,
)

@Entity(tableName = "agent_runs", indices = [Index("chatId"), Index("status")])
data class AgentRunEntity(
    @PrimaryKey val runId: String,
    val chatId: Long?,
    val skillId: String?,
    val request: String,
    val status: String,
    val planJson: String,
    val iteration: Int,
    val finalAnswer: String?,
    val error: String?,
    val consecutiveErrors: Int,
    val sideEffects: Int,
    val canvasRendered: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "agent_steps",
    primaryKeys = ["runId", "idx"],
    foreignKeys = [ForeignKey(entity = AgentRunEntity::class, parentColumns = ["runId"], childColumns = ["runId"], onDelete = ForeignKey.CASCADE)],
)
data class AgentStepEntity(
    val runId: String,
    val idx: Int,
    val kind: String,
    val tool: String?,
    val thought: String,
    val input: String,
    val output: String,
    val error: String?,
    val startedAt: Long,
    val durationMs: Long,
)

@Entity(tableName = "memory")
data class MemoryEntity(@PrimaryKey val key: String, val value: String, val updatedAt: Long)

@Entity(tableName = "notes")
data class NoteEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0, val title: String, val body: String, val createdAt: Long)

/** An imported GGUF. role = CHAT | EMBEDDING | VISION. A VISION model has an [mmprojPath]. */
@Entity(tableName = "models")
data class ModelEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val role: String,
    val name: String,
    val path: String,
    val mmprojPath: String? = null,
    val sizeBytes: Long,
    val active: Boolean,
    val addedAt: Long,
)
