package org.gwfx.aiassisted.core.build;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 覆盖检查（T001-6）：能不能在目标位置建。
 *
 * <p>三条必须钉住的行为：只检查"目标态≠现状"的格子、报出第一个违规坐标、
 * 遇到未加载区块立刻停止（不是跳过）。
 */
class CoveragePlannerTest {

    /** 假探针：把世界压成两个集合，避免为了测纯逻辑去起一个 MC 环境。 */
    private static final class FakeProbe implements TerrainProbe {

        private final Set<String> artificial = new HashSet<>();
        private final Map<String, String> current = new ConcurrentHashMap<>();
        private boolean loaded = true;

        private FakeProbe artificialAt(int x, int y, int z, String blockId) {
            this.artificial.add(key(x, y, z));
            this.current.put(key(x, y, z), blockId);
            return this;
        }

        @Override
        public boolean isLoaded(int worldX, int worldY, int worldZ) {
            return this.loaded;
        }

        @Override
        public String currentBlockId(int worldX, int worldY, int worldZ) {
            return this.current.getOrDefault(key(worldX, worldY, worldZ), "minecraft:air");
        }

        @Override
        public boolean isNaturalTerrain(int worldX, int worldY, int worldZ) {
            return !this.artificial.contains(key(worldX, worldY, worldZ));
        }

        private static String key(int x, int y, int z) {
            return x + "," + y + "," + z;
        }
    }

    /** 1x1x2 的一根柱子：底层石头、上层木头。 */
    private static BuildPlan plan() {
        BlueprintParser.Result parsed = BlueprintParser.parse("柱",
                List.of("S=minecraft:stone", "W=minecraft:oak_planks", ".=minecraft:air"),
                List.of("S", "W"));
        assertTrue(parsed.ok(), parsed::error);
        return BuildPlan.from(parsed.blueprint());
    }

    @Test
    void passesOnEmptyTerrain() {
        BuildPlacement placement = BuildPlacement.inFrontOf(0, 64, 0, BuildPlacement.Facing.SOUTH, 2);

        CoveragePlanner.Result result = CoveragePlanner.check(plan(), placement, new FakeProbe());

        assertTrue(result.ok());
        assertEquals(2, result.checkedBlocks());
    }

    @Test
    void reportsTheFirstArtificialBlock() {
        BuildPlacement placement = BuildPlacement.inFrontOf(0, 64, 0, BuildPlacement.Facing.SOUTH, 2);
        // 上层那一格的现状是"别人的箱子"
        FakeProbe probe = new FakeProbe().artificialAt(0, 65, 2, "minecraft:chest");

        CoveragePlanner.Result result = CoveragePlanner.check(plan(), placement, probe);

        assertFalse(result.ok());
        assertNotNull(result.firstViolation());
        assertEquals("minecraft:chest", result.firstViolation().currentBlockId());
        assertEquals(0, result.firstViolation().x());
        assertEquals(65, result.firstViolation().y());
        assertEquals(2, result.firstViolation().z());
        assertEquals("minecraft:oak_planks", result.firstViolation().targetBlockId());
    }

    @Test
    void skipsCellsThatAlreadyHoldTheTargetBlock() {
        BuildPlacement placement = BuildPlacement.inFrontOf(0, 64, 0, BuildPlacement.Facing.SOUTH, 2);
        // 两格都已经是目标方块：既不放置、也就不该判它"人工"
        FakeProbe probe = new FakeProbe()
                .artificialAt(0, 64, 2, "minecraft:stone")
                .artificialAt(0, 65, 2, "minecraft:oak_planks");

        CoveragePlanner.Result result = CoveragePlanner.check(plan(), placement, probe);

        assertTrue(result.ok());
        assertEquals(0, result.checkedBlocks());
    }

    @Test
    void stopsWhenChunkIsNotLoaded() {
        BuildPlacement placement = BuildPlacement.inFrontOf(0, 64, 0, BuildPlacement.Facing.SOUTH, 2);
        FakeProbe probe = new FakeProbe();
        probe.loaded = false;

        CoveragePlanner.Result result = CoveragePlanner.check(plan(), placement, probe);

        assertFalse(result.ok());
        assertTrue(result.unloadedChunk());
        assertEquals(0, result.checkedBlocks(), "一遇到未加载就该停下，而不是跳过那几格继续");
    }

    @Test
    void nullInputsAreTreatedAsOk() {
        assertTrue(CoveragePlanner.check(null, null, null).ok());
    }
}

