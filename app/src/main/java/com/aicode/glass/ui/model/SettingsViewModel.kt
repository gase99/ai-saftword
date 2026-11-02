package com.aicode.glass.ui.model

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicode.glass.core.data.*
import com.aicode.glass.core.network.*
import com.aicode.glass.core.security.SecretStore
import com.aicode.glass.data.GitRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(val settings: SettingsRepository, val secrets: SecretStore) : ViewModel() {
    val preferences = settings.preferences.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppPreferences())
    private val _configured = MutableStateFlow(ProviderType.entries.associateWith { secrets.contains("api_${it.name}") } + (ProviderType.OPENAI to secrets.contains("api_OPENAI")))
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
        if (config.provider == ProviderType.CUSTOM) StreamingAiClient.validateEndpoint(config.endpoint)
        if (key.isNotBlank()) secrets.set("api_${config.provider.name}", key.trim())
        settings.setProviderConfig(config)
        _configured.update { it + (config.provider to secrets.contains("api_${config.provider.name}")) }; _notice.value = "Provider settings saved"
    } catch(e: Exception) { _error.value = e.message } }
    fun removeKey(provider: ProviderType) = viewModelScope.launch { try { secrets.delete("api_${provider.name}"); _configured.update { it + (provider to false) }; _notice.value = "API key removed" } catch(e: Exception) { _error.value = e.message } }
    fun saveGit(name: String, email: String, token: String) = viewModelScope.launch { try {
        require(name.isNotBlank() && email.matches(Regex("^[^\\s@]+@[^\\s@]+$"))) { "Enter a valid author name and email." }
        if (token.isNotBlank()) { secrets.set("git_token", token.trim()); gitTokenConfigured.value = true }
        settings.setGitIdentity(name.trim(), email.trim(), preferences.value.gitRemote); _notice.value = "Git settings saved"
    } catch(e: Exception) { _error.value = e.message } }
    fun removeGitToken() = viewModelScope.launch { try { secrets.delete("git_token"); gitTokenConfigured.value = false; _notice.value = "Git token removed" } catch(e: Exception) { _error.value = e.message } }
}
