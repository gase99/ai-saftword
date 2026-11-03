package com.aicode.glass.core.mcp

import com.aicode.glass.core.network.AiException
import com.aicode.glass.core.network.EndpointPolicy
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@Serializable data class McpServer(val id: String, val name: String, val endpoint: String, val enabled: Boolean = true, val allowLocalHttp: Boolean = false)
@Serializable data class McpTool(val serverId: String, val name: String, val description: String, val schema: JsonObject) {
    val qualifiedName: String get() = "mcp_" + serverId.filter { it.isLetterOrDigit() }.take(8) + "_" + name.filter { it.isLetterOrDigit() || it == '_' }.take(48)
}
data class McpResult(val text: String, val isError: Boolean)
data class McpConnection(val server: McpServer, val protocol: String, val session: String?, val tools: List<McpTool>)

/** MCP Streamable HTTP client. No shell/stdio transport, arbitrary URL fetch or silent tool execution. */
@Singleton
class McpClient @Inject constructor(private val http: OkHttpClient) {
    private val nextId = ThreadLocal.withInitial { AtomicLong(1) }
    
    suspend fun connect(server: McpServer, token: String): McpConnection = withContext(Dispatchers.IO) {
        EndpointPolicy.validate(server.endpoint, server.allowLocalHttp)
        val client = http.newBuilder().callTimeout(45, java.util.concurrent.TimeUnit.SECONDS).build()
        val headers = Headers.Builder().apply { if(token.isNotBlank()) set("Authorization", "Bearer $token") }.build()
        val init = rpc(client, server, headers, null, "initialize", buildJsonObject {
            put("protocolVersion", "2025-03-26"); putJsonObject("capabilities", {})
            putJsonObject("clientInfo") { put("name", "AiCodeGlass"); put("version", "2.0.0") }
        })
        val protocol = init.result.getValue("protocolVersion").jsonPrimitive.content
        if (protocol !in setOf("2024-11-05", "2025-03-26", "2025-06-18")) throw IOException("Unsupported MCP protocol: $protocol")
        val session = init.session
        rpc(client, server, headers, session, "notifications/initialized", JsonObject(emptyMap()), notification = true)
        val tools = mutableListOf<McpTool>(); var cursor: String? = null
        do {
            val result = rpc(client, server, headers, session, "tools/list", buildJsonObject { if(cursor != null) put("cursor", cursor) }).result
            result["tools"]?.jsonArray?.forEach { element ->
                val obj = element.jsonObject; val name = obj.getValue("name").jsonPrimitive.content
                require(name.matches(Regex("[A-Za-z0-9_-]{1,64}"))) { "Invalid MCP tool name" }
                val schema = obj["inputSchema"]?.jsonObject ?: JsonToolsSchema.empty
                require(schema["type"]?.jsonPrimitive?.content == "object") { "MCP input schema must be an object" }
                tools += McpTool(server.id, name, (obj["description"]?.jsonPrimitive?.content ?: name).take(1000), schema)
            }
            cursor = result["nextCursor"]?.jsonPrimitive?.contentOrNull
        } while(cursor != null && tools.size < 64)
        if(tools.size > 64) throw IOException("MCP server exposes too many tools (maximum 64).")
        McpConnection(server, protocol, session, tools.distinctBy { it.qualifiedName })
    }
    suspend fun call(connection: McpConnection, tool: String, arguments: JsonObject, token: String): McpResult = withContext(Dispatchers.IO) {
        val client = http.newBuilder().callTimeout(90, java.util.concurrent.TimeUnit.SECONDS).build()
        val headers = Headers.Builder().apply { if(token.isNotBlank()) set("Authorization", "Bearer $token") }.build()
        val result = rpc(client, connection.server, headers, connection.session, "tools/call", buildJsonObject { put("name", tool); put("arguments", arguments) }).result
        val text = result["content"]?.jsonArray?.mapNotNull { element ->
            val obj = element.jsonObject; when(obj["type"]?.jsonPrimitive?.content) { "text" -> obj["text"]?.jsonPrimitive?.content; "image", "audio", "resource" -> "[Non-text MCP content omitted]"; else -> null }
        }?.joinToString("\n") ?: result.toString()
        McpResult(text.take(24000), result["isError"]?.jsonPrimitive?.booleanOrNull ?: false)
    }
    private data class RpcResponse(val result: JsonObject, val session: String?)
    private suspend fun rpc(client: OkHttpClient, server: McpServer, headers: Headers, session: String?, method: String, params: JsonObject, notification: Boolean = false): RpcResponse {
        val id = if(notification) 0L else nextId.get().getAndIncrement()
        val payload = buildJsonObject { put("jsonrpc", "2.0"); if(!notification) put("id", id); put("method", method); put("params", params) }
        val request = Request.Builder().url(server.endpoint).headers(headers).header("Accept", "application/json, text/event-stream")
            .header("Content-Type", "application/json").header("MCP-Protocol-Version", "2025-03-26")
            .apply { session?.let { header("Mcp-Session-Id", it) }; if(server.allowLocalHttp) header("X-AiCode-Local-HTTP", "enabled") }
            .post(payload.toString().toRequestBody("application/json".toMediaType())).build()
        val response = client.newCall(request).await()
        return response.use {
            if(!it.isSuccessful) throw AiException("MCP request failed (HTTP ${it.code}).", it.code)
            val newSession = it.header("Mcp-Session-Id") ?: session
            if(newSession != null && !newSession.matches(Regex("[A-Za-z0-9._:-]{1,256}"))) throw IOException("Invalid MCP session header")
            if(notification || it.code == 202) return@use RpcResponse(JsonObject(emptyMap()), newSession)
            val body = it.body ?: throw IOException("Empty MCP response")
            if(body.contentLength() > 1024 * 1024) throw IOException("MCP response exceeds 1 MB")
            val source = body.source(); source.request(1024L * 1024 + 1); if(source.buffer.size > 1024 * 1024) throw IOException("MCP response exceeds 1 MB"); val text = source.readString(Charsets.UTF_8)
            val element = if(it.header("Content-Type")?.contains("text/event-stream") == true) {
                text.lineSequence().filter { line -> line.startsWith("data:") }.map { line -> line.removePrefix("data:").trim() }.filter { it != "[DONE]" }
                    .map { Json.parseToJsonElement(it) }.firstOrNull { obj -> obj.jsonObject["id"]?.jsonPrimitive?.contentOrNull == id.toString() } ?: throw IOException("MCP stream ended without the request response")
            } else Json.parseToJsonElement(text)
            val obj = element.jsonObject
            obj["error"]?.let { error -> throw IOException("MCP error " + error.jsonObject["code"] + ": " + (error.jsonObject["message"]?.jsonPrimitive?.content ?: "request failed")) }
            if(obj["id"]?.jsonPrimitive?.contentOrNull != id.toString()) throw IOException("Mismatched MCP response ID")
            RpcResponse(obj["result"]?.jsonObject ?: throw IOException("Missing MCP result"), newSession)
        }
    }
    private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { if(continuation.isActive) continuation.resumeWithException(e) }
            override fun onResponse(call: Call, response: Response) { continuation.resume(response) }
        })
    }
}
private object JsonToolsSchema { val empty = buildJsonObject { put("type", "object"); putJsonObject("properties", {}) } }
