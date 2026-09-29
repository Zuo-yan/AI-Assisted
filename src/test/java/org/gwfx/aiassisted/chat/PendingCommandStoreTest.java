package org.gwfx.aiassisted.chat;

import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 待确认指令的生命周期（T002）。
 *
 * <p>这几条都是安全属性，不是普通业务逻辑：待确认项<b>过期即不可执行</b>、
 * <b>一人同时只有一条</b>、<b>取到即消费</b>（一次确认只执行一次）。
 */
class PendingCommandStoreTest {

    private static final UUID PLAYER = UUID.randomUUID();

    @Test
    void takeReturnsTheProposalAndConsumesIt() {
        PendingCommandStore store = new PendingCommandStore();
        store.propose(PLAYER, "list", "看看谁在线", 1_000L);

        Optional<PendingCommandStore.Pending> taken = store.take(PLAYER, 1_500L);

        assertTrue(taken.isPresent());
        assertEquals("list", taken.get().command());
        assertEquals("看看谁在线", taken.get().reason());
        // 第二次取不到：一次确认只能执行一次
        assertTrue(store.take(PLAYER, 1_600L).isEmpty());
    }

    @Test
    void expiredProposalIsNeverExecutable() {
        // TTL 内仍然有效
        PendingCommandStore fresh = new PendingCommandStore();
        fresh.propose(PLAYER, "stop", "", 1_000L);
        assertTrue(fresh.take(PLAYER, 1_000L + PendingCommandStore.TTL_MILLIS - 1).isPresent());

        // 到点即失效：过期默认结果是"不执行"，这是正确的默认
        PendingCommandStore expired = new PendingCommandStore();
        expired.propose(PLAYER, "stop", "", 1_000L);
        assertTrue(expired.take(PLAYER, 1_000L + PendingCommandStore.TTL_MILLIS).isEmpty());
    }

    @Test
    void newProposalReplacesTheOldOne() {
        PendingCommandStore store = new PendingCommandStore();
        store.propose(PLAYER, "list", "", 1_000L);
        store.propose(PLAYER, "time set day", "改白天", 2_000L);

        // 一人一条：模型连点不会攒出一串等着玩家误确认的指令
        assertEquals("time set day", store.take(PLAYER, 2_100L).orElseThrow().command());
        assertTrue(store.take(PLAYER, 2_200L).isEmpty());
    }

    @Test
    void peekDoesNotConsume() {
        PendingCommandStore store = new PendingCommandStore();
        store.propose(PLAYER, "list", "", 1_000L);

        assertTrue(store.peek(PLAYER, 1_100L).isPresent());
        assertTrue(store.peek(PLAYER, 1_200L).isPresent());
        assertTrue(store.take(PLAYER, 1_300L).isPresent());
    }

    @Test
    void clearDropsThePendingProposal() {
        PendingCommandStore store = new PendingCommandStore();
        store.propose(PLAYER, "list", "", 1_000L);

        store.clear(PLAYER);

        assertFalse(store.peek(PLAYER, 1_100L).isPresent());
        // 空玩家 id 不该炸（玩家在包处理里可能是 null）
        store.propose(null, "list", "", 1_000L);
        assertTrue(store.peek(null, 1_100L).isEmpty());
    }
}
