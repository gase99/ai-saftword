package com.aicode.glass.data

import com.aicode.glass.core.data.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ChatRepository @Inject constructor(private val dao: ChatDao) {
    fun conversations(workspace: String) = dao.conversations(workspace)
    fun messages(conversation: Long) = dao.messages(conversation)
    fun tools(conversation: Long) = dao.tools(conversation)
    suspend fun create(workspace: String, title: String = "New conversation"): Long {
        val now = System.currentTimeMillis()
        return dao.insertConversation(ConversationEntity(workspaceId = workspace, title = title, createdAt = now, updatedAt = now))
    }
    suspend fun delete(id: Long) = dao.deleteConversation(id)
    suspend fun recover() { dao.recoverInterrupted(); dao.recoverTools() }
}
