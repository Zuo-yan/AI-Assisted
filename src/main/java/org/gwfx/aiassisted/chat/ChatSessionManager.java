package org.gwfx.aiassisted.chat;

import org.gwfx.aiassisted.core.context.ChatHistory;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 按玩家持有会话历史。
 *
 * <p>本轮<b>只存内存</b>：跨会话持久化属于 T001-8（持久记忆），
 * 因此服务端重启后历史清空，{@code /ai status} 会把这一点写清楚，
 * 避免玩家以为是「AI 失忆 bug」。
 */
public final class ChatSessionManager {

    private final Map<UUID, ChatHistory> histories = new ConcurrentHashMap<>();

    private volatile int maxMessages = 20;
    private volatile int maxChars = 8000;

    /** 取（必要时创建）某位玩家的历史。 */
    public ChatHistory history(UUID playerId) {
        return histories.computeIfAbsent(playerId,
                id -> new ChatHistory(this.maxMessages, this.maxChars));
    }

    /** 清空某位玩家的历史。 */
    public void clear(UUID playerId) {
        ChatHistory history = histories.get(playerId);
        if (history != null) {
            history.clear();
        }
    }

    /** 玩家退出时回收，避免长时间开服后 Map 里堆满离线玩家。 */
    public void forget(UUID playerId) {
        histories.remove(playerId);
    }

    /** 配置热重载：更新上限并让已有会话立即按新上限重新裁剪。 */
    public void configure(int maxMessages, int maxChars) {
        this.maxMessages = maxMessages;
        this.maxChars = maxChars;
        for (ChatHistory history : histories.values()) {
            history.setLimits(maxMessages, maxChars);
        }
    }
}
