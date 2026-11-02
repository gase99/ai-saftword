package com.aicode.glass.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import com.aicode.glass.core.network.ProviderConfig
import com.aicode.glass.core.network.ProviderType
import javax.inject.Inject
import javax.inject.Singleton

data class AppPreferences(
    val language: String = "en", val theme: String = "dark",
    val activeWorkspace: String = "default", val activeConversation: Long = 0L,
    val provider: ProviderType = ProviderType.OPENAI, val agentMode: String = "build",
    val authorName: String = "AiCode Glass", val authorEmail: String = "developer@localhost", val gitRemote: String = "",
    val configs: Map<ProviderType, ProviderConfig> = ProviderType.entries.associateWith { ProviderConfig(it, it.defaultModel) }
) { val darkTheme: Boolean get() = theme != "light" }

@Singleton
class SettingsRepository @Inject constructor(private val store: DataStore<Preferences>) {
    val preferences: Flow<AppPreferences> = store.data.map { p ->
        val configs = ProviderType.entries.associateWith { provider -> ProviderConfig(provider,
            p[stringPreferencesKey("model_${provider.name}")] ?: provider.defaultModel,
            p[stringPreferencesKey("endpoint_${provider.name}")] ?: "") }
        AppPreferences(language = p[Keys.language] ?: "en", theme = p[Keys.theme] ?: "dark",
            activeWorkspace = p[Keys.workspace] ?: "default", activeConversation = p[Keys.conversation] ?: 0L,
            provider = runCatching { ProviderType.valueOf(p[Keys.provider] ?: "OPENAI") }.getOrDefault(ProviderType.OPENAI),
            agentMode = p[Keys.mode] ?: "build", authorName = p[Keys.authorName] ?: "AiCode Glass",
            authorEmail = p[Keys.authorEmail] ?: "developer@localhost", gitRemote = p[Keys.remote] ?: "", configs = configs)
    }
    suspend fun setLanguage(value: String) = store.edit { it[Keys.language] = if (value == "fa") "fa" else "en" }
    suspend fun setTheme(value: String) = store.edit { it[Keys.theme] = value }
    suspend fun setWorkspace(value: String) = store.edit { it[Keys.workspace] = value; it[Keys.conversation] = 0L }
    suspend fun setConversation(value: Long) = store.edit { it[Keys.conversation] = value }
    suspend fun setProvider(value: ProviderType) = store.edit { it[Keys.provider] = value.name }
    suspend fun setAgentMode(value: String) = store.edit { it[Keys.mode] = if (value == "ask") "ask" else "build" }
    suspend fun setProviderConfig(config: ProviderConfig) = store.edit {
        it[stringPreferencesKey("model_${config.provider.name}")] = config.model.trim()
        it[stringPreferencesKey("endpoint_${config.provider.name}")] = config.endpoint.trim()
    }
    suspend fun setGitIdentity(name: String, email: String, remote: String) = store.edit {
        it[Keys.authorName] = name; it[Keys.authorEmail] = email; it[Keys.remote] = remote
    }
    private object Keys {
        val language = stringPreferencesKey("language"); val theme = stringPreferencesKey("theme")
        val workspace = stringPreferencesKey("workspace"); val conversation = longPreferencesKey("conversation")
        val provider = stringPreferencesKey("provider"); val mode = stringPreferencesKey("agent_mode")
        val authorName = stringPreferencesKey("author_name"); val authorEmail = stringPreferencesKey("author_email")
        val remote = stringPreferencesKey("git_remote")
    }
}
