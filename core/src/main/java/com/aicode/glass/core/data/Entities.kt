package com.aicode.glass.core.data

import androidx.room.*

@Entity(tableName = "workspaces")
data class WorkspaceEntity(@PrimaryKey val id: String, val name: String, val createdAt: Long)

@Entity(tableName = "conversations", indices = [Index("workspaceId")])
data class ConversationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val workspaceId: String, val title: String, val createdAt: Long, val updatedAt: Long
)

@Entity(tableName = "messages", foreignKeys = [ForeignKey(entity = ConversationEntity::class,
    parentColumns = ["id"], childColumns = ["conversationId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("conversationId")])
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val conversationId: Long, val role: String, val content: String,
    val createdAt: Long = System.currentTimeMillis(), val status: String = "complete",
    val toolCallsJson: String? = null, val toolCallId: String? = null, val error: String? = null
)

@Entity(tableName = "tool_invocations", indices = [Index("conversationId")])
data class ToolInvocationEntity(
    @PrimaryKey val id: String, val conversationId: Long, val messageId: Long,
    val name: String, val arguments: String, val status: String,
    val result: String = "", val checkpointId: String? = null, val createdAt: Long
)

@Entity(tableName = "checkpoints", indices = [Index("workspaceId")])
data class CheckpointEntity(
    @PrimaryKey val id: String, val workspaceId: String, val label: String, val createdAt: Long
)

@Entity(tableName = "checkpoint_files", primaryKeys = ["checkpointId", "path"],
    foreignKeys = [ForeignKey(entity = CheckpointEntity::class, parentColumns = ["id"],
        childColumns = ["checkpointId"], onDelete = ForeignKey.CASCADE)])
data class CheckpointFileEntity(val checkpointId: String, val path: String, val hash: String, val size: Long)
