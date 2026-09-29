package org.gwfx.aiassisted.command;

import net.minecraft.commands.CommandSource;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 收集一次指令执行的输出（T002）。
 *
 * <p><b>为什么要自己实现 {@link CommandSource}</b>：{@code performPrefixedCommand} 会把指令的
 * 反馈发给传入的 source。若直接用玩家的 source，输出就直接进玩家聊天栏（也可能被压掉），
 * 我们既拿不到、也没法统一截断。换成一个收集器，就能"执行完把结果一次性给玩家 + 存进上下文"。
 *
 * <p><b>{@code shouldInformAdmins()} 返回 false</b>：原版对 op 执行的部分指令会向其他管理员广播，
 * 这里刻意关掉 —— 由本模组自己的审计日志承担记录职责，避免在执行本就由玩家确认的前提下刷屏。
 */
public final class CommandOutputCollector implements CommandSource {

    /** 最多保留几行；超出只标记截断（不继续拼字符串）。 */
    private static final int MAX_LINES = 50;

    /** 总字符上限；超出同样只标记。 */
    private static final int MAX_CHARS = 1000;

    private final List<String> lines = new ArrayList<>();
    private int chars;
    private boolean truncated;

    @Override
    public void sendSystemMessage(Component message) {
        if (message == null) {
            return;
        }
        if (this.lines.size() >= MAX_LINES || this.chars >= MAX_CHARS) {
            this.truncated = true;
            return;
        }
        String text = message.getString();
        if (text == null || text.isBlank()) {
            return;
        }
        int remaining = MAX_CHARS - this.chars;
        if (text.length() > remaining) {
            text = text.substring(0, remaining) + "…";
            this.truncated = true;
        }
        this.chars += text.length();
        this.lines.add(text);
    }

    @Override
    public boolean acceptsSuccess() {
        return true;
    }

    @Override
    public boolean acceptsFailure() {
        return true;
    }

    @Override
    public boolean shouldInformAdmins() {
        return false;
    }

    /** 收集到的输出（多行用换行连接）；没有输出时返回空串。 */
    public String text() {
        return String.join("\n", this.lines);
    }

    public boolean isEmpty() {
        return this.lines.isEmpty();
    }

    /** 是否因为上限而丢掉了内容 —— 要让玩家知道"这不是全部输出"。 */
    public boolean truncated() {
        return this.truncated;
    }
}
