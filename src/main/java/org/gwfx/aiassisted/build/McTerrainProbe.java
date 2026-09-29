package org.gwfx.aiassisted.build;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.gwfx.aiassisted.core.build.TerrainProbe;
import org.gwfx.aiassisted.platform.RegistryLookup;

import java.util.List;
import java.util.Set;

/**
 * 「这里现在是不是自然地形」的服务端判定（T001-6）。
 *
 * <p><b>白名单式，判不准就当人工方块</b>。方向不能反：黑名单对模组/数据包新增的方块必然漏，
 * 漏一次的后果是"覆盖掉别人的机器"；白名单漏判的后果只是"这块地建不了，自己去挖一下"。
 * 代价不对称，所以一律往保守方向做。
 *
 * <p><b>只读已加载区块</b>：{@code ServerLevel#getBlockState} 会<b>同步加载区块</b>——
 * 为了一张图纸把远处区块拉起来，是"校验把服务器卡死"的经典写法。所以先 {@code getChunkNow}
 * （与 {@code McContextCollector} 和 {@code SearchBlocksTool} 的立场一致），拿不到就当未加载。
 */
public final class McTerrainProbe implements TerrainProbe {

    /** 这些 tag 覆盖了绝大部分"地表与地下自然方块"。 */
    private static final List<net.minecraft.tags.TagKey<Block>> NATURAL_TAGS = List.of(
            BlockTags.REPLACEABLE,
            BlockTags.REPLACEABLE_BY_TREES,
            BlockTags.DIRT,
            BlockTags.SAND,
            BlockTags.BASE_STONE_OVERWORLD,
            BlockTags.BASE_STONE_NETHER,
            BlockTags.DEEPSLATE_ORE_REPLACEABLES,
            BlockTags.SCULK_REPLACEABLE_WORLD_GEN,
            BlockTags.SNOW,
            BlockTags.ICE,
            BlockTags.LEAVES,
            BlockTags.LOGS,
            BlockTags.FLOWERS,
            BlockTags.CROPS,
            BlockTags.MOSS_BLOCKS,
            BlockTags.TERRACOTTA,
            BlockTags.ORES,
            BlockTags.CORAL_BLOCKS);

    /** tag 盖不到、但确实属于自然地表的东西（沙砾/黏土/水/草…）。 */
    private static final Set<Block> EXTRA_NATURAL = Set.of(
            Blocks.GRAVEL,
            Blocks.CLAY,
            Blocks.WATER,
            Blocks.LAVA,
            Blocks.SHORT_GRASS,
            Blocks.TALL_GRASS,
            Blocks.FERN,
            Blocks.LARGE_FERN,
            Blocks.DEAD_BUSH,
            Blocks.VINE,
            Blocks.SNOW,
            Blocks.SNOW_BLOCK,
            Blocks.POWDER_SNOW,
            Blocks.MOSS_BLOCK,
            Blocks.COARSE_DIRT,
            Blocks.PODZOL,
            Blocks.MYCELIUM,
            Blocks.RED_SAND,
            Blocks.SANDSTONE,
            Blocks.RED_SANDSTONE);

    private final ServerLevel level;

    public McTerrainProbe(ServerLevel level) {
        this.level = level;
    }

    @Override
    public boolean isLoaded(int worldX, int worldY, int worldZ) {
        return chunkAt(worldX, worldZ) != null;
    }

    @Override
    public String currentBlockId(int worldX, int worldY, int worldZ) {
        return RegistryLookup.blockId(stateAt(worldX, worldY, worldZ).getBlock());
    }

    @Override
    public boolean isNaturalTerrain(int worldX, int worldY, int worldZ) {
        BlockState state = stateAt(worldX, worldY, worldZ);
        // ① 有方块实体的一票否决：箱子、机器、告示牌、床… 它们不是"地形"，而且玩家最在意的就是这些
        if (state.hasBlockEntity()) {
            return false;
        }
        // ② 空气与流体：空地当然可以建
        if (state.isAir() || state.getBlock() == Blocks.WATER || state.getBlock() == Blocks.LAVA) {
            return true;
        }
        // ③ tag 白名单
        for (net.minecraft.tags.TagKey<Block> tag : NATURAL_TAGS) {
            if (state.is(tag)) {
                return true;
            }
        }
        // ④ 显式补充
        return EXTRA_NATURAL.contains(state.getBlock());
    }

    /** 只读已加载区块的方块状态；未加载时返回空气（调用方都应先问过 {@link #isLoaded}）。 */
    private BlockState stateAt(int worldX, int worldY, int worldZ) {
        LevelChunk chunk = chunkAt(worldX, worldZ);
        if (chunk == null) {
            return Blocks.AIR.defaultBlockState();
        }
        return chunk.getBlockState(new BlockPos(worldX, worldY, worldZ));
    }

    private LevelChunk chunkAt(int worldX, int worldZ) {
        return this.level.getChunkSource().getChunkNow(
                SectionPos.blockToSectionCoord(worldX), SectionPos.blockToSectionCoord(worldZ));
    }
}
