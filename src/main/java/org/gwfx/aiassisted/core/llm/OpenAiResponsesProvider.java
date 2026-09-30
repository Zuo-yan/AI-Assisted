package org.gwfx.aiassisted.core.llm;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import org.gwfx.aiassisted.core.http.HttpResponseData;
import org.gwfx.aiassisted.core.http.HttpTransport;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * OpenAI 官方新版 Responses 端点（POST {baseUrl}/v1/responses）。
 *
 * <p>与 {@link OpenAiCompatibleProvider}（Chat Completions）是<b>两种协议</b>，不是别名：
 * 端点、请求与响应结构都不同，不能靠改 baseUrl 互换。Responses 是 OpenAI 现在的主推接口
 * （gpt-5 / codex 等新模型的主入口）；第三方兼容服务大多只实现 Chat Completions，
 * 所以本 Provider 是补充而非替代。
 *
 * <p>报文与 Chat Completions 的关键差异：
 * <ul>
 *   <li>系统提示词放顶层 {@code instructions}，对话历史放 {@code input}</li>
 *   <li>生成上限叫 {@code max_output_tokens}（发 {@code max_tokens} 会被忽略）</li>
 *   <li>工具声明是扁平结构：{@code name/parameters} 直接挂在工具对象上，没有 {@code function} 包装层</li>
 *   <li>无状态补全历史：模型的工具调用以 {@code function_call} 条目原样回放，
 *       结果以 {@code function_call_output} 条目回传，靠 {@code call_id} 关联</li>
 *   <li>响应没有 {@code choices}：正文在 {@code output[]} 的 message 条目里，
 *       工具调用是独立的 {@code function_call} 条目</li>
 * </ul>
 *
 * <p>与既有 Provider 相同的宽松解析原则：缺字段一律降级，不因少一个可选字段整轮失败。
 */
public final class OpenAiResponsesProvider implements LlmProvider {

    public static final String ID = "openai-responses";
    public static final String DEFAULT_BASE_URL = "https://api.openai.com/v1";

    private static final int MAX_ERROR_SNIPPET = 200;
    /** token 用量未知时的占位值，与 {@link ChatResponse} 的约定一致。 */
    private static final int UNKNOWN_TOKENS = -1;

    private final HttpTransport transport;
    private final String baseUrl;
    private final String apiKey;
    private final Duration timeout;

