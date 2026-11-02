package com.aicode.glass.core.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface WorkspaceDao {
    @Query("SELECT * FROM workspaces ORDER BY createdAt") fun observe(): Flow<List<WorkspaceEntity>>
    @Query("SELECT * FROM workspaces ORDER BY createdAt") suspend fun all(): List<WorkspaceEntity>
    @Query("SELECT * FROM workspaces WHERE id = :id") suspend fun get(id: String): WorkspaceEntity?
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insert(workspace: WorkspaceEntity)
    @Query("UPDATE workspaces SET name = :name WHERE id = :id") suspend fun rename(id: String, name: String)
}

@Dao
interface ChatDao {
    @Query("SELECT * FROM conversations WHERE workspaceId = :workspaceId ORDER BY updatedAt DESC")
    fun conversations(workspaceId: String): Flow<List<ConversationEntity>>
    @Query("SELECT * FROM conversations WHERE id = :id") suspend fun conversation(id: Long): ConversationEntity?
    @Query("SELECT * FROM messages WHERE conversationId = :id ORDER BY createdAt, id")
    fun messages(id: Long): Flow<List<MessageEntity>>
    @Query("SELECT * FROM messages WHERE conversationId = :id ORDER BY createdAt, id")
    suspend fun history(id: Long): List<MessageEntity>
    @Query("SELECT * FROM tool_invocations WHERE conversationId = :id ORDER BY createdAt")
    fun tools(id: Long): Flow<List<ToolInvocationEntity>>
    @Insert suspend fun insertConversation(conversation: ConversationEntity): Long
    @Insert suspend fun insertMessage(message: MessageEntity): Long
    @Update suspend fun updateMessage(message: MessageEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertTool(tool: ToolInvocationEntity)
    @Query("UPDATE conversations SET title = :title, updatedAt = :time WHERE id = :id")
    suspend fun touch(id: Long, title: String, time: Long)
    @Query("DELETE FROM conversations WHERE id = :id") suspend fun deleteConversation(id: Long)
    @Query("UPDATE messages SET status = 'interrupted' WHERE status = 'streaming'") suspend fun recoverInterrupted()
    @Query("UPDATE tool_invocations SET status = 'interrupted' WHERE status IN ('pending', 'approved', 'running')") suspend fun recoverTools()
}

@Dao
interface CheckpointDao {
    @Query("SELECT * FROM checkpoints WHERE workspaceId = :workspaceId ORDER BY createdAt DESC")
    fun observe(workspaceId: String): Flow<List<CheckpointEntity>>
    @Query("SELECT * FROM checkpoints WHERE id = :id") suspend fun get(id: String): CheckpointEntity?
    @Query("SELECT * FROM checkpoint_files WHERE checkpointId = :id") suspend fun files(id: String): List<CheckpointFileEntity>
    @Insert suspend fun insert(checkpoint: CheckpointEntity)
    @Insert suspend fun insertFiles(files: List<CheckpointFileEntity>)
    @Transaction suspend fun insertSnapshot(checkpoint: CheckpointEntity, files: List<CheckpointFileEntity>) {
        insert(checkpoint); insertFiles(files)
    }
    @Query("SELECT * FROM checkpoints WHERE workspaceId = :workspaceId ORDER BY createdAt DESC")
    suspend fun all(workspaceId: String): List<CheckpointEntity>
    @Query("DELETE FROM checkpoints WHERE id = :id") suspend fun delete(id: String)
    @Query("SELECT DISTINCT hash FROM checkpoint_files") suspend fun referencedHashes(): List<String>
}
