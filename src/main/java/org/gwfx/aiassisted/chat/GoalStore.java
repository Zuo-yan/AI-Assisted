package org.gwfx.aiassisted.chat;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 按玩家持有「当前任务目标」（{@code /ai goal}）。
 *
 * <p><b>它是什么</b>：一句写给 AI 看的备忘，说明玩家正在忙什么。没有它，「我现在缺什么」这类问题
 * 根本无从回答 —— 因为「缺」永远是相对于某个目标说的，而模型看不到玩家心里的计划。
 *
 * <p><b>为什么仅存内存</b>：与 {@link ChatHistory} 的立场一致，跨会话持久化属于 T001-8；
 * 服务端重启后目标清空。
 *
 * <p><b>为什么只由玩家设定</b>：不提供让模型写目标的工具 —— 那等于让模型自己给自己派活，
 * 会立刻放大工具调用次数（每一次都是用户自费的调用），也让「谁在推动这件事」变得不可追溯。
 */
public final class GoalStore {

    /**
     * 目标文本上限。
     *
     * <p>{@code ContextRenderer} 渲染时会再夹一次，这里夹是为了让 {@code /ai goal show}
     * 回显的就是真正生效的内容，不会出现「存了 500 字、发出去只有 200 字」的落差。
     */
    public static final int MAX_CHARS = 200;

    private final Map<UUID, String> goals = new ConcurrentHashMap<>();

    /**
     * 设定目标；空白输入等价于清除。
     *
     * @return 实际生效的文本（可能被截断）；清除时返回空串
     */
    public String set(UUID playerId, String rawGoal) {
        if (playerId == null) {
            return "";
        }
        String cleaned = normalize(rawGoal);
        if (cleaned.isEmpty()) {
            this.goals.remove(playerId);
            return "";
        }
        this.goals.put(playerId, cleaned);
        return cleaned;
    }

    /** 取目标；未设定时返回空串（不返回 null，省掉调用方的判空）。 */
    public String goalOf(UUID playerId) {
        if (playerId == null) {
            return "";
        }
        return this.goals.getOrDefault(playerId, "");
    }

    /** 清除目标。玩家退出时也走这里，避免长时间开服后 Map 里堆满离线玩家。 */
    public void clear(UUID playerId) {
        if (playerId != null) {
            this.goals.remove(playerId);
        }
    }

    private static String normalize(String rawGoal) {
        if (rawGoal == null) {
            return "";
        }
        String stripped = rawGoal.strip();
        return stripped.length() > MAX_CHARS ? stripped.substring(0, MAX_CHARS) : stripped;
    }
}
