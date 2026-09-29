package org.gwfx.aiassisted.build.analysis;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import org.gwfx.aiassisted.platform.RegistryLookup;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;

/**
 * 视线拾取与建筑连通块聚类探测器。
 *
 * <p>以玩家视线所指的方块为种子点，使用三维 6 向漫水填充（Flood-Fill）聚类出整座建筑的方块集合。
 * 带有自然地形阻断策略，防止建筑边缘的大面积草地、泥土和地底石头被误判为建筑一部分。
 */
public final class BuildingClusterDetector {

    public static final int DEFAULT_MAX_DISTANCE = 48;
    public static final int DEFAULT_MAX_CLUSTER_BLOCKS = 4096;

    /** 聚类结果结构体。 */
    public record BuildingStructure(
            BlockPos seedPos,
            BlockPos minPos,
            BlockPos maxPos,
            List<BlockPos> positions,
            Map<BlockPos, String> blockIds) {

        public int blockCount() {
            return this.positions.size();
        }

        public int width() {
            return this.maxPos.getX() - this.minPos.getX() + 1;
        }

        public int height() {
            return this.maxPos.getY() - this.minPos.getY() + 1;
        }

        public int depth() {
            return this.maxPos.getZ() - this.minPos.getZ() + 1;
        }
    }

    private BuildingClusterDetector() {
    }

    /** 沿玩家视线射线探测面前方块。 */
    public static Optional<BlockPos> targetBlockPos(ServerPlayer player, double maxDistance) {
        if (player == null) {
            return Optional.empty();
        }
        HitResult hit = player.pick(maxDistance, 0.0F, false);
        if (hit.getType() == HitResult.Type.BLOCK && hit instanceof BlockHitResult blockHit) {
            BlockPos pos = blockHit.getBlockPos();
            BlockState state = player.level().getBlockState(pos);
            if (!state.isAir()) {
                return Optional.of(pos);
            }
        }
        return Optional.empty();
    }

    /** 从玩家视线所指方块开始聚类建筑连通块。 */
    public static Optional<BuildingStructure> detectTargetedBuilding(ServerPlayer player, int maxDistance, int maxBlocks) {
        Optional<BlockPos> seedOpt = targetBlockPos(player, maxDistance);
        if (seedOpt.isEmpty()) {
            return Optional.empty();
        }
        ServerLevel level = (ServerLevel) player.level();
        return detectCluster(level, seedOpt.get(), maxBlocks);
    }

    /** 从指定种子方块开始三维 6 向漫水填充。 */
    public static Optional<BuildingStructure> detectCluster(ServerLevel level, BlockPos seed, int maxBlocks) {
        BlockState seedState = level.getBlockState(seed);
        if (seedState.isAir()) {
            return Optional.empty();
        }

        String seedId = RegistryLookup.blockId(seedState.getBlock());
        boolean seedIsNatural = isNaturalTerrain(seedId);

        Queue<BlockPos> queue = new ArrayDeque<>();
        Set<BlockPos> visited = new HashSet<>();
        List<BlockPos> cluster = new ArrayList<>();
        Map<BlockPos, String> idMap = new HashMap<>();

        queue.add(seed);
        visited.add(seed);

        int minX = seed.getX(), maxX = seed.getX();
        int minY = seed.getY(), maxY = seed.getY();
        int minZ = seed.getZ(), maxZ = seed.getZ();

        while (!queue.isEmpty() && cluster.size() < maxBlocks) {
            BlockPos current = queue.poll();
            BlockState state = level.getBlockState(current);
            if (state.isAir()) {
                continue;
            }

            String currentId = RegistryLookup.blockId(state.getBlock());
            // 如果种子不是自然地形，而当前碰到连续自然地形，则阻断不蔓延
            if (!seedIsNatural && isNaturalTerrain(currentId) && !current.equals(seed)) {
                continue;
            }

            cluster.add(current);
            idMap.put(current, currentId);

            if (current.getX() < minX) minX = current.getX();
            if (current.getX() > maxX) maxX = current.getX();
            if (current.getY() < minY) minY = current.getY();
            if (current.getY() > maxY) maxY = current.getY();
            if (current.getZ() < minZ) minZ = current.getZ();
            if (current.getZ() > maxZ) maxZ = current.getZ();

            for (Direction dir : Direction.values()) {
                BlockPos neighbor = current.relative(dir);
                if (visited.add(neighbor)) {
                    // 仅当区块已加载时探索
                    if (level.hasChunkAt(neighbor)) {
                        BlockState nState = level.getBlockState(neighbor);
                        if (!nState.isAir()) {
                            queue.add(neighbor);
                        }
                    }
                }
            }
        }

        if (cluster.isEmpty()) {
            return Optional.empty();
        }

        BuildingStructure structure = new BuildingStructure(
                seed,
                new BlockPos(minX, minY, minZ),
                new BlockPos(maxX, maxY, maxZ),
                Collections.unmodifiableList(cluster),
                Collections.unmodifiableMap(idMap)
        );
        return Optional.of(structure);
    }

    /** 常见的自然大地地形方块（用于漫水阻断）。 */
    private static boolean isNaturalTerrain(String blockId) {
        if (blockId == null) return false;
        String id = blockId.toLowerCase();
        return id.equals("minecraft:grass_block")
                || id.equals("minecraft:dirt")
                || id.equals("minecraft:coarse_dirt")
                || id.equals("minecraft:podzol")
                || id.equals("minecraft:stone")
                || id.equals("minecraft:deepslate")
                || id.equals("minecraft:sand")
                || id.equals("minecraft:gravel")
                || id.equals("minecraft:bedrock")
                || id.equals("minecraft:water")
                || id.equals("minecraft:lava");
    }
}
