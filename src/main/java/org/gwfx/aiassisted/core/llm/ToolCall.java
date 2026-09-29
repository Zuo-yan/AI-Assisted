package org.gwfx.aiassisted.core.llm;

/**
 * 模型要求调用一次工具（T001-5）。
 *
 * <p><b>argumentsJson 为什么统一是「JSON 字符串」</b>：两家 Provider 的报文在这里正好相反 ——
 * OpenAI 的 {@code function.arguments} 本身就是一个 JSON <b>字符串</b>（还要二次 parse），
 * Ollama 的 {@code arguments} 则是一个 JSON <b>对象</b>。
 * 与其让上层到处判断「这次拿到的是哪种」，不如让 Provider 在解析阶段就归一化成字符串，
 * 上层（AgentLoop / 工具）只面对一种形态。
 *
 * @param id            工具调用标识。OpenAI 用它关联结果（{@code tool_call_id}）；
 *                      Ollama 不返回 id，由 Provider 生成一个同一步内稳定的兜底值
 * @param name          工具名，对应 {@code ToolSpec#name()}
 * @param argumentsJson 入参 JSON 字符串；为空时归一化为 {@code "{}"}
 */
public record ToolCall(String id, String name, String argumentsJson) {

    public ToolCall {
        id = id == null ? "" : id;
        name = name == null ? "" : name;
        argumentsJson = argumentsJson == null || argumentsJson.isBlank() ? "{}" : argumentsJson;
    }
}
