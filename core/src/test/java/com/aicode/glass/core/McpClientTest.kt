package com.aicode.glass.core

import com.aicode.glass.core.mcp.*
import com.aicode.glass.core.network.LocalHttpInterceptor
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class McpClientTest {
    private val serverConfig = McpServer("testserver", "Test", "", true, true)
    private fun client() = McpClient(OkHttpClient.Builder().addInterceptor(LocalHttpInterceptor()).build())
    private fun json(body: String, session: String? = "session-1") = MockResponse().setHeader("Content-Type", "application/json").apply { session?.let { setHeader("Mcp-Session-Id", it) } }.setBody(body)
    @Test fun initializesDiscoversAndCallsJsonAndSse() = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            val config = serverConfig.copy(endpoint = server.url("/mcp").toString())
            server.enqueue(json("""{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-03-26","capabilities":{},"serverInfo":{"name":"test","version":"1"}}}"""))
            server.enqueue(MockResponse().setResponseCode(202))
            server.enqueue(json("""{"jsonrpc":"2.0","id":2,"result":{"tools":[{"name":"echo","description":"Echo input","inputSchema":{"type":"object","properties":{"text":{"type":"string"}},"required":["text"],"additionalProperties":false}}]}}"""))
            val connection = client().connect(config, "mcp-secret-token")
            assertEquals(1, connection.tools.size); assertEquals("session-1", connection.session)
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody("event: message\ndata: {\"jsonrpc\":\"2.0\",\"id\":3,\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"hello\"}],\"isError\":false}}\n\n"))
            val result = client().call(connection, "echo", buildJsonObject { put("text", "hi") }, "mcp-secret-token")
            assertEquals("hello", result.text); assertFalse(result.isError)
            val init = server.takeRequest(1, TimeUnit.SECONDS)!!; assertEquals("Bearer mcp-secret-token", init.getHeader("Authorization")); assertNull(init.getHeader("X-AiCode-Local-HTTP"))
            val notification = server.takeRequest(1, TimeUnit.SECONDS)!!; assertTrue(notification.body.readUtf8().contains("notifications/initialized")); assertEquals("session-1", notification.getHeader("Mcp-Session-Id"))
            server.takeRequest(1, TimeUnit.SECONDS); val call = server.takeRequest(1, TimeUnit.SECONDS)!!; assertTrue(call.body.readUtf8().contains("tools/call"))
        } finally { server.shutdown() }
    }
    @Test fun schemaRejectsMissingUnexpectedAndWrongType() {
        val schema = buildJsonObject { put("type","object"); putJsonObject("properties") { putJsonObject("count") { put("type","integer") }; putJsonObject("text") { put("type","string") } }; putJsonArray("required") { add("text") }; put("additionalProperties",false) }
        SchemaValidator.validate(schema, buildJsonObject { put("text","ok"); put("count",2) })
        listOf(buildJsonObject { put("count",2) }, buildJsonObject { put("text","ok"); put("extra",true) }, buildJsonObject { put("text",5) }).forEach { assertThrows(IllegalArgumentException::class.java) { SchemaValidator.validate(schema,it) } }
    }
    @Test fun protocolErrorsAreNotTreatedAsSuccess() = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            server.enqueue(json("""{"jsonrpc":"2.0","id":1,"error":{"code":-32602,"message":"bad init"}}"""))
            val error = runCatching { client().connect(serverConfig.copy(endpoint=server.url("/mcp").toString()), "") }.exceptionOrNull()
            assertTrue(error is java.io.IOException); assertTrue(error!!.message!!.contains("bad init"))
        } finally { server.shutdown() }
    }
}
