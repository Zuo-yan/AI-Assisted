package org.gwfx.aiassisted.core.llm;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.gwfx.aiassisted.core.http.HttpResponseData;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnthropicProviderTest {

    @Test
    void resolvesEndpointProperly() {
        assertEquals("https://api.anthropic.com/v1/messages", AnthropicProvider.resolveEndpoint(""));
        assertEquals("https://api.anthropic.com/v1/messages", AnthropicProvider.resolveEndpoint(null));
        assertEquals("https://api.anthropic.com/v1/messages", AnthropicProvider.resolveEndpoint("https://api.anthropic.com"));
        assertEquals("https://api.anthropic.com/v1/messages", AnthropicProvider.resolveEndpoint("https://api.anthropic.com/"));
        assertEquals("https://api.anthropic.com/v1/messages", AnthropicProvider.resolveEndpoint("https://api.anthropic.com/v1"));
        assertEquals("https://api.anthropic.com/v1/messages", AnthropicProvider.resolveEndpoint("https://api.anthropic.com/v1/messages"));
        assertEquals("https://proxy.example.com/claude/v1/messages", AnthropicProvider.resolveEndpoint("https://proxy.example.com/claude"));
    }

    @Test
    void buildsHeadersWithApiKeyAndVersion() {
        Map<String, String> headers = AnthropicProvider.buildHeaders("sk-ant-123");
        assertEquals("sk-ant-123", headers.get("x-api-key"));
        assertEquals("2023-06-01", headers.get("anthropic-version"));

        Map<String, String> noKey = AnthropicProvider.buildHeaders("");
        assertFalse(noKey.containsKey("x-api-key"));
        assertEquals("2023-06-01", noKey.get("anthropic-version"));
    }

    @Test
    void buildsRequestBodyWithSystemPromptAndMessages() {
        ChatRequest request = new ChatRequest(
                "claude-3-5-sonnet-20241022",
                "你是助手",
                List.of(ChatMessage.user("你好"), ChatMessage.assistant("在的")),
                0.5D,
                2048
        );

        JsonObject body = JsonParser.parseString(AnthropicProvider.buildBody(request)).getAsJsonObject();

        assertEquals("claude-3-5-sonnet-20241022", body.get("model").getAsString());
        assertEquals("你是助手", body.get("system").getAsString());
        assertEquals(2048, body.get("max_tokens").getAsInt());
        assertEquals(0.5D, body.get("temperature").getAsDouble(), 1e-9);

        JsonArray messages = body.getAsJsonArray("messages");
        assertEquals(2, messages.size());
        assertEquals("user", messages.get(0).getAsJsonObject().get("role").getAsString());
        assertEquals("你好", messages.get(0).getAsJsonObject().get("content").getAsString());
        assertEquals("assistant", messages.get(1).getAsJsonObject().get("role").getAsString());
        assertEquals("在的", messages.get(1).getAsJsonObject().get("content").getAsString());
    }

    @Test
    void formatsToolUseAndToolResultCorrectly() {
        ToolSpec tool = ToolSpec.builder("search_blocks", "搜索方块")
                .stringParam("block_id", "方块ID", true)
                .build();

        ToolCall call = new ToolCall("call_1", "search_blocks", "{\"block_id\":\"minecraft:diamond_ore\"}");
        ChatMessage assistantCall = ChatMessage.assistantToolCalls("", List.of(call));
        ChatMessage toolResult = ChatMessage.toolResult(call, "找到 3 处");

        ChatRequest request = new ChatRequest(
                "claude-3-5-sonnet",
                "",
                List.of(ChatMessage.user("找钻石"), assistantCall, toolResult),
                0.0D,
                100,
                List.of(tool)
        );

        JsonObject body = JsonParser.parseString(AnthropicProvider.buildBody(request)).getAsJsonObject();

        JsonArray tools = body.getAsJsonArray("tools");
        assertEquals(1, tools.size());
        JsonObject toolObj = tools.get(0).getAsJsonObject();
        assertEquals("search_blocks", toolObj.get("name").getAsString());
        assertTrue(toolObj.has("input_schema"));

        JsonArray messages = body.getAsJsonArray("messages");
        assertEquals(3, messages.size());

        // assistant 消息应有 content 数组且含 tool_use
        JsonObject assistantMsg = messages.get(1).getAsJsonObject();
        assertEquals("assistant", assistantMsg.get("role").getAsString());
        JsonArray assistantContent = assistantMsg.getAsJsonArray("content");
        assertEquals(1, assistantContent.size());
        JsonObject toolUseBlock = assistantContent.get(0).getAsJsonObject();
        assertEquals("tool_use", toolUseBlock.get("type").getAsString());
        assertEquals("call_1", toolUseBlock.get("id").getAsString());
        assertEquals("search_blocks", toolUseBlock.get("name").getAsString());
        assertEquals("minecraft:diamond_ore", toolUseBlock.getAsJsonObject("input").get("block_id").getAsString());

        // tool 结果消息在 Anthropic 格式下应包装为 user 角色的 tool_result
        JsonObject resultMsg = messages.get(2).getAsJsonObject();
        assertEquals("user", resultMsg.get("role").getAsString());
        JsonArray resultContent = resultMsg.getAsJsonArray("content");
        assertEquals(1, resultContent.size());
        JsonObject toolResultBlock = resultContent.get(0).getAsJsonObject();
        assertEquals("tool_result", toolResultBlock.get("type").getAsString());
        assertEquals("call_1", toolResultBlock.get("tool_use_id").getAsString());
        assertEquals("找到 3 处", toolResultBlock.get("content").getAsString());
    }

    @Test
    void parsesTextAndToolCallsResponse() {
        String json = """
                {
                  "id": "msg_01",
                  "type": "message",
                  "role": "assistant",
                  "content": [
                    { "type": "text", "text": "找到了" },
                    { "type": "tool_use", "id": "toolu_1", "name": "place_block", "input": { "x": 10 } }
                  ],
                  "stop_reason": "tool_use",
                  "usage": { "input_tokens": 100, "output_tokens": 50 }
                }
                """;

        ChatResponse response = AnthropicProvider.parseResponse(new HttpResponseData(200, json));

        assertEquals("找到了", response.text());
        assertEquals("tool_calls", response.finishReason());
        assertEquals(100, response.promptTokens());
        assertEquals(50, response.completionTokens());
        assertEquals(1, response.toolCalls().size());
        assertEquals("toolu_1", response.toolCalls().get(0).id());
        assertEquals("place_block", response.toolCalls().get(0).name());
        assertTrue(response.toolCalls().get(0).argumentsJson().contains("10"));
    }

    @Test
    void surfacesHttpStatusOnErrors() {
        LlmException ex = assertThrows(LlmException.class, () ->
                AnthropicProvider.parseResponse(new HttpResponseData(401, "{\"error\":\"invalid x-api-key\"}")));
        assertTrue(ex.getMessage().contains("401"));
        assertTrue(ex.getMessage().contains("invalid x-api-key"));
    }
}