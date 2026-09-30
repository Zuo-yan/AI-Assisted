package org.gwfx.aiassisted.core.build;

/**
 * 建造前的覆盖检查（零 MC 依赖，可单测）：目标位置会不会动到不该动的东西。
 *
 * <p>只检查<b>"目标态 ≠ 现状"</b>的格子：目标位置本来就是同一种方块时，既不需要放置、
 * 也不该因为"它旁边有台机器"而拒绝 —— 那会把大量正常情况误判成冲突。
 *
 * <p>遇到未加载区块立刻停止检查并如实上报，而不是跳过那几格继续 ——
 * 跳过等于"没检查到的地方当作没问题"，而这正是会覆盖别人建筑的那类误判。
 */
public final class CoveragePlanner {

    /**
     * 一处冲突。
     *
     * @param targetBlockId  蓝图想放的方块
     * @param currentBlockId 现状方块（未加载区块时为 null）
     */
    public record Violation(int x, int y, int z, String targetBlockId, String currentBlockId) {
    }

    /**
     * 检查结果。
     *
     * @param checkedBlocks 实际需要改动、因而被检查过的格子数
     * @param conflicts     其中被判为普通"人工方块/不可覆盖"的格子数（检查到第一处冲突即返回，故只会是 0 或 1）
     * @param unloadedChunk 是否存在未加载区块（true 时 firstViolation 里带的是触发的那个坐标）
     */
    public record Result(int checkedBlocks, int conflicts, boolean unloadedChunk, Violation firstViolation) {

        public boolean ok() {
            return this.firstViolation == null && !this.unloadedChunk;
        }
    }

    private CoveragePlanner() {
    }

    public static Result check(BuildPlan plan, BuildPlacement placement, TerrainProbe probe) {
        return check(plan, placement, probe, false);
    }

    public static Result check(BuildPlan plan, BuildPlacement placement, TerrainProbe probe, boolean allowNonNatural) {
        if (plan == null || placement == null || probe == null) {
            return new Result(0, 0, false, null);
        }
        int checked = 0;

        for (BuildPlan.LocalBlock block : plan.blocks()) {
            BuildPlacement.Position position = placement.toWorld(block.x(), block.y(), block.z());
            if (!probe.isLoaded(position.x(), position.y(), position.z())) {
                // 一遇到未加载就停：继续检查要么会强制加载区块，要么会漏检
                return new Result(checked, 0, true,
                        new Violation(position.x(), position.y(), position.z(), block.blockId(), null));
            }
            String current = probe.currentBlockId(position.x(), position.y(), position.z());
            if (block.blockId().equals(current)) {
                // 已经是目标方块：不算改动，也就无需检查（建筑重复建造同一处应当放行）
                continue;
            }
            checked++;
            if (allowNonNatural) {
                // 放权模式：允许修改普通人造建筑和自然地形，但仍严格阻断对不可破坏/违规方块（基岩、末地传送门等）的破坏
                if (current != null && BlueprintValidator.FORBIDDEN_BLOCKS.contains(current)) {
                    // 发现第一处即返回：这也是 conflicts 恒为 0 或 1 的原因
                    return new Result(checked, 1, false,
                            new Violation(position.x(), position.y(), position.z(), block.blockId(), current));
                }
            } else if (!probe.isNaturalTerrain(position.x(), position.y(), position.z())) {
                // 只留第一处：报错只需要一个坐标，玩家据此去挖开那块地
                return new Result(checked, 1, false,
                        new Violation(position.x(), position.y(), position.z(), block.blockId(), current));
            }
        }
        return new Result(checked, 0, false, null);
    }
}