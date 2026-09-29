package org.gwfx.aiassisted.core.text;

/**
 * 危险级指令文本的归一化与校验（零 MC 依赖，可直接单测）。
 *
 * <p><b>为什么单独一层</b>：这段文本来自模型输出，是<b>不可信输入</b>，而且它最终会
 * 被交给 Brigadier 解析执行。校验规则必须只有一份、且能被单测逐条覆盖 ——
 * 写在工具实现里就只能靠真机试。
 *
 * <p><b>规则与理由</b>：
 * <ul>
 *   <li>去掉前导 {@code /}：玩家和模型都习惯写斜杠，但 {@code performPrefixedCommand}
 *       期望的是不带斜杠的原文。统一在这里剥掉，比每次调用处各写一遍可靠。</li>
 *   <li>拒绝换行与控制字符：整条指令最终会被塞进聊天提示与日志，换行能让上下文里的
 *       "待确认指令"看起来像两条不同的东西 —— 那是提示词注入的原料。</li>
 *   <li>长度上限：正常指令远短于此；超长只可能是模型把整段 JSON 或文档塞了进来。</li>
 * </ul>
 */
public final class CommandText {

    /** 指令长度上限（字符）。 */
    public static final int MAX_COMMAND_CHARS = 256;

    /** 理由文本上限（字符）；它只展示给玩家，不做语义判断。 */
    public static final int MAX_REASON_CHARS = 100;

    private CommandText() {
    }

    /**
     * 归一化结果：{@code command} 与 {@code error} 二选一。
     *
     * @param command 合法时是不含前导 {@code /} 的指令原文；不合法时为空串
     * @param error   不合法时是给模型看的原因（一句话，说明该怎么改）；合法时为空串
     */
    public record Result(String command, String error) {

        public Result {
            command = command == null ? "" : command;
            error = error == null ? "" : error;
        }

        public boolean ok() {
            return this.error.isEmpty();
        }

        static Result failed(String error) {
            return new Result("", error);
        }
    }

    /** 归一化指令：剥前导斜杠、去首尾空白、拒绝控制字符与超长。 */
    public static Result normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            return Result.failed("指令不能为空");
        }
        String stripped = raw.strip();
        // 允许模型写 "//list"（手滑）或 "/list"：把所有前导斜杠一次剥掉
        while (stripped.startsWith("/")) {
            stripped = stripped.substring(1).strip();
        }
        if (stripped.isEmpty()) {
            return Result.failed("指令不能只有一个斜杠");
        }
        for (int i = 0; i < stripped.length(); i++) {
            char c = stripped.charAt(i);
            if (c == '\n' || c == '\r' || c == '\t' || Character.isISOControl(c)) {
                return Result.failed("指令里不能有换行或控制字符");
            }
        }
        if (stripped.length() > MAX_COMMAND_CHARS) {
            return Result.failed("指令太长（上限 " + MAX_COMMAND_CHARS + " 字符）");
        }
        return new Result(stripped, "");
    }

    /** 归一化理由文本：去首尾空白并把换行压成空格，超长截断（不报错 —— 理由缺失不该拦住执行）。 */
    public static String normalizeReason(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        StringBuilder cleaned = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length() && cleaned.length() < MAX_REASON_CHARS; i++) {
            char c = raw.charAt(i);
            cleaned.append(c == '\n' || c == '\r' || c == '\t' || Character.isISOControl(c) ? ' ' : c);
        }
        return cleaned.toString().strip();
    }
}
