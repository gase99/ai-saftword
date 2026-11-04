package com.aicode.glass.data

import com.aicode.glass.core.data.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ChatRepository @Inject constructor(private val dao: ChatDao) {
    // Room throws from the flow when the on-device database cannot be read (schema mismatch,
    // corrupt file, full disk). Those flows are collected directly by Compose, so an uncaught
    // exception there kills the process: log and emit an empty list instead.
    fun conversations(workspace: String): Flow<List<ConversationEntity>> =
        dao.conversations(workspace).catch { error -> reporter(error); emit(emptyList()) }
    fun messages(conversation: Long): Flow<List<MessageEntity>> =
        dao.messages(conversation).catch { error -> reporter(error); emit(emptyList()) }
    fun tools(conversation: Long): Flow<List<ToolInvocationEntity>> =
        dao.tools(conversation).catch { error -> reporter(error); emit(emptyList()) }

    private fun reporter(error: Throwable) {
        android.util.Log.e("AiCodeGlass", "Local database query failed", error)
    }
    suspend fun create(workspace: String, title: String = "New conversation"): Long {
        val now = System.currentTimeMillis()
        return dao.insertConversation(ConversationEntity(workspaceId = workspace, title = title, createdAt = now, updatedAt = now))
    }
    suspend fun delete(id: Long) = dao.deleteConversation(id)
    suspend fun recover() { dao.recoverInterrupted(); dao.recoverTools() }
}
