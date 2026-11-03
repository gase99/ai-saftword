package com.aicode.glass.ui.model

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicode.glass.core.data.SettingsRepository
import com.aicode.glass.data.*
import com.aicode.glass.data.terminalCommand
import com.aicode.glass.terminal.TerminalSession
import com.aicode.glass.terminal.CommandOutput
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File
import javax.inject.Inject

data class EditorState(val path: String = "", val content: String = "", val original: String = "", val hash: String = "") { val dirty get() = content != original }

@HiltViewModel
class WorkspaceViewModel @Inject constructor(
    @ApplicationContext private val context: Context, val settings: SettingsRepository,
    val workspaces: WorkspaceRepository, val checkpoints: CheckpointRepository, private val git: GitRepository, private val audit: TerminalAudit, val agent: com.aicode.glass.agent.AgentCoordinator
) : ViewModel() {
    val workspaceId = settings.preferences.map { it.activeWorkspace }.distinctUntilChanged().stateIn(viewModelScope, SharingStarted.Eagerly, "default")
    private val _sessions = MutableStateFlow(listOf(TerminalSession(workspaces.root("default")))); val sessions = _sessions.asStateFlow()
    private val _activeSession = MutableStateFlow(_sessions.value.first().id); val activeSession = _activeSession.asStateFlow()
    val session = combine(sessions, activeSession) { tabs, id -> tabs.find { it.id == id } ?: tabs.first() }.stateIn(viewModelScope, SharingStarted.Eagerly, _sessions.value.first())
    private val terminalJobs = mutableMapOf<String, Job>()
    private val _directory = MutableStateFlow(""); val directory = _directory.asStateFlow()
    private val _files = MutableStateFlow<List<FileItem>>(emptyList()); val files = _files.asStateFlow()
    private val _editor = MutableStateFlow<EditorState?>(null); val editor = _editor.asStateFlow()
    private val _busy = MutableStateFlow(false); val busy = _busy.asStateFlow()
    private val _error = MutableStateFlow<String?>(null); val error = _error.asStateFlow()
    private val _notice = MutableStateFlow<String?>(null); val notice = _notice.asStateFlow()
    @OptIn(ExperimentalCoroutinesApi::class)
    val snapshots = workspaceId.flatMapLatest { checkpoints.observe(it) }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    init { viewModelScope.launch { workspaceId.collect { id ->
        terminalJobs.values.forEach { it.cancel() }; terminalJobs.clear()
        val first = TerminalSession(workspaces.root(id)); _sessions.value = listOf(first); _activeSession.value = first.id
        _directory.value = ""; _editor.value = null; refresh()
    } } }
    fun dismissError() { _error.value = null }
    fun dismissNotice() { _notice.value = null }
    fun refresh() { viewModelScope.launch { try { _files.value = workspaces.transaction(workspaceId.value) { workspaces.list(it, _directory.value) } } catch(e: Exception) { _error.value = e.message } } }
    fun directory(path: String) { _directory.value = path; refresh() }
    fun upDirectory() { _directory.value = _directory.value.substringBeforeLast('/', ""); refresh() }
    fun open(path: String) { viewModelScope.launch { try { val file = workspaces.read(workspaceId.value, path); _editor.value = EditorState(file.path, file.content, file.content, file.hash) } catch(e: Exception) { _error.value = e.message } } }
    fun edit(text: String) { _editor.update { it?.copy(content = text) } }
    fun closeEditor() { _editor.value = null }
    private fun mutation(label: String, block: suspend (File) -> Unit) {
        if (agent.state.value.busy) { _error.value = "Stop the active AI turn before changing files."; return }
        viewModelScope.launch { _busy.value = true; try { workspaces.transaction(workspaceId.value) { root -> checkpoints.captureLocked(root, workspaceId.value, label); block(root) }; refresh(); _notice.value = "Saved · checkpoint created" } catch(e: Exception) { _error.value = e.message } finally { _busy.value = false } }
    }
    fun save() {
        val editor = _editor.value ?: return
        if (agent.state.value.busy) { _error.value = "Stop the active AI turn before saving."; return }
        viewModelScope.launch { _busy.value = true; try {
            workspaces.transaction(workspaceId.value) { root ->
                checkpoints.captureLocked(root, workspaceId.value, "Editor · ${editor.path}")
                workspaces.write(root, editor.path, editor.content, editor.hash)
                val saved = workspaces.read(root, editor.path); _editor.value = EditorState(saved.path, saved.content, saved.content, saved.hash)
            }; refresh(); _notice.value = "Saved · checkpoint created"
        } catch(e: Exception) { _error.value = e.message } finally { _busy.value = false } }
    }
    fun createFile(path: String) = mutation("Before creating $path") { val file = workspaces.resolve(it, path); if (file.exists()) throw WorkspaceException("File already exists."); workspaces.write(it, path, "") }
    fun deleteFile(path: String) { mutation("Before deleting $path") { workspaces.delete(it, path) }; if (_editor.value?.path == path) closeEditor() }
    fun checkpoint(label: String) { viewModelScope.launch { try { checkpoints.capture(workspaceId.value, label); _notice.value = "Checkpoint created" } catch(e: Exception) { _error.value = e.message } } }
    fun restore(id: String) {
        if (agent.state.value.busy) { _error.value = "Stop the active AI turn before restoring."; return }
        viewModelScope.launch { _busy.value = true; try { checkpoints.restore(id); _editor.value = null; refresh(); _notice.value = "Workspace restored. Chat and Git history were preserved." } catch(e: Exception) { _error.value = e.message } finally { _busy.value = false } }
    }
    fun selectTerminal(id: String) { if (_sessions.value.any { it.id == id }) _activeSession.value = id }
    fun addTerminal() {
        val current = _sessions.value; if (current.size >= 4) return
        val next = TerminalSession(workspaces.root(workspaceId.value), "Terminal " + (current.size + 1))
        _sessions.value = current + next; _activeSession.value = next.id
    }
    fun closeTerminal(id: String) {
        if (_sessions.value.size <= 1) return
        terminalJobs.remove(id)?.cancel(); _sessions.update { it.filterNot { tab -> tab.id == id } }
        if (_activeSession.value == id) _activeSession.value = _sessions.value.first().id
    }
    fun execute(command: String) {
        val current = session.value; if (terminalJobs[current.id]?.isActive == true) return
        if (command.trim() == "clear") { current.clear(); return }
        val id = workspaceId.value
        terminalJobs[current.id] = viewModelScope.launch {
            val start = System.currentTimeMillis(); val prefs = settings.preferences.first()
            try {
                workspaces.transaction(id) { root -> current.execute(command, object : com.aicode.glass.terminal.TerminalHost {
                    override suspend fun checkpoint(label: String) { checkpoints.captureLocked(root, id, "Terminal · " + label) }
                    override suspend fun git(args: List<String>) = withContext(Dispatchers.IO) { git.terminalCommand(root, args, prefs.authorName, prefs.authorEmail) }
                }) }
                if (prefs.terminalLogging) audit.record(id, current.id, command.trim().substringBefore(' '), current.exitCode.value, System.currentTimeMillis() - start)
                refresh()
            } catch (e: CancellationException) { /* Session cancellation is isolated. */ }
            catch (e: Exception) { _error.value = e.message }
            finally { terminalJobs.remove(current.id) }
        }
    }
    fun stopTerminal() { val current = session.value; current.cancel(); terminalJobs[current.id]?.cancel() }
    fun exportAudit(uri: Uri) { viewModelScope.launch { try { val text = audit.read(workspaceId.value); context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }; _notice.value = "Terminal audit exported" } catch(e: Exception) { _error.value = e.message } } }
    fun importZip(uri: Uri) {
        if (agent.state.value.busy) return
        viewModelScope.launch { _busy.value = true; try {
            context.contentResolver.openInputStream(uri)?.use { input -> workspaces.importZip(workspaceId.value, input) { staging, root ->
                workspaces.transaction(workspaceId.value) { checkpoints.captureLocked(it, workspaceId.value, "Before ZIP import"); WorkspaceRepository.swapContents(it, staging) }
            } } ?: error("Could not open archive")
            _editor.value = null; _directory.value = ""; refresh(); _notice.value = "Workspace imported"
        } catch(e: Exception) { _error.value = e.message } finally { _busy.value = false } }
    }
    fun exportZip(uri: Uri) { viewModelScope.launch { _busy.value = true; try {
        workspaces.transaction(workspaceId.value) { root -> context.contentResolver.openOutputStream(uri)?.use { workspaces.export(root, it) } ?: error("Could not open export file") }
        _notice.value = "Workspace exported. API keys and Git credentials are not included."
    } catch(e: Exception) { _error.value = e.message } finally { _busy.value = false } } }
}
