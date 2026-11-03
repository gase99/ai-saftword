package com.aicode.glass.core

import com.aicode.glass.core.network.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.Assert.*

/** Explicit external smoke tests. Never enabled by ordinary PR CI; no credentials are printed. */
class LiveProviderTest {
    private fun enabled(provider: ProviderType) {
        assumeTrue("Live validation not enabled", System.getProperty("liveProviders") == "true")
        assumeTrue("Credential not configured", provider == ProviderType.OLLAMA || !System.getenv("${provider.name}_API_KEY").isNullOrBlank())
    }
    private fun verify(provider: ProviderType) = runBlocking {
        enabled(provider)
        val env = System.getenv("${provider.name}_API_KEY") ?: ""
        val config = if(provider == ProviderType.OLLAMA) ProviderConfig(provider, System.getenv("OLLAMA_MODEL") ?: "llama3.2", System.getenv("OLLAMA_ENDPOINT") ?: "http://127.0.0.1:11434/v1", true) else ProviderConfig(provider, provider.defaultModel)
        val client = StreamingAiClient(OkHttpClient.Builder().addInterceptor(LocalHttpInterceptor()).build())
        val events = client.stream(config, env, "Reply with exactly OK.", listOf(AiMessage("user","Connection smoke test. Reply OK.")), emptyList()).toList()
        val output = events.filterIsInstance<AiEvent.Text>().joinToString("") { it.value }
        assertTrue("Provider returned no text", output.isNotBlank())
        // Avoid recording provider output or request headers in test reports.
    }
    @Test fun openAi() = verify(ProviderType.OPENAI)
    @Test fun anthropic() = verify(ProviderType.ANTHROPIC)
    @Test fun gemini() = verify(ProviderType.GEMINI)
    @Test fun ollama() = verify(ProviderType.OLLAMA)
}
