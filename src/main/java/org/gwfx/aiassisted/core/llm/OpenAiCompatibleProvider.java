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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * OpenAI 兼容端点（<code>POST {baseUrl}/chat/completions</code>）。
 *
 * <p>覆盖 OpenAI 本身与所有照抄其报文的服务：DeepSeek、通义千问、智谱 GLM、
 * LM Studio、以及自带 <code>/v1</code> 兼容层的 Ollama。
 *
 * <p>请求里<b>不含流式字段语义之外的扩展</b>，响应只读 {@code choices[0].message.content}
 * 与 {@code usage}。缺字段一律降级而不是抛异常 —— 兼容端点的实现差异很大，
 * 因为少一个 finish_reason 就整轮失败是不划算的。
 */
public final class OpenAiCompatibleProvider implements LlmProvider {

    public static final String ID = "openai-compatible";

    private static final String PATH_CHAT_COMPLETIONS = "/chat/completions";
    private static final int MAX_ERROR_SNIPPET = 200;
    /** token 用量未知时的占位值，与 {@link ChatResponse} 的约定一致。 */
    private static final int UNKNOWN_TOKENS = -1;

    private final HttpTransport transport;
    private final String baseUrl;
    private final String apiKey;
    private final Duration timeout;

    /**
     * @param baseUrl 形如 {@code https://api.deepseek.com/v1}（末尾斜杠会被去掉）
     * @param apiKey  密钥，可为空串（本地服务通常不需要）
     */
    public OpenAiCompatibleProvider(HttpTransport transport, String baseUrl, String apiKey, Duration timeout) {
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
        URI uri = URI.create(this.baseUrl + PATH_CHAT_COMPLETIONS);
        return transport
                .postJson(uri, buildHeaders(this.apiKey), buildBody(request), this.timeout)
                .thenApply(OpenAiCompatibleProvider::parseResponse);
    }

    /** 组装请求头。无密钥时不发 Authorization —— 本地服务会因多余的 Bearer 头报错。 */
    static Map<String, String> buildHeaders(String apiKey) {
        Map<String, String> headers = new LinkedHashMap<>();
        if (apiKey != null && !apiKey.isEmpty()) {
            headers.put("Authorization", "Bearer " + apiKey);
        }
        return headers;
    }

    /** 组装请求体。测试直接对这个方法断言，因此保持包级可见。 */
    static String buildBody(ChatRequest request) {
        JsonObject root = new JsonObject();
        root.addProperty("model", request.model());

        JsonArray messages = new JsonArray();
        if (request.systemPrompt() != null && !request.systemPrompt().isBlank()) {
            messages.add(message(ChatMessage.system(request.systemPrompt())));
        }
        for (ChatMessage message : request.messages()) {
            messages.add(message(message));
        }
        root.add("messages", messages);

        root.addProperty("temperature", request.temperature());
        root.addProperty("max_tokens", request.maxTokens());
        root.addProperty("stream", false);

        // 只在真的给了工具时才出现 tools 字段：关闭 ai.toolCallingEnabled 后请求体与 T001-4 逐字节一致
        if (request.hasTools()) {
            root.add("tools", ToolSpec.toJsonArray(request.tools()));
            root.addProperty("tool_choice", "auto");
        }
        return root.toString();
    }

    /**
     * 单条消息 → OpenAI 报文。
     *
     * <p>三种形态：普通 role+content；assistant 带 {@code tool_calls}
     * （其中 {@code arguments} 必须是 JSON <b>字符串</b>）；tool 结果带 {@code tool_call_id}。
     */
    private static JsonObject message(ChatMessage message) {
        JsonObject object = new JsonObject();
        object.addProperty("role", message.role());
        object.addProperty("content", message.content());

        if (message.hasToolCalls()) {
            JsonArray calls = new JsonArray();
            for (ToolCall call : message.toolCalls()) {
                JsonObject function = new JsonObject();
                function.addProperty("name", call.name());
                // 与 Ollama 相反：这里必须是字符串，不能是对象
                function.addProperty("arguments", call.argumentsJson());

                JsonObject callObject = new JsonObject();
                callObject.addProperty("id", call.id());
                callObject.addProperty("type", "function");
                callObject.add("function", function);
                calls.add(callObject);
            }
            object.add("tool_calls", calls);
        }

        // 工具结果靠 tool_call_id 与请求关联（Ollama 用 tool_name，见 OllamaProvider）
        if (ChatMessage.ROLE_TOOL.equals(message.role()) && message.toolCallId() != null) {
            object.addProperty("tool_call_id", message.toolCallId());
        }
        return object;
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

        // 报文"形状"不符合预期时（choices 不是数组、usage 不是对象等），Gson 的强转会抛
        // ClassCastException / UnsupportedOperationException。中转站返回畸形体是常态，
        // 上层只认 LlmException，这里统一收敛，保证错误话术可读
        try {
            JsonArray choices = root.getAsJsonArray("choices");
            if (choices == null || choices.isEmpty()) {
                throw new LlmException("响应里没有 choices：" + snippetOf(response.body()));
            }

            JsonObject firstChoice = choices.get(0).getAsJsonObject();
            JsonObject message = firstChoice.getAsJsonObject("message");
            if (message == null) {
                throw new LlmException("响应里没有 choices[0].message：" + snippetOf(response.body()));
            }

            String text = asString(message.get("content"));
            String finishReason = asNullableString(firstChoice.get("finish_reason"));

            int promptTokens = UNKNOWN_TOKENS;
            int completionTokens = UNKNOWN_TOKENS;
            JsonObject usage = root.getAsJsonObject("usage");
            if (usage != null) {
                promptTokens = asInt(usage.get("prompt_tokens"), UNKNOWN_TOKENS);
                completionTokens = asInt(usage.get("completion_tokens"), UNKNOWN_TOKENS);
            }

            return new ChatResponse(text, promptTokens, completionTokens, finishReason, parseToolCalls(message));
        } catch (LlmException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new LlmException("响应格式不符合预期：" + snippetOf(response.body()), e);
        }
    }

    /**
     * 解析 {@code choices[0].message.tool_calls}。
     *
     * <p>只要求调用工具时 {@code content} 通常是 {@code null}（已由 {@link #asString} 归一化为空串），
     * 因此不能拿「正文为空」当失败。缺字段一律降级为空列表，与本文档既有的宽松解析风格一致。
     */
    private static List<ToolCall> parseToolCalls(JsonObject message) {
        JsonArray raw = message.getAsJsonArray("tool_calls");
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        List<ToolCall> calls = new ArrayList<>(raw.size());
        for (int i = 0; i < raw.size(); i++) {
            JsonElement element = raw.get(i);
            if (element == null || !element.isJsonObject()) {
                continue;
            }
            JsonObject function = element.getAsJsonObject().getAsJsonObject("function");
            if (function == null) {
                continue;
            }
            String name = asString(function.get("name"));
            if (name.isEmpty()) {
                // 没有函数名的 tool call 无法执行，直接丢弃比让上层拿到一个空名字更好
                continue;
            }
            String id = asNullableString(element.getAsJsonObject().get("id"));
            // arguments 在 OpenAI 侧就是 JSON 字符串，无需再转换
            calls.add(new ToolCall(
                    id == null || id.isBlank() ? "call_" + i : id,
                    name,
                    asString(function.get("arguments"))));
        }
        return calls;
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
