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
 * Anthropic Messages API 端点（POST {baseUrl}/v1/messages）。
 *
 * <p>兼容 Claude 系列模型及所有遵循 Anthropic Messages 规范的中转/逆向端点。
 */
public final class AnthropicProvider implements LlmProvider {

    public static final String ID = "anthropic";
    public static final String DEFAULT_BASE_URL = "https://api.anthropic.com";
    private static final String DEFAULT_VERSION = "2023-06-01";
    private static final int DEFAULT_MAX_TOKENS = 1024;
    private static final int MAX_ERROR_SNIPPET = 200;
    private static final int UNKNOWN_TOKENS = -1;

    private final HttpTransport transport;
    private final String baseUrl;
    private final String apiKey;
    private final Duration timeout;

    public AnthropicProvider(HttpTransport transport, String baseUrl, String apiKey, Duration timeout) {
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
                .thenApply(AnthropicProvider::parseResponse);
    }

    static String resolveEndpoint(String baseUrl) {
        String base = stripTrailingSlash(baseUrl);
        if (base.isEmpty()) {
            base = DEFAULT_BASE_URL;
        }
        if (base.endsWith("/v1")) {
            return base + "/messages";
        }
        if (base.endsWith("/v1/messages")) {
            return base;
        }
        return base + "/v1/messages";
    }

    static Map<String, String> buildHeaders(String apiKey) {
        Map<String, String> headers = new LinkedHashMap<>();
        if (apiKey != null && !apiKey.isEmpty()) {
            headers.put("x-api-key", apiKey);
        }
        headers.put("anthropic-version", DEFAULT_VERSION);
        return headers;
    }

    static String buildBody(ChatRequest request) {
        JsonObject root = new JsonObject();
        root.addProperty("model", request.model());

        int maxTokens = request.maxTokens() > 0 ? request.maxTokens() : DEFAULT_MAX_TOKENS;
        root.addProperty("max_tokens", maxTokens);
        root.addProperty("temperature", request.temperature());

        if (request.systemPrompt() != null && !request.systemPrompt().isBlank()) {
            root.addProperty("system", request.systemPrompt());
        }

        JsonArray messages = new JsonArray();
        for (ChatMessage message : request.messages()) {
            JsonObject item = buildMessage(message);
            if (item != null) {
                messages.add(item);
            }
        }
        root.add("messages", messages);

        if (request.hasTools()) {
            root.add("tools", buildTools(request.tools()));
        }

        return root.toString();
    }

    private static JsonObject buildMessage(ChatMessage message) {
        if (ChatMessage.ROLE_SYSTEM.equals(message.role())) {
            // 系统消息已在顶层 system 处理，若出现在 messages 列表则跳过或转 user
            return null;
        }

        JsonObject obj = new JsonObject();
        if (ChatMessage.ROLE_TOOL.equals(message.role())) {
            obj.addProperty("role", "user");
            JsonArray content = new JsonArray();
            JsonObject toolResult = new JsonObject();
            toolResult.addProperty("type", "tool_result");
            toolResult.addProperty("tool_use_id", message.toolCallId() != null ? message.toolCallId() : "");
            toolResult.addProperty("content", message.content());
            content.add(toolResult);
            obj.add("content", content);
            return obj;
        }

        if (ChatMessage.ROLE_ASSISTANT.equals(message.role())) {
            obj.addProperty("role", "assistant");
            if (message.hasToolCalls()) {
                JsonArray content = new JsonArray();
                if (!message.content().isEmpty()) {
                    JsonObject textBlock = new JsonObject();
                    textBlock.addProperty("type", "text");
                    textBlock.addProperty("text", message.content());
                    content.add(textBlock);
                }
                for (ToolCall call : message.toolCalls()) {
                    JsonObject toolUse = new JsonObject();
                    toolUse.addProperty("type", "tool_use");
                    toolUse.addProperty("id", call.id());
                    toolUse.addProperty("name", call.name());
                    toolUse.add("input", parseJsonOrEmpty(call.argumentsJson()));
                    content.add(toolUse);
                }
                obj.add("content", content);
                return obj;
            }
            obj.addProperty("content", message.content());
            return obj;
        }

        // 默认 user
        obj.addProperty("role", "user");
        obj.addProperty("content", message.content());
        return obj;
    }

    private static JsonArray buildTools(List<ToolSpec> specs) {
        JsonArray array = new JsonArray();
        for (ToolSpec spec : specs) {
            JsonObject tool = new JsonObject();
            tool.addProperty("name", spec.name());
            tool.addProperty("description", spec.description());
            tool.add("input_schema", spec.parametersSchema());
            array.add(tool);
        }
        return array;
    }

    static ChatResponse parseResponse(HttpResponseData response) {
        if (response.statusCode() >= 400) {
            throw new LlmException("Anthropic API 错误 (HTTP " + response.statusCode() + ")" + snippetOf(response.body()));
        }

        JsonObject root;
        try {
            root = JsonParser.parseString(response.body()).getAsJsonObject();
        } catch (JsonSyntaxException | IllegalStateException e) {
            throw new LlmException("Anthropic 响应解析失败：不是合法的 JSON" + snippetOf(response.body()));
        }

        StringBuilder text = new StringBuilder();
        List<ToolCall> toolCalls = new ArrayList<>();

        if (root.has("content") && root.get("content").isJsonArray()) {
            JsonArray content = root.getAsJsonArray("content");
            for (int i = 0; i < content.size(); i++) {
                JsonElement el = content.get(i);
                if (el == null || !el.isJsonObject()) continue;
                JsonObject block = el.getAsJsonObject();
                String type = asString(block.get("type"));
                if ("text".equals(type)) {
                    text.append(asString(block.get("text")));
                } else if ("tool_use".equals(type)) {
                    String id = asString(block.get("id"));
                    if (id.isEmpty()) {
                        id = "toolu_" + i;
                    }
                    String name = asString(block.get("name"));
                    JsonElement input = block.get("input");
                    String argsJson = (input != null && input.isJsonObject()) ? input.toString() : "{}";
                    toolCalls.add(new ToolCall(id, name, argsJson));
                }
            }
        }

                int promptTokens = UNKNOWN_TOKENS;
        int completionTokens = UNKNOWN_TOKENS;
        if (root.has("usage") && root.get("usage").isJsonObject()) {
            JsonObject usage = root.getAsJsonObject("usage");
            promptTokens = asInt(usage.get("input_tokens"), UNKNOWN_TOKENS);
            completionTokens = asInt(usage.get("output_tokens"), UNKNOWN_TOKENS);
        }

        String stopReason = asString(root.get("stop_reason"));
        String finishReason = switch (stopReason) {
            case "end_turn" -> "stop";
            case "tool_use" -> "tool_calls";
            case "max_tokens" -> "length";
            default -> stopReason.isEmpty() ? "stop" : stopReason;
        };

        return new ChatResponse(text.toString(), promptTokens, completionTokens, finishReason, toolCalls);
    }

    private static JsonObject parseJsonOrEmpty(String json) {
        if (json == null || json.isBlank()) {
            return new JsonObject();
        }
        try {
            JsonElement el = JsonParser.parseString(json);
            return el.isJsonObject() ? el.getAsJsonObject() : new JsonObject();
        } catch (JsonSyntaxException e) {
            return new JsonObject();
        }
    }

    private static String asString(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return "";
        }
        return element.isJsonPrimitive() ? element.getAsString() : element.toString();
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
