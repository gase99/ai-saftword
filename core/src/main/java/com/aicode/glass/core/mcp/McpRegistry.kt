package com.aicode.glass.core.mcp

import com.aicode.glass.core.data.SettingsRepository
import com.aicode.glass.core.security.SecretStore
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.JsonObject
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

data class McpState(val connections: List<McpConnection> = emptyList(), val busy: Boolean = false, val error: String? = null, val refreshedAt: Long = 0)
@Singleton
class McpRegistry @Inject constructor(private val client: McpClient, private val settings: SettingsRepository, private val secrets: SecretStore) {
    private val mutex = Mutex(); private val _state = MutableStateFlow(McpState()); val state = _state.asStateFlow()
    val servers = settings.mcpServers
    suspend fun token(id: String) = secrets.get("mcp_$id")
    suspend fun setToken(id: String, value: String) { if(value.isBlank()) secrets.delete("mcp_$id") else secrets.set("mcp_$id", value.trim()) }
    fun hasToken(id: String) = secrets.contains("mcp_$id")
    fun find(name: String): Pair<McpConnection, McpTool>? = _state.value.connections.firstNotNullOfOrNull { connection -> connection.tools.find { it.qualifiedName == name }?.let { connection to it } }
    suspend fun refresh(): McpState = mutex.withLock {
        _state.update { it.copy(busy = true, error = null) }
        val connections = mutableListOf<McpConnection>(); val errors = mutableListOf<String>()
        servers.first().filter { it.enabled }.take(4).forEach { server ->
            try { connections += client.connect(server, token(server.id)) }
            catch(e: kotlinx.coroutines.CancellationException) { throw e }
            catch(e: Exception) { errors += server.name + ": " + (e.message ?: "connection failed").take(300).replace(token(server.id).ifBlank { "\u0000" }, "[redacted]").replace(Regex("Bearer\\s+\\S+"), "Bearer [redacted]") }
        }
        val result = McpState(connections, false, errors.joinToString("\n").ifBlank { null }, System.currentTimeMillis()); _state.value = result; result
    }
    suspend fun call(connection: McpConnection, tool: String, args: JsonObject) = client.call(connection, tool, args, token(connection.server.id))
    fun clear() { _state.value = McpState() }
}
