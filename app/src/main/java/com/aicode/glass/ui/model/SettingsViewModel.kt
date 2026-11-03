package com.aicode.glass.ui.model

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicode.glass.core.data.*
import com.aicode.glass.core.network.*
import com.aicode.glass.core.security.SecretStore
import com.aicode.glass.core.security.ProviderKeys
import com.aicode.glass.core.mcp.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import com.aicode.glass.data.GitRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(val settings: SettingsRepository, val secrets: SecretStore, private val keys: ProviderKeys, val mcp: McpRegistry, private val providerClient: ResilientAiClient, private val transport: StreamingAiClient) : ViewModel() {
    val mcpServers = mcp.servers.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val mcpState = mcp.state
    private val _keyCounts = MutableStateFlow<Map<ProviderType, Int>>(emptyMap()); val keyCounts = _keyCounts.asStateFlow()
    private val _testing = MutableStateFlow(false); val testing = _testing.asStateFlow()
    private val actionMutex = Mutex()
    init { viewModelScope.launch { refreshCounts() } }
    private suspend fun refreshCounts() { _keyCounts.value = ProviderType.entries.associateWith { keys.count(it) }; _configured.value = _keyCounts.value.mapValues { it.value > 0 } }
    val preferences = settings.preferences.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppPreferences())
    private val _configured = MutableStateFlow(ProviderType.entries.associateWith { secrets.contains("api_${it.name}") })
    val configured = _configured.asStateFlow()
    val gitTokenConfigured = MutableStateFlow(secrets.contains("git_token"))
    private val _notice = MutableStateFlow<String?>(null); val notice = _notice.asStateFlow()
    private val _error = MutableStateFlow<String?>(null); val error = _error.asStateFlow()
    fun dismissNotice() { _notice.value = null }; fun dismissError() { _error.value = null }
    fun provider(provider: ProviderType) = viewModelScope.launch { settings.setProvider(provider) }
    fun language(value: String) = viewModelScope.launch { settings.setLanguage(value) }
    fun theme(value: String) = viewModelScope.launch { settings.setTheme(value) }
    fun saveProvider(config: ProviderConfig, key: String) = viewModelScope.launch { try {
        require(config.model.isNotBlank() && config.model.length <= 160 && config.model.none { it.isWhitespace() }) { "Enter a valid model ID." }
        if (config.provider == ProviderType.CUSTOM || config.provider == ProviderType.OLLAMA) EndpointPolicy.validate(config.endpoint.ifBlank { config.provider.baseUrl }, config.allowLocalHttp)
        if (key.isNotBlank()) keys.add(config.provider, key)
        settings.setProviderConfig(config)
        refreshCounts(); _configured.update { it + (config.provider to (keys.count(config.provider) > 0)) }; _notice.value = "Provider settings saved"
    } catch(e: Exception) { _error.value = e.message } }
    fun removeKey(provider: ProviderType) = viewModelScope.launch { try { keys.clear(provider); refreshCounts(); _configured.update { it + (provider to false) }; _notice.value = "API key removed" } catch(e: Exception) { _error.value = e.message } }
    fun connection(retries: Int, fallbacks: Boolean, providers: Set<ProviderType>) = viewModelScope.launch { settings.setConnection(retries, fallbacks, providers) }
    fun reducedMotion(value: Boolean) = viewModelScope.launch { settings.setReducedMotion(value) }
    fun terminalLogging(value: Boolean) = viewModelScope.launch { settings.setTerminalLogging(value) }
    fun saveMcp(name: String, endpoint: String, token: String, localHttp: Boolean, existingId: String? = null) = viewModelScope.launch {
        try {
            require(name.isNotBlank() && name.length <= 60); EndpointPolicy.validate(endpoint, localHttp)
            val id = existingId ?: UUID.randomUUID().toString().replace("-", "").take(12)
            val server = McpServer(id, name.trim(), endpoint.trim(), true, localHttp)
            val current = mcpServers.value.filterNot { it.id == id }; require(current.size < 4) { "At most four MCP servers." }
            mcp.setToken(id, token); settings.saveMcp(current + server); mcp.clear(); _notice.value = "MCP server saved. Refresh to discover tools."
        } catch(e: Exception) { _error.value = e.message }
    }
    fun removeMcp(id: String) = viewModelScope.launch { try { settings.removeMcp(id); secrets.delete("mcp_$id"); mcp.clear() } catch(e: Exception) { _error.value = e.message } }
    fun refreshMcp() { if(!actionMutex.tryLock()) return; viewModelScope.launch { try { val state = mcp.refresh(); _notice.value = state.connections.sumOf { it.tools.size }.toString() + " MCP tools discovered" } catch(e: Exception) { _error.value = e.message } finally { actionMutex.unlock() } } }
    fun testConnection() {
        if(!actionMutex.tryLock()) return
        _testing.value = true
        viewModelScope.launch { try {
            val prefs = settings.preferences.first(); val config = prefs.configs.getValue(prefs.provider); val key = keys.get(prefs.provider).firstOrNull() ?: ""
            val start = System.currentTimeMillis(); var output = ""
            transport.stream(config, key, "Reply with OK only.", listOf(AiMessage("user", "Connection test. Reply OK.")), emptyList()).collect { if(it is AiEvent.Text) output += it.value }
            require(output.isNotBlank()) { "Provider returned no text." }
            _notice.value = "Live connection passed in " + (System.currentTimeMillis()-start) + " ms · " + output.take(80)
        } catch(e: Exception) { _error.value = e.message } finally { _testing.value = false; actionMutex.unlock() } }
    }
    fun saveGit(name: String, email: String, token: String) = viewModelScope.launch { try {
        require(name.isNotBlank() && email.matches(Regex("^[^\\s@]+@[^\\s@]+$"))) { "Enter a valid author name and email." }
        if (token.isNotBlank()) { secrets.set("git_token", token.trim()); gitTokenConfigured.value = true }
        settings.setGitIdentity(name.trim(), email.trim(), preferences.value.gitRemote); _notice.value = "Git settings saved"
    } catch(e: Exception) { _error.value = e.message } }
    fun removeGitToken() = viewModelScope.launch { try { secrets.delete("git_token"); gitTokenConfigured.value = false; _notice.value = "Git token removed" } catch(e: Exception) { _error.value = e.message } }
}
