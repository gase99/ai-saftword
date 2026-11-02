package com.aicode.glass.core.network

import kotlinx.serialization.json.*
import com.aicode.glass.core.network.JsonTools.json

/** Provider-specific wire format isolated from UI, persistence and transport. */
interface ProviderCodec {
    fun request(config: ProviderConfig, system: String, messages: List<AiMessage>, tools: List<ToolSpec>): String
    fun event(data: String): List<AiEvent>
    fun finish(): List<AiEvent>
}

object Codecs { fun create(provider: ProviderType): ProviderCodec = when (provider) {
    ProviderType.OPENAI, ProviderType.CUSTOM -> OpenAiCodec()
    ProviderType.ANTHROPIC -> AnthropicCodec()
    ProviderType.GEMINI -> GeminiCodec()
} }

private fun JsonObject.str(key: String): String = this[key]?.jsonPrimitive?.contentOrNull ?: ""
private fun JsonObject.obj(key: String): JsonObject = this[key] as? JsonObject ?: JsonObject(emptyMap())
private fun JsonObject.arr(key: String): JsonArray = this[key] as? JsonArray ?: JsonArray(emptyList())
private fun JsonElement.text(): String = jsonObject.str("text")

class OpenAiCodec : ProviderCodec {
    private data class PartialCall(var id: String = "", var name: String = "", val args: StringBuilder = StringBuilder())
    private val calls = sortedMapOf<Int, PartialCall>()
    private var usage: AiEvent.Usage? = null
    override fun request(config: ProviderConfig, system: String, messages: List<AiMessage>, tools: List<ToolSpec>): String = buildJsonObject {
        put("model", config.model); put("stream", true)
        putJsonObject("stream_options") { put("include_usage", true) }
        putJsonArray("messages") {
            addJsonObject { put("role", "system"); put("content", system) }
            messages.forEach { message -> addJsonObject {
                put("role", message.role); put("content", message.content)
                message.toolCallId?.let { put("tool_call_id", it) }
                if (message.toolCalls.isNotEmpty()) putJsonArray("tool_calls") {
                    message.toolCalls.forEach { call -> addJsonObject {
                        put("id", call.id); put("type", "function")
                        putJsonObject("function") { put("name", call.name); put("arguments", call.arguments) }
                    } }
                }
            } }
        }
        if (tools.isNotEmpty()) {
            put("tool_choice", "auto")
            putJsonArray("tools") { tools.forEach { tool -> addJsonObject {
                put("type", "function"); putJsonObject("function") {
                    put("name", tool.name); put("description", tool.description); put("parameters", tool.parameters)
                }
            } } }
        }
    }.toString()
    override fun event(data: String): List<AiEvent> {
        if (data.trim() == "[DONE]") return emptyList()
        val root = json.parseToJsonElement(data).jsonObject
        root["error"]?.let { throw AiException(it.toString().take(1000)) }
        val out = mutableListOf<AiEvent>()
        root.obj("usage").let { u -> if (u.isNotEmpty()) usage = AiEvent.Usage(u["prompt_tokens"]?.jsonPrimitive?.intOrNull ?: 0, u["completion_tokens"]?.jsonPrimitive?.intOrNull ?: 0) }
        root.arr("choices").firstOrNull()?.jsonObject?.obj("delta")?.let { delta ->
            delta["content"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content?.let { out += AiEvent.Text(it) }
            delta.arr("tool_calls").forEach { element ->
                val item = element.jsonObject
                val index = item["index"]?.jsonPrimitive?.intOrNull ?: 0
                val call = calls.getOrPut(index) { PartialCall() }
                item["id"]?.jsonPrimitive?.contentOrNull?.let { call.id += it }
                val function = item.obj("function")
                function["name"]?.jsonPrimitive?.contentOrNull?.let { call.name += it }
                function["arguments"]?.jsonPrimitive?.contentOrNull?.let { call.args.append(it) }
            }
        }
        return out
    }
    override fun finish(): List<AiEvent> = calls.values.map { AiEvent.Call(ToolCall(it.id, it.name, it.args.toString().ifBlank { "{}" })) } + listOfNotNull(usage)
}

class AnthropicCodec : ProviderCodec {
    private data class Block(val type: String, var id: String, var name: String, val input: StringBuilder)
    private val blocks = mutableMapOf<Int, Block>()
    private var inputTokens = 0; private var outputTokens = 0
    override fun request(config: ProviderConfig, system: String, messages: List<AiMessage>, tools: List<ToolSpec>): String = buildJsonObject {
        put("model", config.model); put("max_tokens", 4096); put("stream", true); put("system", system)
        putJsonArray("messages") {
            messages.forEach { message ->
                if (message.role == "tool") {
                    // Consecutive tool results must live in one Anthropic user turn.
                    val last = lastOrNull()?.jsonObject
                    if (last?.str("role") == "user" && last?.get("content") is JsonArray) {
                        val content = last["content"]!!.jsonArray.toMutableList()
                        content += toolResult(message)
                        removeAt(lastIndex); addJsonObject { put("role", "user"); put("content", JsonArray(content)) }
                    } else addJsonObject { put("role", "user"); putJsonArray("content") { add(toolResult(message)) } }
                } else addJsonObject {
                    put("role", message.role)
                    if (message.toolCalls.isEmpty()) put("content", message.content)
                    else putJsonArray("content") {
                        if (message.content.isNotBlank()) addJsonObject { put("type", "text"); put("text", message.content) }
                        message.toolCalls.forEach { call -> addJsonObject {
                            put("type", "tool_use"); put("id", call.id); put("name", call.name)
                            put("input", json.parseToJsonElement(call.arguments))
                        } }
                    }
                }
            }
        }
        if (tools.isNotEmpty()) putJsonArray("tools") { tools.forEach { tool -> addJsonObject {
            put("name", tool.name); put("description", tool.description); put("input_schema", tool.parameters)
        } } }
    }.toString()
    private fun toolResult(message: AiMessage) = buildJsonObject {
        put("type", "tool_result"); put("tool_use_id", message.toolCallId ?: ""); put("content", message.content)
    }
    override fun event(data: String): List<AiEvent> {
        val root = json.parseToJsonElement(data).jsonObject
        when (root.str("type")) {
            "error" -> throw AiException(root.obj("error").toString().take(1000))
            "message_start" -> inputTokens = root.obj("message").obj("usage")["input_tokens"]?.jsonPrimitive?.intOrNull ?: 0
            "content_block_start" -> {
                val index = root["index"]?.jsonPrimitive?.intOrNull ?: 0
                val block = root.obj("content_block")
                blocks[index] = Block(block.str("type"), block.str("id"), block.str("name"), StringBuilder())
            }
            "content_block_delta" -> {
                val delta = root.obj("delta"); val block = blocks[root["index"]?.jsonPrimitive?.intOrNull ?: 0]
                if (delta.str("type") == "text_delta") return listOf(AiEvent.Text(delta.str("text")))
                if (delta.str("type") == "input_json_delta") block?.input?.append(delta.str("partial_json"))
            }
            "message_delta" -> outputTokens = root.obj("usage")["output_tokens"]?.jsonPrimitive?.intOrNull ?: outputTokens
        }
        return emptyList()
    }
    override fun finish(): List<AiEvent> = blocks.values.filter { it.type == "tool_use" }.map {
        AiEvent.Call(ToolCall(it.id, it.name, it.input.toString().ifBlank { "{}" }))
    } + listOf(AiEvent.Usage(inputTokens, outputTokens))
}

class GeminiCodec : ProviderCodec {
    private val calls = mutableListOf<AiEvent.Call>()
    private var counter = 0
    private var usage: AiEvent.Usage? = null
    override fun request(config: ProviderConfig, system: String, messages: List<AiMessage>, tools: List<ToolSpec>): String = buildJsonObject {
        putJsonObject("systemInstruction") { putJsonArray("parts") { addJsonObject { put("text", system) } } }
        putJsonObject("generationConfig") { put("maxOutputTokens", 4096) }
        putJsonArray("contents") {
            messages.forEach { message ->
                val role = if (message.role == "assistant") "model" else "user"
                val last = lastOrNull()?.jsonObject
                if (message.role == "tool" && last?.str("role") == "user") {
                    val parts = last.arr("parts").toMutableList(); parts += part(message)
                    removeAt(lastIndex); addJsonObject { put("role", "user"); put("parts", JsonArray(parts)) }
                } else addJsonObject { put("role", role); putJsonArray("parts") { add(part(message)) } }
            }
        }
        if (tools.isNotEmpty()) putJsonArray("tools") { addJsonObject { putJsonArray("functionDeclarations") {
            tools.forEach { tool -> addJsonObject { put("name", tool.name); put("description", tool.description); put("parameters", tool.parameters) } }
        } } }
    }.toString()
    private fun part(message: AiMessage): JsonObject = buildJsonObject {
        when {
            message.role == "tool" -> putJsonObject("functionResponse") {
                put("name", message.toolName ?: "workspace_tool"); putJsonObject("response") { put("content", message.content) }
            }
            message.toolCalls.isNotEmpty() -> putJsonObject("functionCall") {
                val call = message.toolCalls.first(); put("name", call.name); put("args", json.parseToJsonElement(call.arguments))
            }
            else -> put("text", message.content)
        }
    }
    override fun event(data: String): List<AiEvent> {
        val root = json.parseToJsonElement(data).jsonObject
        root["error"]?.let { throw AiException(it.toString().take(1000)) }
        val out = mutableListOf<AiEvent>()
        root.arr("candidates").firstOrNull()?.jsonObject?.obj("content").arr("parts").forEach { element ->
            val part = element.jsonObject
            if (part.containsKey("functionCall")) {
                val call = part.obj("functionCall")
                out += AiEvent.Call(ToolCall("gemini-${counter++}", call.str("name"), call.obj("args").toString()))
            } else if (part.containsKey("text") && part["thought"]?.jsonPrimitive?.booleanOrNull != true) out += AiEvent.Text(part.str("text"))
        }
        root.obj("usageMetadata").let { u -> if (u.isNotEmpty()) usage = AiEvent.Usage(u["promptTokenCount"]?.jsonPrimitive?.intOrNull ?: 0, u["candidatesTokenCount"]?.jsonPrimitive?.intOrNull ?: 0) }
        return out
    }
    override fun finish(): List<AiEvent> = listOfNotNull(usage)
}
