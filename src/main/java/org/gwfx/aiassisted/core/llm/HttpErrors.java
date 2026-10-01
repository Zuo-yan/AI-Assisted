package org.gwfx.aiassisted.core.llm;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.gwfx.aiassisted.core.http.HttpResponseData;

/**
 * 把非 2xx 的 HTTP 响应翻译成 {@link LlmException}。
 *
 * <p>模型厂商与中转站（one-api / new-api 及各类聚合层）的错误体基本都遵循
 * {@code {"error":{"message":"…"}}} 的 OpenAI 形态，Anthropic 也是同构；把里面的
 * message 提出来给玩家看，远比甩一段原始 JSON 可读 —— 例如
 * "This token has no access to model xxx" 一眼可知是令牌没有该模型的权限。
 * 提不出来（网关 HTML、纯文本）就退回「截断原文」的老路。
 */
final class HttpErrors {

    /** 提取出的错误文案与原文片段都截到这个长度，避免把整页响应塞进聊天/日志。 */
    private static final int MAX_SNIPPET = 200;

    private HttpErrors() {
    }

    /** 非 2xx 统一入口：异常带状态码；message 已尽量人话化，密钥脱敏仍由 ai.* 层统一兜底。 */
    static LlmException fromResponse(HttpResponseData response) {
        String code = "HTTP " + response.statusCode();
        String message = errorMessageOf(response.body());
        if (!message.isEmpty()) {
            return LlmException.http(response.statusCode(), code + "：" + ellipsis(message));
        }
        String body = response.body() == null ? "" : response.body().strip();
        if (body.isEmpty()) {
            return LlmException.http(response.statusCode(), code);
        }
        return LlmException.http(response.statusCode(),
                code + "：" + (body.length() > MAX_SNIPPET ? body.substring(0, MAX_SNIPPET) + "…" : body));
    }

    /**
     * 从错误体里抠人话：优先 {@code error.message}（OpenAI / Anthropic / 中转站通用），
     * 其次顶层 {@code message}（部分中转站直接平铺在根节点）。抠不到返回空串。
     */
    private static String errorMessageOf(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        try {
            JsonElement parsed = JsonParser.parseString(body);
            if (!parsed.isJsonObject()) {
                return "";
            }
            JsonObject root = parsed.getAsJsonObject();
            String message = messageAt(root.get("error"));
            if (message.isEmpty()) {
                message = messageAt(root);
            }
            return message;
        } catch (RuntimeException e) {
            // 不是 JSON（网关 HTML、纯文本）就走原文截断
            return "";
        }
    }

    private static String messageAt(JsonElement node) {
        if (node == null || !node.isJsonObject()) {
            return "";
        }
        JsonElement message = node.getAsJsonObject().get("message");
        if (message == null || !message.isJsonPrimitive()) {
            return "";
        }
        String text = message.getAsString().strip();
        return text.isEmpty() ? "" : text;
    }

    private static String ellipsis(String message) {
        return message.length() > MAX_SNIPPET ? message.substring(0, MAX_SNIPPET) + "…" : message;
    }
}
