package org.gwfx.aiassisted.core.context;

import org.gwfx.aiassisted.core.llm.ChatMessage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatHistoryTest {

    @Test
    void keepsMessagesInOrder() {
        ChatHistory history = new ChatHistory(10, 10_000);
        history.add(ChatMessage.user("第一句"));
        history.add(ChatMessage.assistant("第一答"));
        history.add(ChatMessage.user("第二句"));

        var messages = history.messages();
        assertEquals(3, messages.size());
        assertEquals("第一句", messages.get(0).content());
        assertEquals("第一答", messages.get(1).content());
        assertEquals("第二句", messages.get(2).content());
    }

    @Test
    void dropsOldestMessagesWhenMessageLimitExceeded() {
        ChatHistory history = new ChatHistory(4, 10_000);
        for (int i = 1; i <= 6; i++) {
            history.add(ChatMessage.user("msg" + i));
        }

        var messages = history.messages();
        assertEquals(4, messages.size());
        // 最旧的被丢掉，最新的必须还在
        assertEquals("msg3", messages.get(0).content());
        assertEquals("msg6", messages.get(3).content());
    }

    @Test
    void dropsOldestMessagesWhenCharBudgetExceeded() {
        ChatHistory history = new ChatHistory(100, 200);
        for (int i = 0; i < 10; i++) {
            history.add(ChatMessage.assistant("x".repeat(50)));
        }

        assertTrue(history.size() < 10, "字符预算必须生效");
        assertTrue(history.size() >= 1, "至少要保住最后一条");
    }

    @Test
    void alwaysKeepsAtLeastTheLatestMessage() {
        // 单条消息本身就超过字符预算时，也不能裁成空历史
        ChatHistory history = new ChatHistory(2, 200);
        history.add(ChatMessage.user("y".repeat(5_000)));

        assertEquals(1, history.size());
        assertEquals(5_000, history.messages().get(0).content().length());
    }

    @Test
    void ignoresNullMessage() {
        ChatHistory history = new ChatHistory(4, 1_000);
        history.add(null);
        assertTrue(history.isEmpty());
    }

    @Test
    void clearEmptiesHistory() {
        ChatHistory history = new ChatHistory(4, 1_000);
        history.add(ChatMessage.user("hi"));
        history.clear();

        assertTrue(history.isEmpty());
        assertEquals(0, history.size());
    }

    @Test
    void setLimitsAppliesImmediately() {
        ChatHistory history = new ChatHistory(50, 10_000);
        for (int i = 1; i <= 10; i++) {
            history.add(ChatMessage.user("msg" + i));
        }
        assertEquals(10, history.size());

        // /ai reload 改了上限后应立刻重新裁剪，而不是等下次清空
        history.setLimits(3, 10_000);

        assertEquals(3, history.size());
        assertEquals("msg8", history.messages().get(0).content());
        assertEquals("msg10", history.messages().get(2).content());
    }

    @Test
    void enforcesMinimumLimits() {
        ChatHistory history = new ChatHistory(0, 0);
        history.add(ChatMessage.user("a"));
        history.add(ChatMessage.user("b"));
        history.add(ChatMessage.user("c"));

        // 条数下限 2（至少能放下「一轮」）
        assertEquals(2, history.size());
        assertFalse(history.isEmpty());
    }
}
