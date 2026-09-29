package org.gwfx.aiassisted.core.context;

import org.gwfx.aiassisted.core.llm.ChatMessage;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * 单个玩家的多轮对话历史（本轮仅内存，持久化属于 T001-8）。
 *
 * <p>只存 user / assistant 两种角色 —— system 提示词由 {@code AiChatService}
 * 每轮从配置与上下文重新拼装，不占历史配额，也避免摘要/裁剪把它弄丢。
 *
 * <p>裁剪策略：<b>从最旧的一轮开始丢</b>，且至少保住最后一条。
 * 这样最近的一问一答永远在窗口内，越久远的内容越先被牺牲。
 */
public final class ChatHistory {

    private final Deque<ChatMessage> messages = new ArrayDeque<>();
    private volatile int maxMessages;
    private volatile int maxChars;

    public ChatHistory(int maxMessages, int maxChars) {
        this.maxMessages = Math.max(2, maxMessages);
        this.maxChars = Math.max(200, maxChars);
    }

    /**
     * 更新裁剪上限并立即重新裁剪。
     *
     * <p>供 {@code /ai reload} 使用：改配置后已有会话也要跟着生效，
     * 而不是等下次清空历史才应用新值。
     */
    public synchronized void setLimits(int maxMessages, int maxChars) {
        this.maxMessages = Math.max(2, maxMessages);
        this.maxChars = Math.max(200, maxChars);
        trim();
    }

    /** 追加一条消息并立即裁剪。 */
    public synchronized void add(ChatMessage message) {
        if (message == null) {
            return;
        }
        messages.addLast(message);
        trim();
    }

    /** 当前历史快照（按时间从旧到新）。 */
    public synchronized List<ChatMessage> messages() {
        return new ArrayList<>(messages);
    }

    public synchronized void clear() {
        messages.clear();
    }

    public synchronized int size() {
        return messages.size();
    }

    public synchronized boolean isEmpty() {
        return messages.isEmpty();
    }

    private void trim() {
        while (messages.size() > 1 && (messages.size() > maxMessages || totalChars() > maxChars)) {
            messages.removeFirst();
        }
    }

    private int totalChars() {
        int total = 0;
        for (ChatMessage message : messages) {
            total += message.content().length();
        }
        return total;
    }
}
