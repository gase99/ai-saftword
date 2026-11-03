package com.aicode.glass.core.network

import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.net.URI
import javax.inject.Inject
import javax.inject.Singleton

interface AiClient { fun stream(config: ProviderConfig, key: String, system: String, messages: List<AiMessage>, tools: List<ToolSpec>): Flow<AiEvent> }

@Singleton
class StreamingAiClient @Inject constructor(private val http: OkHttpClient) : AiClient {
    private val client = http
    override fun stream(config: ProviderConfig, key: String, system: String, messages: List<AiMessage>, tools: List<ToolSpec>): Flow<AiEvent> = callbackFlow {
        // A local Ollama server is unauthenticated by design; Ollama Cloud always needs a token.
        val base = ProviderUrls.base(config)
        val cloudOllama = config.provider == ProviderType.OLLAMA && ProviderUrls.isOllamaCloud(base)
        if (key.isBlank() && (config.provider != ProviderType.OLLAMA || cloudOllama)) {
            throw AiException(
                if (config.provider == ProviderType.OLLAMA)
                    "Ollama Cloud at $base requires a bearer token: add an API key in Settings (OLLAMA_API_KEY in CI)."
                else "Add an API key in Settings before sending a message."
            )
        }
        EndpointPolicy.validate(base, config.allowLocalHttp)
        if (config.model.isBlank() || config.model.length > 160) throw AiException("Choose a valid model in Settings.")
        val codec = Codecs.create(config.provider)
        val streamUrl = ProviderUrls.streamUrl(config)
        val builder = Request.Builder().url(streamUrl).apply {
            // The marker is only meaningful (and only needed) for cleartext local endpoints.
            if (streamUrl.startsWith("http://", ignoreCase = true)) header("X-AiCode-Local-HTTP", "enabled")
        }.post(codec.request(config, system, messages, tools).toRequestBody("application/json; charset=utf-8".toMediaType()))
            .header("Accept", "text/event-stream").header("Content-Type", "application/json")
        when (config.provider) {
            ProviderType.ANTHROPIC -> builder.header("x-api-key", key).header("anthropic-version", "2023-06-01")
            ProviderType.GEMINI -> builder.header("x-goog-api-key", key)
            // OpenAI, custom and Ollama Cloud all authenticate with a bearer token.
            else -> if (key.isNotBlank()) builder.header("Authorization", "Bearer $key")
        }
        val request = builder.build()
        var finished = false; var received = false; var semantic = false
        fun accept(data: String) {
            if (data.trim() == "[DONE]") {
                if (!finished) { codec.finish().forEach { if(it is AiEvent.Text || it is AiEvent.Call) semantic = true; trySendBlocking(it) }; finished = true }
                if (!semantic) { channel.close(AiException("Provider closed without text or tool calls.")); return }
                close(); return
            }
            codec.event(data).forEach { if(it is AiEvent.Text || it is AiEvent.Call) semantic = true; trySendBlocking(it) }
        }
        val source = EventSources.createFactory(client).newEventSource(request, object : EventSourceListener() {
            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                received = true
                try { accept(data) } catch (e: Exception) {
                    channel.close(AiException(sanitize(e.message ?: "Invalid provider response", key), cause = e)); eventSource.cancel()
                }
            }
            override fun onClosed(eventSource: EventSource) {
                try {
                    if (!finished) { codec.finish().forEach { if(it is AiEvent.Text || it is AiEvent.Call) semantic = true; trySendBlocking(it) }; finished = true }
                    if (!semantic) channel.close(AiException("Provider closed without text or tool calls.")) else close()
                } catch (e: Exception) { channel.close(e) }
            }
            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                // The provider body is the only reliable explanation for 400/401/403/404 answers:
                // keep it in the exception message and echo the full report to stderr when enabled.
                val report = describeFailure(config, streamUrl, response, t, key)
                if (debugHttp()) System.err.println("[AiCode Glass] " + report.details)
                channel.close(AiException(report.summary, response?.code, t))
            }
        })
        awaitClose { source.cancel() }
    }.buffer(1024, onBufferOverflow = BufferOverflow.SUSPEND).flowOn(Dispatchers.IO)

    companion object {
        private const val MAX_BODY = 2000

        fun validateEndpoint(endpoint: String) {
            val uri = try { URI(endpoint) } catch (_: Exception) { throw AiException("Invalid endpoint URL.") }
            EndpointPolicy.validate(endpoint)
        }
        fun sanitize(text: String, key: String, maxLength: Int = 1800): String = (if (key.length > 5) text.replace(key, "[redacted]") else text)
            .replace(Regex("Bearer\\s+[A-Za-z0-9._~+/-]+=*"), "Bearer [redacted]")
            .replace(Regex("([?&](?:key|api_key|apikey|access_token)=)[^&\\s\"]+"), "$1[redacted]")
            .take(maxLength)

        /** Verbose provider diagnostics are printed only for explicit live/debug runs. */
        fun debugHttp(): Boolean = System.getenv("AICODE_DEBUG_HTTP") == "true" ||
            System.getProperty("aicode.debug.http") == "true" ||
            System.getProperty("liveProviders") == "true"

        internal data class FailureReport(val summary: String, val details: String)

        internal fun describeFailure(config: ProviderConfig, url: String, response: Response?, cause: Throwable?, key: String): FailureReport {
            val status = response?.code
            val reason = response?.message.orEmpty().trim()
            val body = try { response?.body?.string()?.trim().orEmpty() } catch (_: Exception) { "" }
            val hint = hintFor(config, status, body, url)
            val statusText = if (status == null) "" else if (reason.isBlank()) " (HTTP $status)" else " (HTTP $status $reason)"
            val summary = buildString {
                append("Provider request failed").append(statusText)
                append(" — ").append(config.provider.name).append(' ').append(config.model)
                if (body.isNotBlank()) append(": ").append(body.take(700))
                else cause?.message?.let { append(": ").append(it.take(300)) }
                if (hint != null) append(" | ").append(hint)
            }
            val details = buildString {
                appendLine("live provider request failed")
                appendLine("  provider : ${config.provider.name}")
                appendLine("  model    : ${config.model}")
                appendLine("  url      : $url")
                if (status != null) appendLine("  status   : $status $reason".trimEnd())
                response?.header("content-type")?.let { appendLine("  content-type : $it") }
                (response?.header("x-request-id") ?: response?.header("request-id") ?: response?.header("x-goog-request-id"))?.let { appendLine("  request-id   : $it") }
                cause?.let { appendLine("  cause    : ${it.javaClass.name}: ${it.message}") }
                if (body.isNotBlank()) { appendLine("  body     :"); appendLine(body.take(MAX_BODY)) }
                hint?.let { appendLine("  hint     : $it") }
            }
            return FailureReport(sanitize(summary, key), sanitize(details, key, maxLength = 4000))
        }

        private fun hintFor(config: ProviderConfig, status: Int?, body: String, url: String): String? {
            val lower = body.lowercase()
            return when {
                status == 401 || status == 403 -> when (config.provider) {
                    ProviderType.OLLAMA -> "Ollama Cloud expects 'Authorization: Bearer <OLLAMA_API_KEY>'. Verify the OLLAMA_API_KEY secret and that the endpoint is https://ollama.com."
                    ProviderType.GEMINI -> "Gemini rejected the key: verify GEMINI_API_KEY and that the Generative Language API is enabled for its project."
                    else -> "${config.provider.name}_API_KEY was rejected by the provider."
                }
                status == 404 && config.provider == ProviderType.OLLAMA ->
                    "Ollama Cloud serves the OpenAI-compatible API at https://ollama.com/v1 (native routes are under /api). Endpoint was resolved to $url."
                status == 404 && config.provider == ProviderType.GEMINI ->
                    "Model '${config.model}' is not available to this key; pick a current model such as gemini-3.5-flash or gemini-3.8-flash."
                status == 404 -> "Check the endpoint path and the model name; GET /models lists what this credential can use."
                status == 400 && (lower.contains("model") || lower.contains("not found")) -> "Provider rejected the model '${config.model}'; check the model name."
                status == 429 -> "Provider rate limit or quota reached."
                status != null && status >= 500 -> "Provider-side failure; retry later."
                status == null -> "No HTTP response at all (DNS, TLS, proxy or timeout). Check the endpoint reachability, not the credential."
                else -> null
            }
        }
    }
}
