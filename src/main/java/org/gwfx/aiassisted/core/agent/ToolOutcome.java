package org.gwfx.aiassisted.core.agent;

/**
 * 一次工具执行的结果。
 *
 * <p>{@code text} 会被原样塞进消息链回传给模型，因此它就是「工具对模型说的话」——
 * 既要给出结论，也要在失败时给出可读原因（模型会据此换参数重试，而不是干瞪眼）。
 *
 * <p><b>为什么在构造里就截断</b>：工具结果最终会进上下文预算，长度必须在入口处封死，
 * 不能指望每个工具实现都记得自己截断。
 */
public record ToolOutcome(boolean ok, String text) {

    /** 回传给模型的文本上限（字符）。超长（例如搜方块命中一大片）必须截断。 */
    public static final int MAX_TEXT_CHARS = 4000;

    private static final String TRUNCATED = "…(已截断)";

    public ToolOutcome {
        text = text == null ? "" : text;
        if (text.length() > MAX_TEXT_CHARS) {
            text = text.substring(0, MAX_TEXT_CHARS) + TRUNCATED;
        }
    }

    public static ToolOutcome ok(String text) {
        return new ToolOutcome(true, text);
    }

    public static ToolOutcome error(String text) {
        return new ToolOutcome(false, text);
    }

    /** 模型编了一个不存在的工具名时使用。 */
    public static ToolOutcome unknownTool(String name) {
        return error("未知工具「" + name + "」，本服务端没有注册该工具。");
    }
}
