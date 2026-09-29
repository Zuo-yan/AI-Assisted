package org.gwfx.aiassisted.chat;

import org.gwfx.aiassisted.core.context.ContextSnapshot;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 最近一次「玩家确认执行」的指令（T002），用于让下一轮对话能谈它的结果。
 *
 * <p><b>为什么需要它</b>：确认执行后我们刻意<b>不</b>再自动调一次模型（省一次计费，也避免
 * 模型借已批准的指令继续加码），所以命令输出只能靠下一次提问带回去 —— 就是通过
 * {@link ContextSnapshot.RecentCommand} 这一行事实。没有它，玩家问"刚才 /list 的结果是什么"
 * 时模型只能干瞪眼。
 *
 * <p><b>为什么在这里就截断</b>：这一行会进 {@code <context>}（整块上限 2000 字符），
 * 一条 {@code /data get} 的输出能轻松几百字。截断必须发生在入库时，不能指望渲染层兜。
 *
 * <p>同样是仅内存（与 {@link GoalStore} 一致）。
 */
public final class LastCommandStore {

    /** 输出摘要上限（字符）。给指令原文与其余上下文留出预算。 */
    public static final int MAX_OUTPUT_CHARS = 200;

    /** 指令原文上限。与 {@code ProposeCommandTool} 的校验上限一致，这里是第二道防线。 */
    public static final int MAX_COMMAND_CHARS = 256;

    private final Map<UUID, Entry> entries = new ConcurrentHashMap<>();

    /** 一条已执行记录：指令 + 输出摘要 + 时刻（时刻用于渲染"x 秒前"）。 */
    private record Entry(String command, String output, long atMillis) {
    }

    /** 记下一次已确认执行。 */
    public void record(UUID playerId, String command, String output, long nowMillis) {
        if (playerId == null) {
            return;
        }
        this.entries.put(playerId, new Entry(truncate(command, MAX_COMMAND_CHARS),
                truncate(output, MAX_OUTPUT_CHARS), nowMillis));
    }

    /**
     * 取该玩家最近一次执行结果，转成上下文事实。
     *
     * @return 没有记录时返回 {@code null}（调用方与渲染层都按"不渲染这一行"处理）
     */
    public ContextSnapshot.RecentCommand recentFor(UUID playerId, long nowMillis) {
        Entry entry = playerId == null ? null : this.entries.get(playerId);
        if (entry == null) {
            return null;
        }
        long secondsAgo = Math.max(0L, (nowMillis - entry.atMillis()) / 1000L);
        return new ContextSnapshot.RecentCommand(entry.command(), entry.output(), secondsAgo);
    }

    /** 玩家退出时清理。 */
    public void clear(UUID playerId) {
        if (playerId != null) {
            this.entries.remove(playerId);
        }
    }

    private static String truncate(String text, int maxChars) {
        if (text == null) {
            return "";
        }
        String stripped = text.strip();
        return stripped.length() > maxChars ? stripped.substring(0, maxChars) + "…" : stripped;
    }
}
