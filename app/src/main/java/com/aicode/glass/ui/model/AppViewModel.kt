package com.aicode.glass.ui.model

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicode.glass.agent.AgentCoordinator
import com.aicode.glass.core.data.*
import com.aicode.glass.data.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AppViewModel @Inject constructor(
    val settings: SettingsRepository, val chats: ChatRepository, val workspaces: WorkspaceRepository,
    private val dao: ChatDao, private val agent: AgentCoordinator
) : ViewModel() {
    val preferences = settings.preferences.stateIn(viewModelScope, SharingStarted.Eagerly, settings.initial)
    val workspaceList = workspaces.workspaces.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    private val _ready = MutableStateFlow(false); val ready = _ready.asStateFlow()
    private val _error = MutableStateFlow<String?>(null); val error = _error.asStateFlow()
    init {
        viewModelScope.launch {
            try {
                workspaces.ensureDefault(); chats.recover()
                settings.preferences.map { it.activeWorkspace }.distinctUntilChanged().collectLatest { id ->
                    val existing = dao.conversations(id).first()
                    val preferred = settings.preferences.first().activeConversation
                    if (dao.conversation(preferred)?.workspaceId != id) settings.setConversation(existing.firstOrNull()?.id ?: chats.create(id))
                    _ready.value = true
                }
            } catch (e: Exception) {
                // A broken local database must not leave the shell on a spinner forever: show the
                // reason (the schema fallback in DatabaseModule should have prevented it).
                android.util.Log.e("AiCodeGlass", "Workspace initialisation failed", e)
                _error.value = e.message ?: "Could not open the local database."
                _ready.value = true
            }
        }
    }
    fun dismissError() { _error.value = null }
    fun selectWorkspace(id: String) { if (!agent.state.value.busy) viewModelScope.launch { settings.setWorkspace(id) } }
    fun createWorkspace(name: String) {
        if (agent.state.value.busy) return
        viewModelScope.launch { try { settings.setWorkspace(workspaces.create(name)) } catch (e: Exception) { _error.value = e.message } }
    }
    fun setLanguage(language: String) = viewModelScope.launch { settings.setLanguage(language) }
    fun setTheme(theme: String) = viewModelScope.launch { settings.setTheme(theme) }
}
