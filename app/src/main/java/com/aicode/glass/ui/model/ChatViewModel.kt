package com.aicode.glass.ui.model

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicode.glass.agent.AgentCoordinator
import com.aicode.glass.core.data.*
import com.aicode.glass.data.ChatRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ChatViewModel @Inject constructor(val settings: SettingsRepository, val chats: ChatRepository, val agent: AgentCoordinator, val analysts: com.aicode.glass.agent.SubAgentCoordinator) : ViewModel() {
    val preferences = settings.preferences.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), settings.initial)
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val conversations = preferences.flatMapLatest { chats.conversations(it.activeWorkspace) }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val messages = preferences.flatMapLatest { chats.messages(it.activeConversation) }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val tools = preferences.flatMapLatest { chats.tools(it.activeConversation) }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    fun send(text: String) = agent.send(preferences.value.activeConversation, text)
    fun newConversation() {
        if (agent.state.value.busy) return
        viewModelScope.launch { settings.setConversation(chats.create(preferences.value.activeWorkspace)) }
    }
    fun select(id: Long) { if (!agent.state.value.busy) viewModelScope.launch { settings.setConversation(id) } }
    fun delete(id: Long) { if (!agent.state.value.busy) viewModelScope.launch { chats.delete(id); if (preferences.value.activeConversation == id) newConversation() } }
    fun mode(mode: String) { if (!agent.state.value.busy) viewModelScope.launch { settings.setAgentMode(mode) } }
}
