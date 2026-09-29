package org.gwfx.aiassisted.core.llm;

import java.util.List;

/**
 * 一条对话消息。role 取值见本类常量。
 *
 * <p>T001-5 起新增三个可选字段用于工具调用；{@code user/assistant/system} 只关心正文，
 * 新字段留空，因此 T001-4 的既有链路（与单测）行为完全不变。
 *
 * <p><b>为什么同一个类型同时带 {@code toolCallId} 与 {@code toolName}</b>：
 * 这正是两家协议的分歧点 —— OpenAI 用 id 关联工具结果（{@code tool_call_id}），
 * Ollama 用函数名关联（{@code tool_name}，它根本不返回 id）。
 * 一个消息类型同时携带两者、由各自的 Provider 取所需，比在上层分叉出两个消息类型简单得多。
 *
 * <p>刻意保持扁平（不加子类层级）：工具链只存在于一次请求的生命周期内，不值得为它建类型体系。
 *
 * @param role       见 {@link #ROLE_SYSTEM} 等常量
 * @param content    正文；工具结果消息里是回传给模型的文本
 * @param toolCalls  仅 assistant 消息可能非空：模型要求调用的工具列表
 * @param toolCallId 仅 tool 结果消息使用：OpenAI 用它关联请求
 * @param toolName   仅 tool 结果消息使用：Ollama 用它关联请求
 */
public record ChatMessage(
        String role,
        String content,
        List<ToolCall> toolCalls,
        String toolCallId,
        String toolName) {

    public static final String ROLE_SYSTEM = "system";
    public static final String ROLE_USER = "user";
    public static final String ROLE_ASSISTANT = "assistant";
    public static final String ROLE_TOOL = "tool";

    public ChatMessage {
        role = role == null ? ROLE_USER : role;
        content = content == null ? "" : content;
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        // toolCallId / toolName 只对 tool 结果消息有意义，其余消息为 null 属正常状态
    }

    public static ChatMessage system(String content) {
        return new ChatMessage(ROLE_SYSTEM, content, List.of(), null, null);
    }

    public static ChatMessage user(String content) {
        return new ChatMessage(ROLE_USER, content, List.of(), null, null);
    }

    public static ChatMessage assistant(String content) {
        return new ChatMessage(ROLE_ASSISTANT, content, List.of(), null, null);
    }

    /** 模型要求调用工具的 assistant 消息（正文可能为空）。 */
    public static ChatMessage assistantToolCalls(String content, List<ToolCall> toolCalls) {
        return new ChatMessage(ROLE_ASSISTANT, content, toolCalls, null, null);
    }

    /**
     * 工具执行结果消息。
     *
     * <p>同时写入 id 与 name：OpenAI 认 id，Ollama 认 name，各取所需。
     */
    public static ChatMessage toolResult(ToolCall call, String content) {
        return new ChatMessage(ROLE_TOOL, content, List.of(), call.id(), call.name());
    }

    public boolean hasToolCalls() {
        return !toolCalls.isEmpty();
    }
}
