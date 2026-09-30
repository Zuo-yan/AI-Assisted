package org.gwfx.aiassisted.core.llm;

import com.google.gson.JsonArray;
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

class OpenAiResponsesProviderTest {

    // ===== 请求体拼装 =====

    @Test
    void buildsRequestBodyWithInstructionsAndInputHistory() {
        ChatRequest request = new ChatRequest(
                "gpt-5",
                "你是助手",
                List.of(ChatMessage.user("你好"), ChatMessage.assistant("在的"), ChatMessage.user("介绍下自己")),
                0.3D,
                256);

        JsonObject body = JsonParser.parseString(OpenAiResponsesProvider.buildBody(request)).getAsJsonObject();

        assertEquals("gpt-5", body.get("model").getAsString());
        assertFalse(body.get("stream").getAsBoolean());
        assertEquals(0.3D, body.get("temperature").getAsDouble(), 1e-9);
        // Responses 的上限字段叫 max_output_tokens，不是 max_tokens
        assertEquals(256, body.get("max_output_tokens").getAsInt());
        assertFalse(body.has("max_tokens"));

        // 系统提示词放顶层 instructions
        assertEquals("你是助手", body.get("instructions").getAsString());

        JsonArray input = body.getAsJsonArray("input");
        assertEquals(3, input.size());
        assertEquals("user", input.get(0).getAsJsonObject().get("role").getAsString());
        assertEquals("介绍下自己", input.get(2).getAsJsonObject().get("content").getAsString());
    }

    @Test
    void omitsInstructionsWhenSystemPromptBlank() {
        ChatRequest request = new ChatRequest("m", "  ", List.of(ChatMessage.user("hi")), 0.7D, 100);
        JsonObject body = JsonParser.parseString(OpenAiResponsesProvider.buildBody(request)).getAsJsonObject();

        assertFalse(body.has("instructions"));
        assertEquals(1, body.getAsJsonArray("input").size());
    }

    // ===== 请求头 =====

    @Test
    void sendsBearerHeaderOnlyWhenKeyPresent() {
        assertFalse(OpenAiResponsesProvider.buildHeaders("").containsKey("Authorization"));
        assertFalse(OpenAiResponsesProvider.buildHeaders(null).containsKey("Authorization"));
        assertEquals("Bearer sk-test",
                OpenAiResponsesProvider.buildHeaders("sk-test").get("Authorization"));
    }

    // ===== 地址拼接 =====

    @Test
    void joinsBaseUrlWithoutDoublingSlash() {
        RecordingTransport transport = new RecordingTransport();
        OpenAiResponsesProvider provider = new OpenAiResponsesProvider(
                transport, "https://api.openai.com/v1/", "sk-1", Duration.ofSeconds(5));

        provider.chat(new ChatRequest("m", "", List.of(ChatMessage.user("hi")), 0.7D, 10)).join();

        assertEquals(URI.create("https://api.openai.com/v1/responses"), transport.lastUri);
        assertEquals("Bearer sk-1", transport.lastHeaders.get("Authorization"));
    }

    @Test
    void appendsV1WhenBaseUrlIsBareDomain() {
        assertEquals("https://api.openai.com/v1/responses",
                OpenAiResponsesProvider.resolveEndpoint("https://api.openai.com"));
        assertEquals("https://api.openai.com/v1/responses",
                OpenAiResponsesProvider.resolveEndpoint(""));
        assertEquals("https://api.example.com/v1/responses",
                OpenAiResponsesProvider.resolveEndpoint("https://api.example.com/v1/responses/"));
    }

    // ===== 响应解析 =====

    @Test
    void parsesOutputTextAndUsage() {
        String body = """
                {"status":"completed",
                 "output":[{"type":"message","role":"assistant",
                            "content":[{"type":"output_text","text":"你好呀"}]}],
                 "usage":{"input_tokens":12,"output_tokens":34}}
                """;

        ChatResponse response = OpenAiResponsesProvider.parseResponse(new HttpResponseData(200, body));

        assertEquals("你好呀", response.text());
        assertEquals(12, response.promptTokens());
        assertEquals(34, response.completionTokens());
        assertEquals("stop", response.finishReason());
        assertTrue(response.hasUsage());
    }

    @Test
    void concatenatesMultipleOutputTextBlocks() {
        String body = """
                {"output":[{"type":"message","role":"assistant",
                            "content":[{"type":"output_text","text":"第一段"},
                                       {"type":"output_text","text":"第二段"}]}]}
                """;

        assertEquals("第一段第二段",
                OpenAiResponsesProvider.parseResponse(new HttpResponseData(200, body)).text());
    }

