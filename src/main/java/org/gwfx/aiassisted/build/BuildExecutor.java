package org.gwfx.aiassisted.build;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.gwfx.aiassisted.core.build.BuildPlacement;
import org.gwfx.aiassisted.core.build.UndoLog;
import org.gwfx.aiassisted.platform.RegistryLookup;

/**
 * 真正动方块的那一层（T001-6）——<b>全仓库唯一新增的写世界入口</b>。
 *
 * <p>把"放一格 / 恢复一格"独立出来，是为了让"谁在写世界"这件事可枚举、可审计：
 * 除了本类，AI 模块没有任何别的地方调 {@code setBlock}。
 *
 * <p><b>更新位 = CLIENTS | NEIGHBORS | SUPPRESS_DROPS</b>：
 * <ul>
 *   <li>{@code UPDATE_CLIENTS}：不发给客户端，玩家看不见任何变化；</li>
 *   <li>{@code UPDATE_NEIGHBORS}：门/楼梯/栅栏/红石要邻居更新才会连成一体。
 *       附近 {@code GrassPortalShape} 用的是 {@code 2|16}（不触发更新），但那是为了"传送门别被自己压塌"，
 *       用在建筑上会让家具全都"不连接"；</li>
 *   <li>{@code UPDATE_SUPPRESS_DROPS}：覆盖掉草/石头这类方块时不掉落物品（否则建完地上全是掉落物）。</li>
 * </ul>
 *
 * <p>必须在服务端主线程调用（方块更新会触达邻居与世界状态）。
 */
final class BuildExecutor {

    private static final int FLAGS =
            Block.UPDATE_CLIENTS | Block.UPDATE_NEIGHBORS | Block.UPDATE_SUPPRESS_DROPS;

    private BuildExecutor() {
    }

    /** 目标方块状态；id 在注册表里不存在时返回 null（调用方据此中止整场）。 */
    static BlockState stateFor(String blockId) {
        return RegistryLookup.block(ResourceLocation.tryParse(blockId))
                .map(Block::defaultBlockState)
                .orElse(null);
    }

    enum PlaceOutcome {
        /** 放下去了，且旧状态已记进撤销日志。 */
        PLACED,
        /** 本来就是这种方块，什么都没做。 */
        UNCHANGED,
        /** 所在区块未加载 —— 不硬闯，交给调用方中止。 */
        UNLOADED,
        /** 客户端/世界拒绝了这次更新。 */
        UNAVAILABLE
    }

    /**
     * 放一个方块。
     *
     * <p>只有 {@code setBlock} 真的返回 true 才记撤销日志 —— 没改动却记进去，
     * 会让"撤销恢复了几格"这个数字变成谎话。
     */
    static PlaceOutcome placeOne(ServerLevel level, BuildPlacement.Position position, BlockState target,
                                 String targetBlockId, UndoLog<BlockState> log) {
        LevelChunk chunk = chunkAt(level, position.x(), position.z());
        if (chunk == null) {
            return PlaceOutcome.UNLOADED;
        }
        BlockPos pos = new BlockPos(position.x(), position.y(), position.z());
        BlockState current = chunk.getBlockState(pos);
        if (targetBlockId.equals(RegistryLookup.blockId(current.getBlock()))) {
            return PlaceOutcome.UNCHANGED;
        }
        // 走 level.setBlock 而不是 chunk.setBlockState：前者才会发出完整的更新（邻居+客户端）
        if (!level.setBlock(pos, target, FLAGS)) {
            return PlaceOutcome.UNAVAILABLE;
        }
        log.record(position.x(), position.y(), position.z(), current, targetBlockId);
        return PlaceOutcome.PLACED;
    }

    enum RestoreOutcome {
        RESTORED,
        /** 这一格现在不是我们放下的方块了（别人改过）—— 条件撤销，跳过。 */
        SKIPPED_CHANGED,
        /** 区块未加载，不硬闯。 */
        SKIPPED_UNLOADED
    }

    /** 恢复一格（撤销）。 */
    static RestoreOutcome restoreOne(ServerLevel level, UndoLog.Entry<BlockState> entry) {
        LevelChunk chunk = chunkAt(level, entry.x(), entry.z());
        if (chunk == null) {
            return RestoreOutcome.SKIPPED_UNLOADED;
        }
        BlockPos pos = new BlockPos(entry.x(), entry.y(), entry.z());
        String currentId = RegistryLookup.blockId(chunk.getBlockState(pos).getBlock());
        if (!currentId.equals(entry.placedBlockId())) {
            // 条件撤销：只回退"仍然是我们放下的那个方块"的格子，绝不覆盖后来者的改动
            return RestoreOutcome.SKIPPED_CHANGED;
        }
        level.setBlock(pos, entry.previousState(), FLAGS);
        return RestoreOutcome.RESTORED;
    }

    /** 只取已加载区块；{@code getBlockState} 会同步加载区块，绝不能在校验/放置路径上用它。 */
    private static LevelChunk chunkAt(ServerLevel level, int worldX, int worldZ) {
        return level.getChunkSource().getChunkNow(
                SectionPos.blockToSectionCoord(worldX), SectionPos.blockToSectionCoord(worldZ));
    }
}
