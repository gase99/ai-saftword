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
import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

interface AiClient { fun stream(config: ProviderConfig, key: String, system: String, messages: List<AiMessage>, tools: List<ToolSpec>): Flow<AiEvent> }

@Singleton
class StreamingAiClient @Inject constructor(private val http: OkHttpClient) : AiClient {
    private val client = http
    override fun stream(config: ProviderConfig, key: String, system: String, messages: List<AiMessage>, tools: List<ToolSpec>): Flow<AiEvent> = callbackFlow {
        if (key.isBlank() && config.provider != ProviderType.OLLAMA) throw AiException("Add an API key in Settings before sending a message.")
        val base = if (config.provider == ProviderType.CUSTOM || config.provider == ProviderType.OLLAMA) config.endpoint.trim().trimEnd('/').ifBlank { config.provider.baseUrl } else config.provider.baseUrl
        EndpointPolicy.validate(base, config.allowLocalHttp)
        if (config.model.isBlank() || config.model.length > 160) throw AiException("Choose a valid model in Settings.")
        val codec = Codecs.create(config.provider)
        val url = when (config.provider) {
            ProviderType.ANTHROPIC -> "$base/messages"
            ProviderType.GEMINI -> "$base/models/${config.model}:streamGenerateContent?alt=sse"
            else -> "$base/chat/completions"
        }
        val builder = Request.Builder().url(url).apply { if (config.allowLocalHttp) header("X-AiCode-Local-HTTP", "enabled") }.post(codec.request(config, system, messages, tools).toRequestBody("application/json; charset=utf-8".toMediaType()))
            .header("Accept", "text/event-stream").header("Content-Type", "application/json")
        when (config.provider) {
            ProviderType.ANTHROPIC -> builder.header("x-api-key", key).header("anthropic-version", "2023-06-01")
            ProviderType.GEMINI -> builder.header("x-goog-api-key", key)
            else -> if (key.isNotBlank()) builder.header("Authorization", "Bearer $key")
        }
        var finished = false; var received = false; var semantic = false
        fun accept(data: String) {
            if (data.trim() == "[DONE]") {
                if (!finished) { codec.finish().forEach { if(it is AiEvent.Text || it is AiEvent.Call) semantic = true; trySendBlocking(it) }; finished = true }
                if (!semantic) { channel.close(AiException("Provider closed without text or tool calls.")); return }
                close(); return
            }
            codec.event(data).forEach { if(it is AiEvent.Text || it is AiEvent.Call) semantic = true; trySendBlocking(it) }
        }
        val source = EventSources.createFactory(client).newEventSource(builder.build(), object : EventSourceListener() {
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
                val body = try { response?.body?.string()?.take(1600) ?: "" } catch (_: Exception) { "" }
                val detail = sanitize(body.ifBlank { t?.message ?: "Connection interrupted" }, key)
                channel.close(AiException("Provider request failed${response?.code?.let { " (HTTP $it)" } ?: ""}: $detail", response?.code, t))
            }
        })
        awaitClose { source.cancel() }
    }.buffer(1024, onBufferOverflow = BufferOverflow.SUSPEND).flowOn(Dispatchers.IO)

    companion object {
        fun validateEndpoint(endpoint: String) {
            val uri = try { URI(endpoint) } catch (_: Exception) { throw AiException("Invalid endpoint URL.") }
            EndpointPolicy.validate(endpoint)
        }
        fun sanitize(text: String, key: String): String = (if (key.length > 5) text.replace(key, "[redacted]") else text)
            .replace(Regex("Bearer\\s+[A-Za-z0-9._~+/-]+=*"), "Bearer [redacted]").take(1800)
    }
}