    @Test
    void toleratesMissingUsageAndOutput() {
        ChatResponse response = OpenAiResponsesProvider.parseResponse(
                new HttpResponseData(200, "{\"status\":\"completed\"}"));

        assertEquals("", response.text());
        assertFalse(response.hasUsage());
    }

    @Test
    void mapsIncompleteMaxOutputTokensToLengthFinishReason() {
        String body = """
                {"status":"incomplete","incomplete_details":{"reason":"max_output_tokens"},
                 "output":[{"type":"message","content":[{"type":"output_text","text":"写到一半"}]}]}
                """;

        assertEquals("length",
                OpenAiResponsesProvider.parseResponse(new HttpResponseData(200, body)).finishReason());
    }

    @Test
    void rejectsFailedStatusWithErrorDetail() {
        String body = """
                {"status":"failed","error":{"message":"模型不存在"}}
                """;

        LlmException error = assertThrows(LlmException.class, () ->
                OpenAiResponsesProvider.parseResponse(new HttpResponseData(200, body)));
        assertTrue(error.getMessage().contains("模型不存在"));
        assertEquals(LlmException.NO_HTTP_STATUS, error.httpStatus());
    }

    @Test
    void rejectsNonJsonResponse() {
        assertThrows(LlmException.class, () ->
                OpenAiResponsesProvider.parseResponse(new HttpResponseData(200, "<html>gateway error</html>")));
    }

    @Test
    void surfacesHttpStatusFor4xxAnd5xx() {
        LlmException unauthorized = assertThrows(LlmException.class, () ->
                OpenAiResponsesProvider.parseResponse(new HttpResponseData(401, "{\"error\":\"bad key\"}")));
        assertEquals(401, unauthorized.httpStatus());

        LlmException serverError = assertThrows(LlmException.class, () ->
                OpenAiResponsesProvider.parseResponse(new HttpResponseData(503, "unavailable")));
        assertEquals(503, serverError.httpStatus());
    }

    @Test
    void truncatesLongErrorBody() {
        String huge = "x".repeat(5000);
        LlmException error = assertThrows(LlmException.class, () ->
                OpenAiResponsesProvider.parseResponse(new HttpResponseData(500, huge)));

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
        ChatRequest request = new ChatRequest("m", "", List.of(ChatMessage.user("hi")), 0.7D, 10);
        JsonObject body = JsonParser.parseString(OpenAiResponsesProvider.buildBody(request)).getAsJsonObject();

        assertFalse(body.has("tools"));
        assertFalse(body.has("tool_choice"));
    }

    @Test
    void usesFlattenedToolDeclaration() {
        // Responses 的工具声明是扁平结构：name/parameters 直接挂在工具对象上，没有 function 包装层
        ChatRequest request = new ChatRequest("m", "", List.of(ChatMessage.user("hi")), 0.7D, 10,
                List.of(toolSpec()));
        JsonObject body = JsonParser.parseString(OpenAiResponsesProvider.buildBody(request)).getAsJsonObject();

        JsonArray tools = body.getAsJsonArray("tools");
        assertEquals(1, tools.size());
        JsonObject tool = tools.get(0).getAsJsonObject();
        assertEquals("function", tool.get("type").getAsString());
        assertEquals("search_blocks", tool.get("name").getAsString());
        assertEquals("搜索附近方块", tool.get("description").getAsString());
        assertEquals("object", tool.getAsJsonObject("parameters").get("type").getAsString());
        assertFalse(tool.has("function"));
        assertEquals("auto", body.get("tool_choice").getAsString());
    }

    @Test
    void replaysFunctionCallsAndToolResultsAsInputItems() {
        // 无状态请求（不用 previous_response_id）：模型的 function_call 原样回放，结果用 function_call_output 回传
        ToolCall call = new ToolCall("call_1", "search_blocks", "{\"block_id\":\"minecraft:stone\"}");
        ChatRequest request = new ChatRequest("m", "", List.of(
                ChatMessage.assistantToolCalls("", List.of(call)),
                ChatMessage.toolResult(call, "命中 3 个")),
                0.7D, 10, List.of(toolSpec()));

        JsonObject body = JsonParser.parseString(OpenAiResponsesProvider.buildBody(request)).getAsJsonObject();
        JsonArray input = body.getAsJsonArray("input");

        JsonObject replayedCall = input.get(0).getAsJsonObject();
        assertEquals("function_call", replayedCall.get("type").getAsString());
        assertEquals("call_1", replayedCall.get("call_id").getAsString());
        assertEquals("search_blocks", replayedCall.get("name").getAsString());
        assertTrue(replayedCall.get("arguments").isJsonPrimitive());

        JsonObject toolOutput = input.get(1).getAsJsonObject();
        assertEquals("function_call_output", toolOutput.get("type").getAsString());
        assertEquals("call_1", toolOutput.get("call_id").getAsString());
        assertEquals("命中 3 个", toolOutput.get("output").getAsString());
    }

