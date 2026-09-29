package org.gwfx.aiassisted.core.build;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 落点与朝向（T001-6）。
 *
 * <p>这里最重要的一条不变量是：<b>玩家的脚下格子永远不在建筑轮廓内</b>。
 * 它一旦破了，玩家会在 {@code /ai confirm} 之后被埋在墙里 —— 而且是建完才发现。
 * 所以四个朝向 × 多组尺寸逐个断言。
 */
class BuildPlacementTest {

    private static final int PLAYER_X = 100;
    private static final int PLAYER_Y = 64;
    private static final int PLAYER_Z = -200;

    @Test
    void localFrontPointsToTheFacingDirection() {
        assertFront(BuildPlacement.Facing.SOUTH, 0, 0, 1);
        assertFront(BuildPlacement.Facing.NORTH, 0, 0, -1);
        assertFront(BuildPlacement.Facing.WEST, -1, 0, 0);
        assertFront(BuildPlacement.Facing.EAST, 1, 0, 0);
    }

    @Test
    void originIsOffsetAlongFacing() {
        BuildPlacement placement = BuildPlacement.inFrontOf(PLAYER_X, PLAYER_Y, PLAYER_Z,
                BuildPlacement.Facing.SOUTH, 2);

        assertEquals(PLAYER_X, placement.origin().x());
        assertEquals(PLAYER_Y, placement.origin().y());
        assertEquals(PLAYER_Z + 2, placement.origin().z());
    }

    @Test
    void playerIsNeverInsideTheFootprint() {
        int[][] sizes = {{9, 5, 7}, {1, 1, 1}, {16, 8, 16}, {3, 4, 5}, {1, 8, 1}};
        for (BuildPlacement.Facing facing : BuildPlacement.Facing.values()) {
            for (int[] size : sizes) {
                BuildPlacement placement = BuildPlacement.inFrontOf(PLAYER_X, PLAYER_Y, PLAYER_Z, facing, 2);
                assertFalse(placement.contains(PLAYER_X, PLAYER_Y, PLAYER_Z, size[0], size[1], size[2]),
                        () -> "玩家格落在了轮廓内：" + facing + " size=" + size[0] + "x" + size[1] + "x" + size[2]);

                // 反过来确认"轮廓里确实有格子"（否则上面那条断言可能因为 contains 恒 false 而假通过）
                BuildPlacement.Position firstBlock = placement.toWorld(0, 0, 0);
                assertTrue(placement.contains(firstBlock.x(), firstBlock.y(), firstBlock.z(),
                                size[0], size[1], size[2]),
                        () -> "本地原点应当在轮廓内：" + facing);
            }
        }
    }

    @Test
    void rotationIsIsometric() {
        // 四个朝向都必须是一致的手性旋转：局部 (1,0,0) 与 (0,0,1) 在世界里仍互相垂直、长度不变
        for (BuildPlacement.Facing facing : BuildPlacement.Facing.values()) {
            BuildPlacement placement = BuildPlacement.inFrontOf(0, 0, 0, facing, 0);
            BuildPlacement.Position xAxis = placement.toWorld(1, 0, 0);
            BuildPlacement.Position zAxis = placement.toWorld(0, 0, 1);

            assertEquals(1, Math.abs(xAxis.x()) + Math.abs(xAxis.z()), () -> "x 轴映射被缩放：" + facing);
            assertEquals(1, Math.abs(zAxis.x()) + Math.abs(zAxis.z()), () -> "z 轴映射被缩放：" + facing);
            assertEquals(0, xAxis.x() * zAxis.x() + xAxis.z() * zAxis.z(), () -> "两轴不再垂直：" + facing);
        }
    }

    @Test
    void containsRespectsHeight() {
        BuildPlacement placement = BuildPlacement.inFrontOf(0, 64, 0, BuildPlacement.Facing.SOUTH, 2);

        // depth=1、width=1 的单格：局部 y 必须落在 [0, height)
        assertTrue(placement.contains(placement.toWorld(0, 0, 0).x(), 64, placement.origin().z(), 1, 3, 1));
        assertFalse(placement.contains(placement.toWorld(0, 0, 0).x(), 67, placement.origin().z(), 1, 3, 1));
    }

    private static void assertFront(BuildPlacement.Facing facing, int expectedX, int expectedY, int expectedZ) {
        BuildPlacement placement = BuildPlacement.inFrontOf(0, 0, 0, facing, 0);
        BuildPlacement.Position front = placement.toWorld(0, 0, 1);

        assertEquals(expectedX, front.x(), () -> "朝向 " + facing + " 的正面映射不对");
        assertEquals(expectedY, front.y());
        assertEquals(expectedZ, front.z(), () -> "朝向 " + facing + " 的正面映射不对");
    }
}
