package com.aicode.glass.core

import com.aicode.glass.core.network.*
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Explicit external smoke tests. Never enabled by ordinary PR CI; no credentials are printed.
 *
 * Enable with `-PliveProviders=true` (or `-DliveProviders=true`) plus the matching
 * `<PROVIDER>_API_KEY` environment variable. Optional overrides: `<PROVIDER>_MODEL` and
 * `<PROVIDER>_ENDPOINT`, e.g. `OLLAMA_ENDPOINT=https://ollama.com` for Ollama Cloud.
 */
class LiveProviderTest {
    private val client: OkHttpClient by lazy { HttpClients.provider() }

    private fun env(name: String): String = System.getenv(name)?.trim().orEmpty()

    private fun endpoint(provider: ProviderType): String =
        env("${provider.name}_ENDPOINT").ifBlank { if (provider == ProviderType.OLLAMA) LOCAL_OLLAMA else "" }

    private fun model(provider: ProviderType): String = env("${provider.name}_MODEL").ifBlank {
        // Ollama Cloud does not serve the local `llama3.2` tag; gpt-oss:20b is on the cloud tier.
        if (provider == ProviderType.OLLAMA && ProviderUrls.isOllamaCloud(endpoint(provider))) CLOUD_OLLAMA_MODEL
        else provider.defaultModel
    }

    private fun config(provider: ProviderType): ProviderConfig {
        val endpoint = endpoint(provider)
        return ProviderConfig(provider, model(provider), endpoint, EndpointPolicy.isLocalEndpoint(endpoint))
    }

    /** Skips (never silently passes) when the run is not explicitly enabled or lacks credentials. */
    private fun enabled(provider: ProviderType): ProviderConfig {
        assumeTrue("Live validation not enabled: pass -PliveProviders=true", System.getProperty("liveProviders") == "true")
        val config = config(provider)
        val needsKey = provider != ProviderType.OLLAMA || ProviderUrls.isOllamaCloud(config.endpoint)
        assumeTrue(
            "${provider.name}_API_KEY is not configured, so this live test cannot authenticate",
            !needsKey || env("${provider.name}_API_KEY").isNotBlank()
        )
        return config
    }

    private fun verify(provider: ProviderType) = runBlocking {
        val config = enabled(provider)
        val key = env("${provider.name}_API_KEY")
        val transport = StreamingAiClient(client)
        println("[live] ${provider.name}: model=${config.model} url=${ProviderUrls.streamUrl(config)} key=${if (key.isBlank()) "none" else "present"}")
        val events = try {
            transport.stream(config, key, "Reply with exactly OK.", listOf(AiMessage("user", "Connection smoke test. Reply OK.")), emptyList()).toList()
        } catch (e: AiException) {
            // Confirm which models this credential can actually use before failing the job.
            printAvailableModels(provider, config, key)
            throw AssertionError("${provider.name} live request failed: ${e.message}", e)
        } catch (e: Exception) {
            throw AssertionError("${provider.name} live request crashed: ${e.javaClass.simpleName}: ${e.message}", e)
        }
        val output = events.filterIsInstance<AiEvent.Text>().joinToString("") { it.value }
        assertTrue("${provider.name} returned no text for model ${config.model}", output.isNotBlank())
        // Report size only: provider output and request headers stay out of the logs.
        println("[live] ${provider.name}: received ${output.length} characters")
    }

    private fun printAvailableModels(provider: ProviderType, config: ProviderConfig, key: String) {
        val models = runCatching { models(provider, config, key) }.getOrNull().orEmpty()
        if (models.isEmpty()) return
        println("[live] ${provider.name}: ${provider.name}_MODEL must be one of -> ${models.take(25).joinToString(", ")}")
    }

    private fun models(provider: ProviderType, config: ProviderConfig, key: String): List<String> {
        val url = ProviderUrls.modelsUrl(config) ?: return emptyList()
        val request = Request.Builder().url(url).apply {
            when (provider) {
                ProviderType.GEMINI -> header("x-goog-api-key", key)
                ProviderType.ANTHROPIC -> header("x-api-key", key).header("anthropic-version", "2023-06-01")
                else -> if (key.isNotBlank()) header("Authorization", "Bearer $key")
            }
        }.get().build()
        return client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) return listOf("HTTP ${response.code}: ${body.take(200)}")
            val root = com.aicode.glass.core.network.JsonTools.json.parseToJsonElement(body).jsonObject
            when (provider) {
                ProviderType.GEMINI -> (root["models"] as? JsonArray ?: JsonArray(emptyList()))
                    .mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.contentOrNull?.removePrefix("models/") }
                else -> (root["data"] as? JsonArray ?: JsonArray(emptyList()))
                    .mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.contentOrNull }
            }
        }
    }

    @Test fun openai() = verify(ProviderType.OPENAI)
    @Test fun anthropic() = verify(ProviderType.ANTHROPIC)
    @Test fun gemini() = verify(ProviderType.GEMINI)
    @Test fun ollama() = verify(ProviderType.OLLAMA)

    private companion object {
        const val LOCAL_OLLAMA = "http://127.0.0.1:11434/v1"
        const val CLOUD_OLLAMA_MODEL = "gpt-oss:20b"
    }
}