    @Test
    void assistantMessageWithTextAndToolCallsExpandsToMultipleItems() {
        ToolCall call = new ToolCall("call_2", "search_blocks", "{}");
        ChatRequest request = new ChatRequest("m", "", List.of(
                ChatMessage.assistantToolCalls("我先查一下", List.of(call))),
                0.7D, 10);

        JsonObject body = JsonParser.parseString(OpenAiResponsesProvider.buildBody(request)).getAsJsonObject();
        JsonArray input = body.getAsJsonArray("input");

        // 正文与工具调用在 Responses 里是并列条目：一条 assistant 消息展开成 2 条
        assertEquals(2, input.size());
        assertEquals("assistant", input.get(0).getAsJsonObject().get("role").getAsString());
        assertEquals("我先查一下", input.get(0).getAsJsonObject().get("content").getAsString());
        assertEquals("function_call", input.get(1).getAsJsonObject().get("type").getAsString());
    }

    @Test
    void parsesFunctionCallItemsFromOutput() {
        String body = """
                {"status":"completed",
                 "output":[{"type":"function_call","call_id":"call_9","name":"search_blocks",
                            "arguments":"{\\"block_id\\":\\"minecraft:stone\\"}"}]}
                """;

        ChatResponse response = OpenAiResponsesProvider.parseResponse(new HttpResponseData(200, body));

        // 只调用工具时正文为空，不能当成失败
        assertEquals("", response.text());
        assertTrue(response.hasToolCalls());
        assertEquals("tool_calls", response.finishReason());
        ToolCall call = response.toolCalls().get(0);
        assertEquals("call_9", call.id());
        assertEquals("search_blocks", call.name());
        assertEquals("minecraft:stone",
                JsonParser.parseString(call.argumentsJson()).getAsJsonObject().get("block_id").getAsString());
    }

    @Test
    void generatesFallbackCallIdWhenMissing() {
        String body = """
                {"output":[{"type":"function_call","name":"search_blocks","arguments":"{}"}]}
                """;

        ChatResponse response = OpenAiResponsesProvider.parseResponse(new HttpResponseData(200, body));

        // 缺 call_id 时生成兜底 id：上层要靠它把工具结果与请求配对
        assertFalse(response.toolCalls().get(0).id().isBlank());
    }

    @Test
    void dropsFunctionCallItemsWithoutName() {
        String body = """
                {"output":[{"type":"function_call","call_id":"call_1","arguments":"{}"},
                           {"type":"message","content":[{"type":"output_text","text":"done"}]}]}
                """;

        ChatResponse response = OpenAiResponsesProvider.parseResponse(new HttpResponseData(200, body));

        // 没有函数名的调用无法执行，丢弃后不能把 finish_reason 误标成 tool_calls
        assertFalse(response.hasToolCalls());
        assertEquals("done", response.text());
        assertEquals("stop", response.finishReason());
    }

    // ===== 端到端（假传输） =====

    @Test
    void chatPostsToResolvedEndpointWithBearer() {
        RecordingTransport transport = new RecordingTransport();
        transport.response = new HttpResponseData(200,
                "{\"output\":[{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"ok\"}]}]}");
        OpenAiResponsesProvider provider = new OpenAiResponsesProvider(
                transport, "https://api.openai.com/v1", "sk-1", Duration.ofSeconds(5));

        ChatResponse response = provider
                .chat(new ChatRequest("gpt-5", "", List.of(ChatMessage.user("hi")), 0.7D, 10))
                .join();

        assertEquals("ok", response.text());
        assertEquals(URI.create("https://api.openai.com/v1/responses"), transport.lastUri);
    }

    /** 记录最后一次请求的假传输，用于验证 URL 与请求头，不产生真实网络请求。 */
    static final class RecordingTransport implements HttpTransport {
        URI lastUri;
        Map<String, String> lastHeaders;
        String lastBody;
        HttpResponseData response = new HttpResponseData(200, "{\"output\":[]}");

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
