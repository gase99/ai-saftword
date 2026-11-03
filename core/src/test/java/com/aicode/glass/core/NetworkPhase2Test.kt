package com.aicode.glass.core

import com.aicode.glass.core.network.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.TimeUnit

class NetworkPhase2Test {
    private fun client() = OkHttpClient.Builder().addInterceptor(LocalHttpInterceptor()).retryOnConnectionFailure(false).build()
    private fun config() = ProviderConfig(ProviderType.CUSTOM, "test", allowLocalHttp = true)
    private fun response(body: String, code: Int = 200) = MockResponse().setResponseCode(code).setHeader("Content-Type", "text/event-stream").setBody(body)
    private val text = """data: {"choices":[{"delta":{"content":"hello"}}]}

data: [DONE]

"""
    @Test fun endpointPolicyBlocksPublicHttpAndMetadataAddresses() {
        EndpointPolicy.validate("https://api.example.com/v1")
        EndpointPolicy.validate("http://192.168.1.10:11434/v1", true)
        listOf("http://public.example/v1", "http://169.254.169.254/latest", "http://localhost.evil.example/v1").forEach { assertThrows(AiException::class.java) { EndpointPolicy.validate(it, true) } }
        assertThrows(AiException::class.java) { EndpointPolicy.validate("http://localhost:11434/v1", false) }
    }
    @Test fun retriesConnectionBeforeFirstToken() = runTest {
        val server = MockWebServer(); server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(503).setBody("retry"))
            server.enqueue(response(text))
            val events = ResilientAiClient(StreamingAiClient(client())).stream(config().copy(endpoint = server.url("/v1").toString()), "secret123456", "sys", listOf(AiMessage("user", "hi")), emptyList()).toList()
            assertTrue(events.any { it is AiEvent.Retry }); assertEquals("hello", events.filterIsInstance<AiEvent.Text>().single().value); assertEquals(2, server.requestCount)
        } finally { server.shutdown() }
    }
    @Test fun neverReplaysMidstreamFailure() = runTest {
        val server = MockWebServer(); server.start()
        try {
            server.enqueue(response("data: {\"choices\":[{\"delta\":{\"content\":\"partial\"}}]}\n\n").setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
            val seen = mutableListOf<AiEvent>()
            assertThrows(AiException::class.java) { kotlinx.coroutines.runBlocking { ResilientAiClient(StreamingAiClient(client())).stream(config().copy(endpoint = server.url("/v1").toString()), "secret123456", "sys", listOf(AiMessage("user", "hi")), emptyList()).toList(seen) } }
            assertEquals(1, server.requestCount); assertEquals(1, seen.filterIsInstance<AiEvent.Text>().size)
        } finally { server.shutdown() }
    }
    @Test fun rotatesKeysAndCanUseExplicitFallback() = runTest {
        val server = MockWebServer(); server.start()
        try {
            server.enqueue(response("bad key", 401)); server.enqueue(response(text))
            val a = KeyAttempt(config().copy(endpoint = server.url("/a").toString()), "first-secret", "1")
            val b = a.copy(key = "second-secret", label = "2")
            val events = ResilientAiClient(StreamingAiClient(client())).streamWithKeys(listOf(a,b), "sys", listOf(AiMessage("user", "hi")), emptyList(), ConnectionPolicy(0,false)).toList()
            assertEquals("hello", events.filterIsInstance<AiEvent.Text>().single().value)
            assertTrue(server.takeRequest(1, TimeUnit.SECONDS)!!.getHeader("Authorization")!!.contains("first-secret"))
            assertTrue(server.takeRequest(1, TimeUnit.SECONDS)!!.getHeader("Authorization")!!.contains("second-secret"))
        } finally { server.shutdown() }
    }
    @Test fun fallbackIsOptInAndEmptyStreamsFail() = runTest {
        val server = MockWebServer(); server.start()
        try {
            server.enqueue(response("bad", 401)); server.enqueue(response(text))
            val a = KeyAttempt(config().copy(endpoint = server.url("/a").toString()), "first-secret", "1")
            val b = a.copy(config = a.config.copy(provider = ProviderType.OLLAMA), key = "", label = "2")
            assertThrows(AiException::class.java) { kotlinx.coroutines.runBlocking { ResilientAiClient(StreamingAiClient(client())).streamWithKeys(listOf(a,b), "sys", listOf(AiMessage("user","hi")), emptyList(), ConnectionPolicy(0,false)).toList() } }
            assertEquals(1, server.requestCount)
            server.enqueue(response("data: [DONE]\n\n"))
            assertThrows(AiException::class.java) { kotlinx.coroutines.runBlocking { StreamingAiClient(client()).stream(a.config, a.key, "sys", listOf(AiMessage("user","hi")), emptyList()).toList() } }
        } finally { server.shutdown() }
    }
    @Test fun ollamaUsesOpenAiProtocolWithoutApiKey() = runTest {
        val server = MockWebServer(); server.start()
        try {
            server.enqueue(response(text)); val cfg = ProviderConfig(ProviderType.OLLAMA, "local", server.url("/v1").toString(), true)
            val events = StreamingAiClient(client()).stream(cfg, "", "sys", listOf(AiMessage("user","hi")), emptyList()).toList()
            assertEquals("hello", events.filterIsInstance<AiEvent.Text>().single().value)
            val request = server.takeRequest(1, TimeUnit.SECONDS)!!; assertEquals("/v1/chat/completions", request.path); assertNull(request.getHeader("Authorization"))
        } finally { server.shutdown() }
    }
}
