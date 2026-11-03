package com.aicode.glass.core.network

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min
import kotlin.random.Random

data class KeyAttempt(val config: ProviderConfig, val key: String, val label: String)
data class ConnectionPolicy(val retries: Int = 2, val fallbacks: Boolean = false)
class KeyCooldowns {
    private val until = mutableMapOf<String, Long>()
    @Synchronized fun available(label: String, now: Long = System.currentTimeMillis()) = (until[label] ?: 0) <= now
    @Synchronized fun cool(label: String, millis: Long, now: Long = System.currentTimeMillis()) { until[label] = now + millis.coerceIn(1000, 300_000) }
    @Synchronized fun clear(label: String) { until.remove(label) }
}
object RetryDecision {
    fun retryable(error: Throwable): Boolean = error is IOException || error is AiException && (error.statusCode == null || error.statusCode in setOf(408, 409, 429, 500, 502, 503, 504))
    fun backoff(attempt: Int, random: Int = Random.nextInt(250)): Long = min(8000L, 500L * (1L shl attempt.coerceIn(0, 4))) + random
}

/** Retries only before the first semantic event: never replays partial text or an executed tool call. */
@Singleton
class ResilientAiClient @Inject constructor(private val transport: StreamingAiClient) : AiClient {
    private val cooldowns = KeyCooldowns()
    override fun stream(config: ProviderConfig, key: String, system: String, messages: List<AiMessage>, tools: List<ToolSpec>): Flow<AiEvent> =
        streamWithKeys(listOf(KeyAttempt(config, key, config.provider.name + ":1")), system, messages, tools, ConnectionPolicy())
    fun streamWithKeys(attempts: List<KeyAttempt>, system: String, messages: List<AiMessage>, tools: List<ToolSpec>, policy: ConnectionPolicy): Flow<AiEvent> = flow {
        require(attempts.isNotEmpty()) { "No provider connection configured." }
        val primary = attempts.first().config.provider
        val candidates = attempts.filter { policy.fallbacks || it.config.provider == primary }
        var last: Throwable? = null; var count = 0; val budget = (retriesLabel(policy.retries) + 1) * candidates.size
        for (attempt in candidates) {
            val label = attempt.config.provider.name + ":" + attempt.label
            if (!cooldowns.available(label)) { last = AiException("Provider/key is cooling down after a rate limit."); continue }
            val retries = policy.retries.coerceIn(0, 3)
            for (retry in 0..retries) {
                var emitted = false
                try {
                    transport.stream(attempt.config, attempt.key, system, messages, tools).collect {
                        if (it is AiEvent.Text || it is AiEvent.Call) emitted = true
                        emit(it)
                    }
                    cooldowns.clear(label); return@flow
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    last = e
                    if (e is AiException && e.statusCode == 429) cooldowns.cool(label, 60_000)
                    if (e is AiException && e.statusCode in setOf(401, 403)) break
                    if (emitted || !RetryDecision.retryable(e) || count >= budget) break
                    val wait = RetryDecision.backoff(retry); count++
                    emit(AiEvent.Retry(count, redactedReason(e), wait)); delay(wait)
                }
            }
        }
        throw last ?: AiException("All configured API keys are cooling down. Wait before retrying.")
    }.buffer(1024)
    private fun retriesLabel(value: Int) = value.coerceIn(0, 3)
    private fun redactedReason(error: Throwable): String = when {
        error is AiException && error.statusCode == 429 -> "rate_limit"; error is AiException && error.statusCode != null -> "http_${error.statusCode}"; else -> "network"
    }
}
