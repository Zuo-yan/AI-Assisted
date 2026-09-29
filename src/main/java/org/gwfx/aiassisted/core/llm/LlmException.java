package org.gwfx.aiassisted.core.llm;

/**
 * LLM 调用失败。
 *
 * <p><b>约定：本异常的 message 必须已经过脱敏</b>（不得包含 API Key、完整请求体、带密钥的 URL）。
 * 提供方在本包内构造消息时只允许写入 HTTP 状态码与截断后的响应片段，
 * 真正的密钥替换由 {@code ai.core.text.KeyRedactor} 在 ai.* 层统一兜底。
 *
 * <p>{@link #httpStatus()} 让上层能把失败翻译成更具体的话术
 * （401/403 → Key 无效，429 → 被限流，5xx → 服务端故障），而不是笼统的"请求失败"。
 */
public class LlmException extends RuntimeException {

    /** 与 HTTP 无关的失败（未配置密钥、响应格式不对等）时使用。 */
    public static final int NO_HTTP_STATUS = -1;

    private final int httpStatus;

    public LlmException(String message) {
        this(message, null, NO_HTTP_STATUS);
    }

    public LlmException(String message, Throwable cause) {
        this(message, cause, NO_HTTP_STATUS);
    }

    public LlmException(String message, Throwable cause, int httpStatus) {
        super(message, cause);
        this.httpStatus = httpStatus;
    }

    /** 携带 HTTP 状态码的失败。 */
    public static LlmException http(int statusCode, String message) {
        return new LlmException(message, null, statusCode);
    }

    /** HTTP 状态码；{@link #NO_HTTP_STATUS} 表示与 HTTP 无关。 */
    public int httpStatus() {
        return httpStatus;
    }
}
