package org.gwfx.aiassisted.core.build;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 撤销记录（T001-6）。
 *
 * <p>用 String 当"旧方块状态"来测：{@link UndoLog} 刻意做成与 MC 无关的数据结构，
 * 这样"只记真正改动的格 / 反向恢复 / 每玩家只留一条 / 过期即丢"这些语义都能在纯 JVM 里钉住。
 */
class UndoStoreTest {

    private static final UUID PLAYER = UUID.randomUUID();

    @Test
    void recordsOnlyWhatWasTouchedAndCanBeReversed() {
        UndoLog<String> log = new UndoLog<>();
        log.record(1, 64, 2, "minecraft:air", "minecraft:oak_planks");
        log.record(1, 65, 2, "minecraft:dirt", "minecraft:oak_log");

        assertEquals(2, log.size());
        List<UndoLog.Entry<String>> reversed = log.reversed();
        assertEquals("minecraft:oak_log", reversed.get(0).placedBlockId(), "撤销要按放置的相反顺序");
        assertEquals("minecraft:dirt", reversed.get(0).previousState());
        assertEquals("minecraft:oak_planks", reversed.get(1).placedBlockId());
    }

    @Test
    void keepsPreviousStateForConditionalUndo() {
        UndoLog<String> log = new UndoLog<>();
        log.record(0, 64, 0, "minecraft:grass_block", "minecraft:stone");

        // 撤销前用它判断"这一格现在还是我们放的那个方块吗"
        assertEquals("minecraft:stone", log.entries().get(0).placedBlockId());
        assertEquals("minecraft:grass_block", log.entries().get(0).previousState());
    }

    @Test
    void storesAndTakesOncePerPlayer() {
        UndoStore<String> store = new UndoStore<>();
        store.put(PLAYER, logOfOneBlock(), "小屋", 1_000L);

        assertTrue(store.take(PLAYER, 1_100L).isPresent());
        assertTrue(store.take(PLAYER, 1_200L).isEmpty(), "一次撤销只做一次");
    }

    @Test
    void onlyTheLatestBuildIsKept() {
        UndoStore<String> store = new UndoStore<>();
        store.put(PLAYER, logOfOneBlock(), "第一座", 1_000L);
        store.put(PLAYER, logOfOneBlock(), "第二座", 2_000L);

        assertEquals(1, store.size());
        assertEquals("第二座", store.peek(PLAYER, 2_100L).orElseThrow().name());
    }

    @Test
    void expiredRecordsAreDroppedAndSweepable() {
        UndoStore<String> store = new UndoStore<>();
        store.put(PLAYER, logOfOneBlock(), "小屋", 1_000L);

        // 惰性过期：到点取不到
        assertTrue(store.take(PLAYER, 1_000L + UndoStore.TTL_MILLIS).isEmpty());

        // 显式清理：给"建完就再也没回来"的玩家兜底，否则那块内存永远不会被回收
        store.put(PLAYER, logOfOneBlock(), "小屋", 1_000L);
        assertEquals(1, store.size());
        assertEquals(1, store.sweep(1_000L + UndoStore.TTL_MILLIS));
        assertEquals(0, store.size());
    }

    @Test
    void emptyLogIsNotStored() {
        UndoStore<String> store = new UndoStore<>();
        store.put(PLAYER, new UndoLog<>(), "空", 1_000L);

        assertFalse(store.peek(PLAYER, 1_100L).isPresent(), "什么都没改就不该留下撤销记录");
    }

    private static UndoLog<String> logOfOneBlock() {
        UndoLog<String> log = new UndoLog<>();
        log.record(0, 64, 0, "minecraft:air", "minecraft:stone");
        return log;
    }
}
