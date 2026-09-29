package org.gwfx.aiassisted.core.llm;

import java.util.List;

/**
 * 一次对话请求（与具体厂商无关的中间表示）。
 *
 * <p>{@code systemPrompt} 与 {@code messages} 分开传：调用方（{@code ContextBuilder}）
 * 负责把系统提示词与「上下文数据块」拼好，各家 Provider 再翻译成自己的报文格式。
 *
 * @param model        模型名，不能为空（空值应在调用前被拦下并提示用户配置）
 * @param systemPrompt 系统提示词，可为空
 * @param messages     多轮消息（user/assistant，以及工具链里的 assistant-with-tool_calls / tool）
 * @param temperature  采样温度
 * @param maxTokens    最大生成 token
 * @param tools        本次允许模型调用的工具声明；为空时 Provider <b>完全不发</b> tools 字段
 */
public record ChatRequest(
        String model,
        String systemPrompt,
        List<ChatMessage> messages,
        double temperature,
        int maxTokens,
        List<ToolSpec> tools) {

    public ChatRequest {
        messages = messages == null ? List.of() : List.copyOf(messages);
        tools = tools == null ? List.of() : List.copyOf(tools);
    }

    /**
     * 不使用工具的便捷构造。
     *
     * <p>保留它有两个作用：T001-4 的既有链路不必改动；关闭 {@code ai.toolCallingEnabled} 后
     * 走的就是这条路径，报文与 T001-4 逐字节一致。
     */
    public ChatRequest(String model, String systemPrompt, List<ChatMessage> messages,
                       double temperature, int maxTokens) {
        this(model, systemPrompt, messages, temperature, maxTokens, List.of());
    }

    public boolean hasTools() {
        return !tools.isEmpty();
    }
}
