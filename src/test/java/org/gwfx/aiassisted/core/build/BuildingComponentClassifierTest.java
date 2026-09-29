package org.gwfx.aiassisted.core.build;

import org.gwfx.aiassisted.core.build.BuildingComponentClassifier.ComponentType;
import org.gwfx.aiassisted.core.build.BuildingComponentClassifier.Voxel;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuildingComponentClassifierTest {

    @Test
    void emptyListYieldsEmptyMap() {
        Map<ComponentType, List<Voxel>> res = BuildingComponentClassifier.classify(List.of());
        assertTrue(res.isEmpty());
    }

    @Test
    void recognizesSimpleCottageComponents() {
        List<Voxel> voxels = new ArrayList<>();

        // 1. 地板 y = 0: 5x5 的石方块
        for (int x = 0; x < 5; x++) {
            for (int z = 0; z < 5; z++) {
                voxels.add(new Voxel(x, 0, z, "minecraft:stone"));
            }
        }

        // 2. 四角原木立柱 y = 1..3
        int[][] corners = {{0, 0}, {0, 4}, {4, 0}, {4, 4}};
        for (int[] c : corners) {
            for (int y = 1; y <= 3; y++) {
                voxels.add(new Voxel(c[0], y, c[1], "minecraft:oak_log"));
            }
        }

        // 3. 窗户 (2, 2, 0)
        voxels.add(new Voxel(2, 2, 0, "minecraft:glass_pane"));

        // 4. 普通墙体 (1, 1..3, 0) 等
        voxels.add(new Voxel(1, 1, 0, "minecraft:oak_planks"));
        voxels.add(new Voxel(1, 2, 0, "minecraft:oak_planks"));
        voxels.add(new Voxel(1, 3, 0, "minecraft:oak_planks"));

        // 5. 装饰物：火把 (2, 3, 0)
        voxels.add(new Voxel(2, 3, 0, "minecraft:wall_torch"));

        // 6. 屋顶 y = 4..5: 橡木楼梯
        for (int x = 0; x < 5; x++) {
            voxels.add(new Voxel(x, 4, 1, "minecraft:oak_stairs"));
            voxels.add(new Voxel(x, 4, 3, "minecraft:oak_stairs"));
            voxels.add(new Voxel(x, 5, 2, "minecraft:oak_stairs"));
        }

        Map<ComponentType, List<Voxel>> classified = BuildingComponentClassifier.classify(voxels);

        // 校验各个分类存在且非空
        assertFalse(classified.get(ComponentType.FLOOR).isEmpty(), "地面应当被识别");
        assertFalse(classified.get(ComponentType.PILLAR).isEmpty(), "立柱应当被识别");
        assertFalse(classified.get(ComponentType.WINDOW).isEmpty(), "窗户应当被识别");
        assertFalse(classified.get(ComponentType.ROOF).isEmpty(), "屋顶应当被识别");
        assertFalse(classified.get(ComponentType.DECORATION).isEmpty(), "火把等装饰物应当被识别");
        assertFalse(classified.get(ComponentType.WALL).isEmpty(), "墙体应当被识别");

        // 细节校验
        assertTrue(classified.get(ComponentType.WINDOW).stream()
                .anyMatch(v -> v.blockId().equals("minecraft:glass_pane")));
        assertTrue(classified.get(ComponentType.DECORATION).stream()
                .anyMatch(v -> v.blockId().equals("minecraft:wall_torch")));
        assertTrue(classified.get(ComponentType.ROOF).stream()
                .allMatch(v -> v.blockId().contains("stairs")));
    }

    @Test
    void parseComponentTypeHandlesSynonyms() {
        assertEquals(ComponentType.ROOF, ComponentType.parse("roof"));
        assertEquals(ComponentType.ROOF, ComponentType.parse("屋顶"));
        assertEquals(ComponentType.PILLAR, ComponentType.parse("立柱"));
        assertEquals(ComponentType.PILLAR, ComponentType.parse("pillar"));
        assertEquals(ComponentType.WINDOW, ComponentType.parse("window"));
        assertEquals(ComponentType.WINDOW, ComponentType.parse("窗户"));
        assertEquals(ComponentType.WALL, ComponentType.parse("wall"));
        assertEquals(ComponentType.WALL, ComponentType.parse("墙体"));
        assertEquals(ComponentType.FLOOR, ComponentType.parse("floor"));
        assertEquals(ComponentType.FLOOR, ComponentType.parse("地面"));
        assertEquals(ComponentType.DECORATION, ComponentType.parse("decoration"));
        assertEquals(ComponentType.OTHER, ComponentType.parse("unknown_xyz"));
    }
}
