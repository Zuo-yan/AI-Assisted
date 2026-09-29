package org.gwfx.aiassisted.core.llm;

import java.util.List;

/**
 * 一次对话响应。
 *
 * @param text             模型回复正文（只要求调用工具时可能为空串）
 * @param promptTokens     输入 token 数，未知时 {@code -1}
 * @param completionTokens 输出 token 数，未知时 {@code -1}
 * @param finishReason     结束原因，未知时 {@code null}
 * @param toolCalls        模型要求调用的工具，没有时为空列表
 */
public record ChatResponse(
        String text,
        int promptTokens,
        int completionTokens,
        String finishReason,
        List<ToolCall> toolCalls) {

    private static final int UNKNOWN = -1;

    public ChatResponse {
        text = text == null ? "" : text;
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }

    /** 不含工具调用的便捷构造（T001-4 及既有单测路径）。 */
    public ChatResponse(String text, int promptTokens, int completionTokens, String finishReason) {
        this(text, promptTokens, completionTokens, finishReason, List.of());
    }

    /** 只拿到正文时的便捷构造（token 用量未知）。 */
    public static ChatResponse of(String text) {
        return new ChatResponse(text, UNKNOWN, UNKNOWN, null, List.of());
    }

    /** 是否拿到了完整的 token 用量（两项都已知），用于「显示本次消耗」的展示逻辑。 */
    public boolean hasUsage() {
        return promptTokens >= 0 && completionTokens >= 0;
    }

    /** 模型是否要求调用工具；为真时正文通常为空，应继续 Agent 循环而不是当作最终答案。 */
    public boolean hasToolCalls() {
        return !toolCalls.isEmpty();
    }
}
