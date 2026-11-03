package com.aicode.glass.core.network

import kotlinx.serialization.json.*

enum class ProviderType(val baseUrl: String, val defaultModel: String) {
    OPENAI("https://api.openai.com/v1", "gpt-4o-mini"),
    ANTHROPIC("https://api.anthropic.com/v1", "claude-sonnet-4-20250514"),
    GEMINI("https://generativelanguage.googleapis.com/v1beta", "gemini-2.5-flash"),
    CUSTOM("https://api.openai.com/v1", "gpt-4o-mini"),
    OLLAMA("http://10.0.2.2:11434/v1", "llama3.2")
}

data class ProviderConfig(val provider: ProviderType, val model: String, val endpoint: String = "", val allowLocalHttp: Boolean = false)
data class ToolCall(val id: String, val name: String, val arguments: String)
data class AiMessage(val role: String, val content: String, val toolCalls: List<ToolCall> = emptyList(), val toolCallId: String? = null, val toolName: String? = null)
data class ToolSpec(val name: String, val description: String, val parameters: JsonObject)
sealed interface AiEvent {
    data class Text(val value: String) : AiEvent
    data class Call(val value: ToolCall) : AiEvent
    data class Usage(val inputTokens: Int, val outputTokens: Int) : AiEvent
    data class Retry(val attempt: Int, val reason: String, val delayMillis: Long) : AiEvent
}
class AiException(message: String, val statusCode: Int? = null, cause: Throwable? = null) : Exception(message, cause)

object JsonTools {
    val json = Json { ignoreUnknownKeys = true; isLenient = false; encodeDefaults = true }
    fun schema(properties: Map<String, String>, required: List<String>, descriptions: Map<String, String> = emptyMap()): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") { properties.forEach { (name, type) -> putJsonObject(name) {
            put("type", type); put("description", descriptions[name] ?: name)
        } } }
        putJsonArray("required") { required.forEach { add(it) } }
        put("additionalProperties", false)
    }
    fun parseArgs(value: String): JsonObject = json.parseToJsonElement(value).jsonObject
}
