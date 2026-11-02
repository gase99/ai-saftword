package com.aicode.glass.ui.model

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicode.glass.agent.AgentCoordinator
import com.aicode.glass.core.data.SettingsRepository
import com.aicode.glass.data.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class GitViewModel @Inject constructor(val settings: SettingsRepository, val git: GitRepository, val checkpoints: CheckpointRepository, val agent: AgentCoordinator) : ViewModel() {
    val workspaceId = settings.preferences.map { it.activeWorkspace }.distinctUntilChanged().stateIn(viewModelScope, SharingStarted.Eagerly, "default")
    private val _state = MutableStateFlow(GitSnapshot()); val state = _state.asStateFlow()
    private val _busy = MutableStateFlow(false); val busy = _busy.asStateFlow()
    private val _error = MutableStateFlow<String?>(null); val error = _error.asStateFlow()
    private val _notice = MutableStateFlow<String?>(null); val notice = _notice.asStateFlow()
    private val _diff = MutableStateFlow<String?>(null); val diff = _diff.asStateFlow()
    init { viewModelScope.launch { workspaceId.collect { refresh() } } }
    fun dismissError() { _error.value = null }
    fun dismissNotice() { _notice.value = null }
    fun dismissDiff() { _diff.value = null }
    fun refresh() { viewModelScope.launch { try { _state.value = git.snapshot(workspaceId.value) } catch(e: Exception) { _error.value = e.message } } }
    private fun action(message: String = "Done", block: suspend () -> Unit) {
        if (_busy.value || agent.state.value.busy) return
        viewModelScope.launch { _busy.value = true; try { block(); refresh(); _notice.value = message } catch(e: Exception) { _error.value = e.message } finally { _busy.value = false } }
    }
    fun initialize() = action("Repository initialized") { git.initialize(workspaceId.value) }
    fun stage(path: String, staged: Boolean = true) = action("Index updated") { git.stage(workspaceId.value, path, staged) }
    fun stageAll() = action("All changes staged") { git.stageAll(workspaceId.value) }
    fun commit(message: String) { val prefs = settings.preferences.value; action("Changes committed") { git.commit(workspaceId.value, message, prefs.authorName, prefs.authorEmail) } }
    fun branch(name: String, switch: Boolean) = action("Branch ready") { git.createBranch(workspaceId.value, name); if (switch) git.switchBranch(workspaceId.value, name, checkpoints) }
    fun switch(name: String) = action("Switched to $name") { git.switchBranch(workspaceId.value, name, checkpoints) }
    fun showDiff(path: String, staged: Boolean) { viewModelScope.launch { try { _diff.value = git.diff(workspaceId.value, path, staged) } catch(e: Exception) { _error.value = e.message } } }
    fun showCommit(hash: String) { viewModelScope.launch { try { _diff.value = git.commitDiff(workspaceId.value, hash) } catch(e: Exception) { _error.value = e.message } } }
    fun remote(url: String) = action("Remote saved") { git.setRemote(workspaceId.value, url) }
    fun sync(action: String) = action("Git $action completed") { git.sync(workspaceId.value, action) }
}
