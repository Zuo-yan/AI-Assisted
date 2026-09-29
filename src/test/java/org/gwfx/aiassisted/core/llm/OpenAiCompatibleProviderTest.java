package org.gwfx.aiassisted.core.llm;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.gwfx.aiassisted.core.http.HttpResponseData;
import org.gwfx.aiassisted.core.http.HttpTransport;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenAiCompatibleProviderTest {

    // ===== 请求体拼装 =====

    @Test
    void buildsRequestBodyWithSystemPromptAndHistory() {
        ChatRequest request = new ChatRequest(
                "deepseek-chat",
                "你是助手",
                List.of(ChatMessage.user("你好"), ChatMessage.assistant("在的"), ChatMessage.user("介绍下自己")),
                0.3D,
                256);

        JsonObject body = JsonParser.parseString(OpenAiCompatibleProvider.buildBody(request)).getAsJsonObject();

        assertEquals("deepseek-chat", body.get("model").getAsString());
        assertFalse(body.get("stream").getAsBoolean());
        assertEquals(0.3D, body.get("temperature").getAsDouble(), 1e-9);
        assertEquals(256, body.get("max_tokens").getAsInt());

        JsonArray messages = body.getAsJsonArray("messages");
        // 1 条 system + 3 条历史
        assertEquals(4, messages.size());
        assertEquals("system", messages.get(0).getAsJsonObject().get("role").getAsString());
        assertEquals("你是助手", messages.get(0).getAsJsonObject().get("content").getAsString());
        assertEquals("user", messages.get(1).getAsJsonObject().get("role").getAsString());
        assertEquals("介绍下自己", messages.get(3).getAsJsonObject().get("content").getAsString());
    }

    @Test
    void omitsSystemMessageWhenPromptBlank() {
        ChatRequest request = new ChatRequest("m", "  ", List.of(ChatMessage.user("hi")), 0.7D, 100);
        JsonObject body = JsonParser.parseString(OpenAiCompatibleProvider.buildBody(request)).getAsJsonObject();

        JsonArray messages = body.getAsJsonArray("messages");
        assertEquals(1, messages.size());
        assertEquals("user", messages.get(0).getAsJsonObject().get("role").getAsString());
    }

    // ===== 请求头 =====

    @Test
    void sendsBearerHeaderOnlyWhenKeyPresent() {
        assertFalse(OpenAiCompatibleProvider.buildHeaders("").containsKey("Authorization"));
        assertFalse(OpenAiCompatibleProvider.buildHeaders(null).containsKey("Authorization"));
        assertEquals("Bearer sk-test",
                OpenAiCompatibleProvider.buildHeaders("sk-test").get("Authorization"));
    }

    // ===== 地址拼接 =====

    @Test
    void joinsBaseUrlWithoutDoublingSlash() {
        RecordingTransport transport = new RecordingTransport();
        OpenAiCompatibleProvider provider = new OpenAiCompatibleProvider(
                transport, "https://api.example.com/v1/", "sk-1", Duration.ofSeconds(5));

        provider.chat(new ChatRequest("m", "", List.of(ChatMessage.user("hi")), 0.7D, 10)).join();

        assertEquals(URI.create("https://api.example.com/v1/chat/completions"), transport.lastUri);
        assertEquals("Bearer sk-1", transport.lastHeaders.get("Authorization"));
    }

    // ===== 响应解析 =====

    @Test
    void parsesContentAndUsage() {
        String body = """
                {"choices":[{"message":{"role":"assistant","content":"你好呀"},"finish_reason":"stop"}],
                 "usage":{"prompt_tokens":12,"completion_tokens":34}}
                """;

        ChatResponse response = OpenAiCompatibleProvider.parseResponse(new HttpResponseData(200, body));

        assertEquals("你好呀", response.text());
        assertEquals(12, response.promptTokens());
        assertEquals(34, response.completionTokens());
        assertEquals("stop", response.finishReason());
        assertTrue(response.hasUsage());
    }

    @Test
    void toleratesMissingUsage() {
        String body = "{\"choices\":[{\"message\":{\"content\":\"hi\"}}]}";
        ChatResponse response = OpenAiCompatibleProvider.parseResponse(new HttpResponseData(200, body));

        assertEquals("hi", response.text());
        assertFalse(response.hasUsage());
    }

    @Test
    void rejectsResponseWithoutChoices() {
        LlmException error = assertThrows(LlmException.class, () ->
                OpenAiCompatibleProvider.parseResponse(new HttpResponseData(200, "{\"error\":\"nope\"}")));
        assertEquals(LlmException.NO_HTTP_STATUS, error.httpStatus());
    }

    @Test
    void rejectsNonJsonResponse() {
        assertThrows(LlmException.class, () ->
                OpenAiCompatibleProvider.parseResponse(new HttpResponseData(200, "<html>gateway error</html>")));
    }

    @Test
    void surfacesHttpStatusFor4xxAnd5xx() {
        LlmException unauthorized = assertThrows(LlmException.class, () ->
                OpenAiCompatibleProvider.parseResponse(new HttpResponseData(401, "{\"error\":\"bad key\"}")));
        assertEquals(401, unauthorized.httpStatus());

        LlmException serverError = assertThrows(LlmException.class, () ->
                OpenAiCompatibleProvider.parseResponse(new HttpResponseData(503, "unavailable")));
        assertEquals(503, serverError.httpStatus());
    }

    @Test
    void truncatesLongErrorBody() {
        String huge = "x".repeat(5000);
        LlmException error = assertThrows(LlmException.class, () ->
                OpenAiCompatibleProvider.parseResponse(new HttpResponseData(500, huge)));

        // 错误信息必须被截断，避免把整页响应塞进日志/聊天
        assertTrue(error.getMessage().length() < 400, "错误信息过长: " + error.getMessage().length());
    }

    // ===== 工具调用报文 =====

    private static ToolSpec toolSpec() {
        return ToolSpec.builder("search_blocks", "搜索附近方块")
                .stringParam("block_id", "方块 id", true)
                .integerParam("radius", "半径", 1, 16, false)
                .build();
    }

    @Test
    void omitsToolsFieldWhenRequestHasNoTools() {
        // 回归门：不带 tools 时请求体里绝不能多出 tools / tool_choice 字段，
        // 否则关闭 ai.toolCallingEnabled 后报文就不再与 T001-4 一致
        ChatRequest request = new ChatRequest("m", "", List.of(ChatMessage.user("hi")), 0.7D, 10);
        JsonObject body = JsonParser.parseString(OpenAiCompatibleProvider.buildBody(request)).getAsJsonObject();

        assertFalse(body.has("tools"));
        assertFalse(body.has("tool_choice"));
    }

    @Test
    void includesToolsAndToolChoiceWhenPresent() {
        ChatRequest request = new ChatRequest("m", "", List.of(ChatMessage.user("hi")), 0.7D, 10,
                List.of(toolSpec()));
        JsonObject body = JsonParser.parseString(OpenAiCompatibleProvider.buildBody(request)).getAsJsonObject();

        JsonArray tools = body.getAsJsonArray("tools");
        assertEquals(1, tools.size());
        JsonObject wrapper = tools.get(0).getAsJsonObject();
        assertEquals("function", wrapper.get("type").getAsString());
        JsonObject function = wrapper.getAsJsonObject("function");
        assertEquals("search_blocks", function.get("name").getAsString());
        assertEquals("object", function.getAsJsonObject("parameters").get("type").getAsString());
        assertEquals("auto", body.get("tool_choice").getAsString());
    }

    @Test
    void serializesToolCallMessagesAndToolResultsWithToolCallId() {
        ToolCall call = new ToolCall("call_1", "search_blocks", "{\"block_id\":\"minecraft:stone\"}");
        ChatRequest request = new ChatRequest("m", "", List.of(
                ChatMessage.assistantToolCalls("", List.of(call)),
                ChatMessage.toolResult(call, "命中 3 个")),
                0.7D, 10, List.of(toolSpec()));

        JsonObject body = JsonParser.parseString(OpenAiCompatibleProvider.buildBody(request)).getAsJsonObject();
        JsonArray messages = body.getAsJsonArray("messages");

        JsonObject assistant = messages.get(0).getAsJsonObject();
        assertEquals("assistant", assistant.get("role").getAsString());
        JsonObject callObject = assistant.getAsJsonArray("tool_calls").get(0).getAsJsonObject();
        assertEquals("call_1", callObject.get("id").getAsString());
        // OpenAI 的 arguments 必须是 JSON 字符串，不能是对象
        assertTrue(callObject.getAsJsonObject("function").get("arguments").isJsonPrimitive());

        JsonObject tool = messages.get(1).getAsJsonObject();
        assertEquals("tool", tool.get("role").getAsString());
        assertEquals("call_1", tool.get("tool_call_id").getAsString());
        assertEquals("命中 3 个", tool.get("content").getAsString());
    }

    @Test
    void parsesToolCallsAndNormalizesNullContent() {
        JsonObject function = new JsonObject();
        function.addProperty("name", "search_entities");
        function.addProperty("arguments", "{\"entity_id\":\"minecraft:sheep\"}");
        JsonObject callObject = new JsonObject();
        callObject.addProperty("id", "call_9");
        callObject.addProperty("type", "function");
        callObject.add("function", function);
        JsonObject message = new JsonObject();
        message.add("content", JsonNull.INSTANCE);
        message.add("tool_calls", JsonParser.parseString("[" + callObject + "]"));
        JsonObject choice = new JsonObject();
        choice.add("message", message);
        choice.addProperty("finish_reason", "tool_calls");
        JsonObject root = new JsonObject();
        root.add("choices", JsonParser.parseString("[" + choice + "]"));

        ChatResponse response = OpenAiCompatibleProvider.parseResponse(new HttpResponseData(200, root.toString()));

        // content 为 null 时必须归一化为空串，不能把「正文为空」当成失败
        assertEquals("", response.text());
        assertTrue(response.hasToolCalls());
        ToolCall call = response.toolCalls().get(0);
        assertEquals("call_9", call.id());
        assertEquals("search_entities", call.name());
        assertEquals("minecraft:sheep",
                JsonParser.parseString(call.argumentsJson()).getAsJsonObject().get("entity_id").getAsString());
    }

    @Test
    void generatesFallbackIdWhenToolCallIdIsMissing() {
        JsonObject function = new JsonObject();
        function.addProperty("name", "search_blocks");
        function.addProperty("arguments", "{}");
        JsonObject callObject = new JsonObject();
        callObject.add("function", function);
        JsonObject message = new JsonObject();
        message.addProperty("content", "");
        message.add("tool_calls", JsonParser.parseString("[" + callObject + "]"));
        JsonObject choice = new JsonObject();
        choice.add("message", message);
        JsonObject root = new JsonObject();
        root.add("choices", JsonParser.parseString("[" + choice + "]"));

        ChatResponse response = OpenAiCompatibleProvider.parseResponse(new HttpResponseData(200, root.toString()));

        // 缺 id 时生成兜底 id：上层要靠它把工具结果与请求配对，空白 id 会让消息链对不上
        assertFalse(response.toolCalls().get(0).id().isBlank());
    }

    /** 记录最后一次请求的假传输，用于验证 URL 与请求头，不产生真实网络请求。 */
    static final class RecordingTransport implements HttpTransport {
        URI lastUri;
        Map<String, String> lastHeaders;
        String lastBody;
        HttpResponseData response = new HttpResponseData(200, "{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}");

        @Override
        public CompletableFuture<HttpResponseData> postJson(
                URI uri, Map<String, String> headers, String jsonBody, Duration timeout) {
            this.lastUri = uri;
            this.lastHeaders = headers;
            this.lastBody = jsonBody;
            return CompletableFuture.completedFuture(this.response);
        }
    }
}
