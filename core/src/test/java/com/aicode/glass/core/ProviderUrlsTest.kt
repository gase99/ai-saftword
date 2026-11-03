package com.aicode.glass.core

import com.aicode.glass.core.network.*
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class ProviderUrlsTest {
    private fun ollama(endpoint: String, model: String = "gpt-oss:20b") = ProviderConfig(ProviderType.OLLAMA, model, endpoint, true)

    @Test fun ollamaCloudBaseIsResolvedToTheOpenAiCompatiblePath() {
        listOf(
            "https://ollama.com",
            "https://ollama.com/",
            "https://ollama.com/api",
            "https://ollama.com/api/chat",
            "https://ollama.com/api/v1",
            "https://ollama.com/v1",
            "https://ollama.com/v1/chat/completions"
        ).forEach { endpoint ->
            assertEquals(endpoint, "https://ollama.com/v1/chat/completions", ProviderUrls.streamUrl(ollama(endpoint)))
        }
        assertEquals("http://127.0.0.1:11434/v1/chat/completions", ProviderUrls.streamUrl(ollama("http://127.0.0.1:11434")))
        assertEquals("http://10.0.2.2:11434/v1/chat/completions", ProviderUrls.streamUrl(ollama("http://10.0.2.2:11434/v1")))
        // An explicit proxy path is preserved verbatim.
        assertEquals("https://proxy.example/ollama/chat/completions", ProviderUrls.streamUrl(ollama("https://proxy.example/ollama")))
    }

    @Test fun geminiStreamsThroughTheVersionedModelMethod() {
        val config = ProviderConfig(ProviderType.GEMINI, ProviderType.GEMINI.defaultModel)
        val url = ProviderUrls.streamUrl(config)
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/models/${ProviderType.GEMINI.defaultModel}:streamGenerateContent?alt=sse",
            url
        )
        assertFalse("Deprecated Gemini models return 404", ProviderType.GEMINI.defaultModel in setOf("gemini-pro", "gemini-1.5-flash", "gemini-2.5-flash"))
        // A bare host override still needs the v1beta version segment.
        assertEquals("https://gemini-proxy.example/v1beta/models?pageSize=200", ProviderUrls.modelsUrl(ProviderConfig(ProviderType.GEMINI, "x", "https://gemini-proxy.example")))
    }

    @Test fun remoteOllamaRequiresAKeyBeforeAnyRequest() = runBlocking {
        val client = StreamingAiClient(HttpClients.provider())
        val error = runCatching {
            client.stream(ollama("https://ollama.com"), "", "sys", listOf(AiMessage("user", "hi")), emptyList()).toList()
        }.exceptionOrNull()
        assertTrue("Expected a credential error, got $error", error is AiException)
        assertTrue(error!!.message!!.contains("API key"))
    }

    @Test fun ollamaSendsBearerAndSurfacesProviderErrorBody() = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            server.enqueue(
                MockResponse().setResponseCode(404).setHeader("Content-Type", "application/json")
                    .setBody("""{"error":"path \"/chat/completions\" not found"}""")
            )
            val transport = StreamingAiClient(HttpClients.provider())
            val config = ProviderConfig(ProviderType.OLLAMA, "gpt-oss:20b", server.url("/v1").toString(), true)
            val error = runCatching {
                transport.stream(config, "ollama-cloud-secret", "sys", listOf(AiMessage("user", "hi")), emptyList()).toList()
            }.exceptionOrNull()
            assertTrue("Expected AiException, got $error", error is AiException)
            val failure = error as AiException
            assertEquals(404, failure.statusCode ?: 0)
            val message = failure.message.orEmpty()
            assertTrue("Provider body missing from the error: $message", message.contains("not found"))
            assertTrue("HTTP status missing from the error: $message", message.contains("HTTP 404"))
            assertFalse("Credential leaked into the error", message.contains("ollama-cloud-secret"))
            val request = server.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals("/v1/chat/completions", request.path)
            assertEquals("Bearer ollama-cloud-secret", request.getHeader("Authorization"))
        } finally { server.shutdown() }
    }

    @Test fun localOllamaStaysUnauthenticated() = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            server.enqueue(
                MockResponse().setResponseCode(200).setHeader("Content-Type", "text/event-stream")
                    .setBody("data: {\"choices\":[{\"delta\":{\"content\":\"hello\"}}]}\n\ndata: [DONE]\n\n")
            )
            val config = ProviderConfig(ProviderType.OLLAMA, "llama3.2", server.url("/v1").toString(), true)
            val events = StreamingAiClient(HttpClients.provider()).stream(config, "", "sys", listOf(AiMessage("user", "hi")), emptyList()).toList()
            assertEquals("hello", events.filterIsInstance<AiEvent.Text>().joinToString("") { it.value })
            val request = server.takeRequest(2, TimeUnit.SECONDS)!!
            assertNull(request.getHeader("Authorization"))
            assertEquals("/v1/chat/completions", request.path)
        } finally { server.shutdown() }
    }
}
