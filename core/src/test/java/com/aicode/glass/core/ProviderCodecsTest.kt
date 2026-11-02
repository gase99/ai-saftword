package com.aicode.glass.core

import com.aicode.glass.core.network.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ProviderCodecsTest {
    private val config = ProviderConfig(ProviderType.OPENAI, "test-model")
    private val tools = listOf(ToolSpec("read_file", "Read a file", JsonTools.schema(mapOf("path" to "string"), listOf("path"))))
    @Test fun openAiAssemblesFragmentedToolArguments() {
        val codec = OpenAiCodec()
        codec.event("""{"choices":[{"delta":{"content":"Hello","tool_calls":[{"index":0,"id":"call-1","function":{"name":"read_file","arguments":"{\"pa"}}]}}]}""")
        val last = codec.event("""{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"th\":\"a.py\"}"}}]}}]}""")
        assertEquals(listOf(AiEvent.Text("Hello")), last)
        val events = codec.finish()
        assertEquals(ToolCall("call-1", "read_file", """{"path":"a.py"}"""), (events.first() as AiEvent.Call).value)
        assertEquals(emptyList<AiEvent>(), codec.event("[DONE]"))
    }
    @Test fun openAiEncodesToolHistoryAndSchema() {
        val messages = listOf(AiMessage("user", "read"), AiMessage("assistant", "", listOf(ToolCall("1", "read_file", """{"path":"a"}"""))), AiMessage("tool", "contents", toolCallId = "1"))
        val body = Json.parseToJsonElement(OpenAiCodec().request(config, "system", messages, tools)).jsonObject
        assertEquals(true, body["stream"]?.jsonPrimitive?.boolean)
        val wire = body.getValue("messages").jsonArray
        assertEquals("tool", wire[2].jsonObject["role"]?.jsonPrimitive?.content)
        assertEquals("1", wire[2].jsonObject["tool_call_id"]?.jsonPrimitive?.content)
        assertEquals("function", body.getValue("tools").jsonArray[0].jsonObject["type"]?.jsonPrimitive?.content)
    }
    @Test fun anthropicGroupsAdjacentToolResults() {
        val messages = listOf(AiMessage("user", "read both"), AiMessage("assistant", "", listOf(ToolCall("a", "read_file", "{}"), ToolCall("b", "read_file", "{}"))), AiMessage("tool", "A", toolCallId = "a"), AiMessage("tool", "B", toolCallId = "b"))
        val body = Json.parseToJsonElement(AnthropicCodec().request(ProviderConfig(ProviderType.ANTHROPIC, "claude"), "system", messages, tools)).jsonObject
        val wire = body.getValue("messages").jsonArray
        assertEquals(3, wire.size)
        val results = wire[2].jsonObject.getValue("content").jsonArray
        assertEquals(2, results.size)
        assertEquals("b", results[1].jsonObject["tool_use_id"]?.jsonPrimitive?.content)
        assertEquals("input_schema", body.getValue("tools").jsonArray[0].jsonObject.keys.first { it == "input_schema" })
    }
    @Test fun anthropicStreamsTextAndToolJson() {
        val codec = AnthropicCodec()
        codec.event("""{"type":"message_start","message":{"usage":{"input_tokens":12}}}""")
        codec.event("""{"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"tool-1","name":"read_file"}}""")
        assertEquals(listOf(AiEvent.Text("reading")), codec.event("""{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"reading"}}"""))
        codec.event("""{"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{\"path\":\"a\"}"}}""")
        codec.event("""{"type":"message_delta","usage":{"output_tokens":7}}""")
        val events = codec.finish()
        assertEquals("read_file", (events[0] as AiEvent.Call).value.name)
        assertEquals(AiEvent.Usage(12, 7), events.last())
    }
    @Test fun geminiPreservesParallelCallsAndToolNames() {
        val calls = listOf(ToolCall("1", "read_file", """{"path":"a"}"""), ToolCall("2", "list_files", "{}"))
        val messages = listOf(AiMessage("user", "read both"), AiMessage("assistant", "", calls), AiMessage("tool", "A", toolCallId = "1", toolName = "read_file"), AiMessage("tool", "B", toolCallId = "2", toolName = "list_files"))
        val body = Json.parseToJsonElement(GeminiCodec().request(ProviderConfig(ProviderType.GEMINI, "gemini"), "system", messages, tools)).jsonObject
        val wire = body.getValue("contents").jsonArray
        assertEquals(2, wire[1].jsonObject.getValue("parts").jsonArray.size)
        val results = wire[2].jsonObject.getValue("parts").jsonArray
        assertEquals("list_files", results[1].jsonObject.getValue("functionResponse").jsonObject["name"]?.jsonPrimitive?.content)
    }
    @Test fun geminiStreamsFunctionCallAndUsage() {
        val codec = GeminiCodec()
        val events = codec.event("""{"candidates":[{"content":{"parts":[{"text":"Hi"},{"functionCall":{"name":"list_files","args":{}}}]}}],"usageMetadata":{"promptTokenCount":5,"candidatesTokenCount":9}}""")
        assertEquals(AiEvent.Text("Hi"), events[0])
        assertEquals("list_files", (events[1] as AiEvent.Call).value.name)
        assertEquals(AiEvent.Usage(5, 9), codec.finish().single())
    }
    @Test fun customEndpointRequiresSafeHttps() {
        listOf("http://api.example/v1", "https://user:secret@example.com/v1", "https://example.com/v1?key=secret", "file:///tmp").forEach {
            assertThrows(AiException::class.java) { StreamingAiClient.validateEndpoint(it) }
        }
        StreamingAiClient.validateEndpoint("https://example.com/openai/v1")
    }
    @Test fun errorsRedactCredentials() {
        val result = StreamingAiClient.sanitize("failed key=sk-secret123456 Authorization: Bearer abc.def.ghi", "sk-secret123456")
        assertFalse(result.contains("sk-secret123456")); assertFalse(result.contains("abc.def.ghi"))
    }
    @Test fun providerErrorsFailExplicitly() {
        assertThrows(AiException::class.java) { OpenAiCodec().event("""{"error":{"message":"bad key","type":"authentication_error"}}""") }
    }
}
