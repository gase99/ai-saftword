package com.aicode.glass.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import com.aicode.glass.core.network.ProviderConfig
import com.aicode.glass.core.network.ProviderType
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.builtins.ListSerializer
import com.aicode.glass.core.mcp.McpServer
import javax.inject.Inject
import javax.inject.Singleton

data class AppPreferences(
    val language: String = "en", val theme: String = "dark",
    val activeWorkspace: String = "default", val activeConversation: Long = 0L,
    val provider: ProviderType = ProviderType.OPENAI, val agentMode: String = "build",
    val authorName: String = "AiCode Glass", val authorEmail: String = "developer@localhost", val gitRemote: String = "",
    val connectionRetries: Int = 2, val allowFallbacks: Boolean = false,
    val fallbackProviders: Set<ProviderType> = emptySet(), val reducedMotion: Boolean = false, val terminalLogging: Boolean = false,
    val configs: Map<ProviderType, ProviderConfig> = ProviderType.entries.associateWith { ProviderConfig(it, it.defaultModel) }
) { val darkTheme: Boolean get() = theme != "light" }

@Singleton
class SettingsRepository @Inject constructor(private val store: DataStore<Preferences>) {
    // The settings flow is collected eagerly at startup (AppViewModel), so an unreadable file must
    // degrade to defaults instead of crashing the process. Secrets are stored elsewhere.
    val preferences: Flow<AppPreferences> = store.data.catch { error ->
        android.util.Log.e("AiCodeGlass", "Settings could not be read; continuing with defaults", error)
        emit(emptyPreferences())
    }.map { p ->
        val configs = ProviderType.entries.associateWith { provider -> ProviderConfig(provider,
            p[stringPreferencesKey("model_${provider.name}")] ?: provider.defaultModel,
            p[stringPreferencesKey("endpoint_${provider.name}")] ?: "", p[booleanPreferencesKey("local_http_${provider.name}")] ?: false) }
        AppPreferences(language = p[Keys.language] ?: "en", theme = p[Keys.theme] ?: "dark",
            activeWorkspace = p[Keys.workspace] ?: "default", activeConversation = p[Keys.conversation] ?: 0L,
            provider = runCatching { ProviderType.valueOf(p[Keys.provider] ?: "OPENAI") }.getOrDefault(ProviderType.OPENAI),
            agentMode = p[Keys.mode] ?: "build", connectionRetries = p[Keys.retries] ?: 2, allowFallbacks = p[Keys.fallbacks] ?: false,
            fallbackProviders = (p[Keys.fallbackProviders] ?: "").split(",").mapNotNull { runCatching { ProviderType.valueOf(it) }.getOrNull() }.toSet(),
            reducedMotion = p[Keys.reducedMotion] ?: false, terminalLogging = p[Keys.terminalLogging] ?: false, authorName = p[Keys.authorName] ?: "AiCode Glass",
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
        it[booleanPreferencesKey("local_http_${config.provider.name}")] = config.allowLocalHttp
    }
    suspend fun setGitIdentity(name: String, email: String, remote: String) = store.edit {
        it[Keys.authorName] = name; it[Keys.authorEmail] = email; it[Keys.remote] = remote
    }
    suspend fun setConnection(retries: Int, fallbacks: Boolean, providers: Set<ProviderType>) = store.edit {
        it[Keys.retries] = retries.coerceIn(0, 3); it[Keys.fallbacks] = fallbacks
        it[Keys.fallbackProviders] = providers.joinToString(",") { name -> name.name }
    }
    suspend fun setReducedMotion(value: Boolean) = store.edit { it[Keys.reducedMotion] = value }
    suspend fun setTerminalLogging(value: Boolean) = store.edit { it[Keys.terminalLogging] = value }
    val mcpServers: Flow<List<McpServer>> = store.data.map { p -> runCatching { Json.decodeFromString(ListSerializer(McpServer.serializer()), p[Keys.mcp] ?: "[]") }.getOrDefault(emptyList()) }
    suspend fun saveMcp(servers: List<McpServer>) { require(servers.size <= 4); store.edit { it[Keys.mcp] = Json.encodeToString(ListSerializer(McpServer.serializer()), servers) } }
    suspend fun removeMcp(id: String) { val remaining = mcpServers.first().filterNot { it.id == id }; saveMcp(remaining) }
    private object Keys {
        val mcp = stringPreferencesKey("mcp_servers_v2")
        val retries = intPreferencesKey("connection_retries"); val fallbacks = booleanPreferencesKey("allow_fallbacks")
        val fallbackProviders = stringSetPreferencesKey("fallback_providers_unused").let { stringPreferencesKey("fallback_providers") }
        val reducedMotion = booleanPreferencesKey("reduced_motion"); val terminalLogging = booleanPreferencesKey("terminal_logging")
        val language = stringPreferencesKey("language"); val theme = stringPreferencesKey("theme")
        val workspace = stringPreferencesKey("workspace"); val conversation = longPreferencesKey("conversation")
        val provider = stringPreferencesKey("provider"); val mode = stringPreferencesKey("agent_mode")
        val authorName = stringPreferencesKey("author_name"); val authorEmail = stringPreferencesKey("author_email")
        val remote = stringPreferencesKey("git_remote")
    }
}