    /**
     * @param baseUrl 形如 {@code https://api.openai.com/v1}（末尾斜杠会被去掉；不带 /v1 会自动补上）
     * @param apiKey  密钥，可为空串
     */
    public OpenAiResponsesProvider(HttpTransport transport, String baseUrl, String apiKey, Duration timeout) {
        this.transport = transport;
        this.baseUrl = stripTrailingSlash(baseUrl);
        this.apiKey = apiKey == null ? "" : apiKey;
        this.timeout = timeout;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public CompletableFuture<ChatResponse> chat(ChatRequest request) {
        URI uri = URI.create(resolveEndpoint(this.baseUrl));
        return transport
                .postJson(uri, buildHeaders(this.apiKey), buildBody(request), this.timeout)
                .thenApply(OpenAiResponsesProvider::parseResponse);
    }

    /** 地址拼接与 {@link AnthropicProvider} 同一套宽容规则：兼容裸域名、/v1 结尾、完整端点三种写法。 */
    static String resolveEndpoint(String baseUrl) {
        String base = stripTrailingSlash(baseUrl);
        if (base.isEmpty()) {
            base = DEFAULT_BASE_URL;
        }
        if (base.endsWith("/responses")) {
            return base;
        }
        if (base.endsWith("/v1")) {
            return base + "/responses";
        }
        return base + "/v1/responses";
    }

    /** 与 openai-compatible 同款 Bearer 头：Responses 端点同样走 Authorization（无密钥则不发）。 */
    static Map<String, String> buildHeaders(String apiKey) {
        return OpenAiCompatibleProvider.buildHeaders(apiKey);
    }

    /** 组装请求体。测试直接对这个方法断言，因此保持包级可见。 */
    static String buildBody(ChatRequest request) {
        JsonObject root = new JsonObject();
        root.addProperty("model", request.model());

        if (request.systemPrompt() != null && !request.systemPrompt().isBlank()) {
            root.addProperty("instructions", request.systemPrompt());
        }

        JsonArray input = new JsonArray();
        for (ChatMessage message : request.messages()) {
            addInputItems(input, message);
        }
        root.add("input", input);

        root.addProperty("temperature", request.temperature());
        root.addProperty("max_output_tokens", request.maxTokens());
        root.addProperty("stream", false);

        // 只在真的给了工具时才出现 tools 字段，与 openai-compatible 的约定一致
        if (request.hasTools()) {
            root.add("tools", tools(request.tools()));
            root.addProperty("tool_choice", "auto");
        }
        return root.toString();
    }

    /**
     * 一条历史消息 → 一个或多个 input 条目。
     *
     * <p>assistant 的「正文 + 工具调用」在 Responses 里是<b>并列条目</b>而非同一对象的两个字段，
     * 所以一条消息可能展开成多条。系统消息已进顶层 {@code instructions}，跳过。
     */
    private static void addInputItems(JsonArray input, ChatMessage message) {
        if (ChatMessage.ROLE_SYSTEM.equals(message.role())) {
            return;
        }
        if (ChatMessage.ROLE_TOOL.equals(message.role())) {
            // 工具结果：靠 call_id 与模型发出的 function_call 配对，output 是 JSON 字符串
            JsonObject item = new JsonObject();
            item.addProperty("type", "function_call_output");
            item.addProperty("call_id", message.toolCallId() != null ? message.toolCallId() : "");
            item.addProperty("output", message.content());
            input.add(item);
            return;
        }

        // user / assistant 正文；assistant 带工具调用时正文允许为空，此时只发调用条目
        if (!message.content().isEmpty() || !message.hasToolCalls()) {
            JsonObject item = new JsonObject();
            item.addProperty("role", message.role());
            item.addProperty("content", message.content());
            input.add(item);
        }
        for (ToolCall call : message.toolCalls()) {
            JsonObject item = new JsonObject();
            item.addProperty("type", "function_call");
            item.addProperty("call_id", call.id());
            item.addProperty("name", call.name());
            // 与 Chat Completions 一致：arguments 是 JSON 字符串，不是对象
            item.addProperty("arguments", call.argumentsJson());
            input.add(item);
        }
    }

    private static JsonArray tools(List<ToolSpec> specs) {
        JsonArray array = new JsonArray();
        for (ToolSpec spec : specs) {
            JsonObject tool = new JsonObject();
            tool.addProperty("type", "function");
            tool.addProperty("name", spec.name());
            tool.addProperty("description", spec.description());
            tool.add("parameters", spec.parametersSchema());
            array.add(tool);
        }
        return array;
    }

    /** 解析响应。非 2xx 抛 {@link LlmException}（带状态码），格式错误也抛同类异常。 */
    static ChatResponse parseResponse(HttpResponseData response) {
        if (!response.isSuccess()) {
            throw HttpErrors.fromResponse(response);
        }

        JsonObject root;
        try {
            JsonElement parsed = JsonParser.parseString(response.body());
            if (!parsed.isJsonObject()) {
                throw new JsonSyntaxException("响应根节点不是 JSON 对象");
            }
            root = parsed.getAsJsonObject();
        } catch (JsonSyntaxException | IllegalStateException e) {
            throw new LlmException("响应不是合法 JSON：" + snippetOf(response.body()), e);
        }

        if ("failed".equals(asNullableString(root.get("status")))) {
            // 服务端明确判失败时带出 error.message，比笼统的"没有 output"有用
            throw new LlmException("Responses 请求失败：" + errorMessageOf(root) + snippetOf(response.body()));
        }

        StringBuilder text = new StringBuilder();
        List<ToolCall> calls = new ArrayList<>();
        JsonElement outputElement = root.get("output");
        if (outputElement != null && outputElement.isJsonArray()) {
            JsonArray output = outputElement.getAsJsonArray();
            for (int i = 0; i < output.size(); i++) {
                JsonElement element = output.get(i);
                if (element == null || !element.isJsonObject()) {
                    continue;
                }
                JsonObject item = element.getAsJsonObject();
                String type = asString(item.get("type"));
                if ("message".equals(type)) {
                    appendMessageText(text, item);
                } else if ("function_call".equals(type)) {
                    addToolCall(calls, i, item);
                }
                // reasoning 等其余条目与对话正文无关，跳过
            }
        } else {
            // 宽松：个别兼容实现只回便捷字段 output_text
            text.append(asString(root.get("output_text")));
        }

        int promptTokens = UNKNOWN_TOKENS;
        int completionTokens = UNKNOWN_TOKENS;
        JsonObject usage = root.getAsJsonObject("usage");
        if (usage != null) {
            promptTokens = asInt(usage.get("input_tokens"), UNKNOWN_TOKENS);
            completionTokens = asInt(usage.get("output_tokens"), UNKNOWN_TOKENS);
        }

        return new ChatResponse(text.toString(), promptTokens, completionTokens,
                finishReasonOf(root, calls), calls);
    }

    /** message 条目的 content 数组可能有多段 output_text，全部拼接（reasoning 模型常见分段）。 */
    private static void appendMessageText(StringBuilder text, JsonObject messageItem) {
        JsonElement contentElement = messageItem.get("content");
        if (contentElement == null || !contentElement.isJsonArray()) {
            return;
        }
        for (JsonElement element : contentElement.getAsJsonArray()) {
            if (element == null || !element.isJsonObject()) {
                continue;
            }
            JsonObject block = element.getAsJsonObject();
            if ("output_text".equals(asString(block.get("type")))) {
                text.append(asString(block.get("text")));
            }
        }
    }

    private static void addToolCall(List<ToolCall> calls, int index, JsonObject item) {
        String name = asString(item.get("name"));
        if (name.isEmpty()) {
            // 没有函数名的调用无法执行，直接丢弃，与 openai-compatible 的处理一致
            return;
        }
        String callId = asNullableString(item.get("call_id"));
        calls.add(new ToolCall(
                callId == null || callId.isBlank() ? "call_" + index : callId,
                name,
                asString(item.get("arguments"))));
    }

    /** finish_reason 语义与另外两家对齐：有工具调用 → tool_calls；截断 → length；其余 → stop。 */
    private static String finishReasonOf(JsonObject root, List<ToolCall> calls) {
        if (!calls.isEmpty()) {
            return "tool_calls";
        }
        if ("incomplete".equals(asNullableString(root.get("status")))) {
            JsonObject details = root.getAsJsonObject("incomplete_details");
            if (details != null && "max_output_tokens".equals(asString(details.get("reason")))) {
                return "length";
            }
        }
        return "stop";
    }

    private static String errorMessageOf(JsonObject root) {
        JsonObject error = root.getAsJsonObject("error");
        if (error == null) {
            return "";
        }
        String message = asString(error.get("message"));
        return message.isEmpty() ? "" : "：" + message;
    }

    private static String asString(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return "";
        }
        return element.isJsonPrimitive() ? element.getAsString() : element.toString();
    }

    private static String asNullableString(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return null;
        }
        return element.isJsonPrimitive() ? element.getAsString() : null;
    }

    private static int asInt(JsonElement element, int fallback) {
        if (element == null || element.isJsonNull()) {
            return fallback;
        }
        try {
            return element.getAsInt();
        } catch (NumberFormatException | UnsupportedOperationException e) {
            return fallback;
        }
    }

    /** 截断错误片段：完整响应体可能很长，也可能含不该进日志的内容。 */
    private static String snippetOf(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        String trimmed = body.strip();
        return trimmed.length() > MAX_ERROR_SNIPPET
                ? "：" + trimmed.substring(0, MAX_ERROR_SNIPPET) + "…"
                : "：" + trimmed;
    }

    private static String stripTrailingSlash(String url) {
        if (url == null) {
            return "";
        }
        String trimmed = url.strip();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }
}
