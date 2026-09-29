package org.gwfx.aiassisted.build;

import org.gwfx.aiassisted.core.build.BuildPlacement;
import org.gwfx.aiassisted.core.build.BuildPlan;
import org.gwfx.aiassisted.core.build.BlueprintParser;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 待确认建造的生命周期（T001-6）。
 *
 * <p>与待确认指令同构的安全属性：<b>过期即不可建造</b>、<b>取到即消费</b>、<b>一人同时只有一条</b>。
 * TTL 比指令长（120 秒）：玩家得把那张分层字符画看完才敢确认。
 */
class PendingBuildStoreTest {

    private static final UUID PLAYER = UUID.randomUUID();

    private static BuildPlan plan() {
        BlueprintParser.Result parsed = BlueprintParser.parse("小屋",
                List.of("W=minecraft:oak_planks", ".=minecraft:air"),
                List.of("WW;W.", "WW;WW"));
        assertTrue(parsed.ok(), parsed::error);
        return BuildPlan.from(parsed.blueprint());
    }

    private static BuildPlacement placement() {
        return BuildPlacement.inFrontOf(0, 64, 0, BuildPlacement.Facing.SOUTH, 2);
    }

    @Test
    void takesThePendingBuildAndConsumesIt() {
        PendingBuildStore store = new PendingBuildStore();
        store.propose(PLAYER, plan(), placement(), 1_000L);

        PendingBuildStore.Pending taken = store.take(PLAYER, 1_500L).orElseThrow();

        assertEquals("小屋", taken.name());
        assertEquals(7, taken.plan().blockCount());
        assertTrue(store.take(PLAYER, 1_600L).isEmpty(), "一次确认只建造一次");
    }

    @Test
    void expiredBuildIsNeverBuilt() {
        PendingBuildStore store = new PendingBuildStore();
        store.propose(PLAYER, plan(), placement(), 1_000L);

        assertFalse(store.peek(PLAYER, 1_000L + PendingBuildStore.TTL_MILLIS).isPresent());
        assertTrue(PendingBuildStore.TTL_MILLIS >= 60_000L, "建造要留够阅读字符画的时间");
    }

    @Test
    void newProposalReplacesTheOldOne() {
        PendingBuildStore store = new PendingBuildStore();
        store.propose(PLAYER, plan(), placement(), 1_000L);
        store.propose(PLAYER, plan(), placement(), 2_000L);

        assertTrue(store.take(PLAYER, 2_100L).isPresent());
        assertTrue(store.take(PLAYER, 2_200L).isEmpty());
    }

    @Test
    void clearDropsIt() {
        PendingBuildStore store = new PendingBuildStore();
        store.propose(PLAYER, plan(), placement(), 1_000L);

        store.clear(PLAYER);

        assertFalse(store.peek(PLAYER, 1_100L).isPresent());
        // 空玩家 id 不该炸
        store.propose(null, plan(), placement(), 1_000L);
        assertTrue(store.peek(null, 1_100L).isEmpty());
    }

    @Test
    void unnamedBuildGetsAFallbackName() {
        BlueprintParser.Result parsed = BlueprintParser.parse("",
                List.of("W=minecraft:stone"), List.of("W"));
        PendingBuildStore store = new PendingBuildStore();
        store.propose(PLAYER, BuildPlan.from(parsed.blueprint()), placement(), 1_000L);

        assertEquals("未命名建筑", store.take(PLAYER, 1_100L).orElseThrow().name());
    }
}
