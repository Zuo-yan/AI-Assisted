package org.gwfx.aiassisted.tool;

import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.gwfx.aiassisted.core.agent.ToolArgs;
import org.gwfx.aiassisted.core.agent.ToolOutcome;
import org.gwfx.aiassisted.core.config.AiConfig;
import org.gwfx.aiassisted.core.llm.ToolSpec;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 工具 {@code search_blocks}：在玩家周围搜索指定方块。
 *
 * <p>这个工具补的正是「上下文采集器」答不了的缺口 —— 上下文只扫<b>容器</b>，
 * 不扫任意方块，所以「附近有没有钻石矿 / 工作台 / 岩浆」这类问题原本无从回答。
 *
 * <p><b>成本控制（本类存在的意义）</b>：
 * <ol>
 *   <li>只看<b>已加载区块</b>（{@code getChunkNow}），绝不为了查询去强加载区块；</li>
 *   <li>逐段先经 {@code LevelChunkSection#maybeHas} 做 Palette 级早退
 *       （26.3 源码注释明说这比遍历整段/整块都高效）；</li>
 *   <li>位置循环按半径做框裁剪，不浪费预算在必然越界的位置上；</li>
 *   <li>用「方块位置扫描次数」作为工作量预算（配置 {@code ai.toolMaxScanBlocks}），
 *       超预算即停并标注「可能不完整」—— 工具在主线程同步执行、无法硬中断，
 *       所以只能把工作量本身限死，而不是假装有个执行超时。</li>
 * </ol>
 *
 * <p><b>必须在服务端主线程调用</b>（要读区块）。
 */
public final class SearchBlocksTool {

    public static final String NAME = "search_blocks";

    /** 默认搜索半径（格）。 */
    static final int DEFAULT_RADIUS = 8;
    /** 半径上下限：小半径保证延迟可控，上限避免一次查询把主线程卡住。 */
    static final int MIN_RADIUS = 1;
    static final int MAX_RADIUS = 16;

    private SearchBlocksTool() {
    }

    public static ToolSpec spec() {
        return ToolSpec.builder(NAME, "在玩家周围已加载的区块里搜索指定方块，返回最近的若干坐标与命中数量。"
                        + "适用于「附近有没有钻石矿/工作台/岩浆」这类问题。只搜索已加载区域，未加载处查不到。")
                .stringParam("block_id", "方块注册表 id，例如 minecraft:diamond_ore", true)
                .integerParam("radius", "搜索半径（格）", MIN_RADIUS, MAX_RADIUS, false)
                .build();
    }

    static ToolOutcome invoke(ServerPlayer player, AiConfig config, JsonObject args) {
        Optional<String> rawId = ToolArgs.string(args, "block_id");
        if (rawId.isEmpty()) {
            return ToolOutcome.error("缺少参数 block_id（方块注册表 id，例如 minecraft:diamond_ore）");
        }
        Identifier id = Identifier.tryParse(rawId.get());
        if (id == null) {
            return ToolOutcome.error("block_id 不是合法的注册表 id：" + rawId.get());
        }
        // 注册表里查不到就直接拒绝：编出来的 id 走到扫描阶段只会白扫一遍
        Optional<Block> resolved = BuiltInRegistries.BLOCK.getOptional(id);
        if (resolved.isEmpty()) {
            return ToolOutcome.error("注册表里不存在这个方块：" + id);
        }
        Block target = resolved.get();

        int radius = ToolArgs.integer(args, "radius", DEFAULT_RADIUS, MIN_RADIUS, MAX_RADIUS);
        ServerLevel level = player.level();
        BlockPos origin = player.blockPosition();
        int radiusSqr = radius * radius;

        ServerChunkCache chunkSource = level.getChunkSource();
        int minChunkX = SectionPos.blockToSectionCoord(origin.getX() - radius);
        int maxChunkX = SectionPos.blockToSectionCoord(origin.getX() + radius);
        int minChunkZ = SectionPos.blockToSectionCoord(origin.getZ() - radius);
        int maxChunkZ = SectionPos.blockToSectionCoord(origin.getZ() + radius);

        List<Hit> hits = new ArrayList<>();
        int budget = config.toolMaxScanBlocks();
        int scanned = 0;
        boolean truncated = false;

        outer:
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                // getChunkNow 只取已加载区块；未加载返回 null。绝不为了查询强加载区块。
                LevelChunk chunk = chunkSource.getChunkNow(chunkX, chunkZ);
                if (chunk == null) {
                    continue;
                }
                int baseX = chunk.getPos().getMinBlockX();
                int baseZ = chunk.getPos().getMinBlockZ();
                int xStart = Math.max(baseX, origin.getX() - radius);
                int xEnd = Math.min(baseX + 15, origin.getX() + radius);
                int zStart = Math.max(baseZ, origin.getZ() - radius);
                int zEnd = Math.min(baseZ + 15, origin.getZ() + radius);

                LevelChunkSection[] sections = chunk.getSections();
                for (int index = 0; index < sections.length; index++) {
                    LevelChunkSection section = sections[index];
                    if (section == null || section.hasOnlyAir()) {
                        continue;
                    }
                    // 整段没有目标方块就一次比较跳过（Palette 早退），这是本工具不卡主线程的关键
                    if (!section.maybeHas(state -> state.getBlock() == target)) {
                        continue;
                    }
                    int minY = SectionPos.sectionToBlockCoord(chunk.getSectionYFromSectionIndex(index));
                    int yStart = Math.max(minY, origin.getY() - radius);
                    int yEnd = Math.min(minY + 15, origin.getY() + radius);

                    for (int worldY = yStart; worldY <= yEnd; worldY++) {
                        int localY = worldY - minY;
                        for (int worldX = xStart; worldX <= xEnd; worldX++) {
                            int localX = worldX - baseX;
                            for (int worldZ = zStart; worldZ <= zEnd; worldZ++) {
                                if (++scanned > budget) {
                                    truncated = true;
                                    break outer;
                                }
                                BlockState state = section.getBlockState(localX, localY, worldZ - baseZ);
                                if (state.getBlock() != target) {
                                    continue;
                                }
                                int distanceSqr = distanceSqr(origin, worldX, worldY, worldZ);
                                if (distanceSqr > radiusSqr) {
                                    continue;
                                }
                                hits.add(new Hit(worldX, worldY, worldZ, distanceSqr));
                            }
                        }
                    }
                }
            }
        }

        hits.sort(Comparator.comparingInt(Hit::distanceSqr));
        return ToolOutcome.ok(render(id.toString(), radius, hits, truncated, config.toolMaxResults()));
    }

    private static String render(String blockId, int radius, List<Hit> hits, boolean truncated, int maxResults) {
        StringBuilder out = new StringBuilder(256);
        out.append("搜索方块 ").append(blockId).append("（半径 ").append(radius).append(" 格，仅已加载区块）：\n");

        if (hits.isEmpty()) {
            out.append("没有找到。");
            if (truncated) {
                out.append("注意：扫描已达预算上限，未加载或未扫到的区域不能断定没有。");
            }
            return out.toString();
        }

        out.append("命中 ").append(hits.size()).append(" 个");
        if (truncated) {
            out.append("（扫描已达预算上限，实际可能更多）");
        }
        out.append("。最近的 ").append(Math.min(hits.size(), Math.max(1, maxResults))).append(" 个坐标：\n");

        int limit = Math.min(hits.size(), Math.max(1, maxResults));
        for (int i = 0; i < limit; i++) {
            Hit hit = hits.get(i);
            out.append("- ").append(hit.x()).append(' ').append(hit.y()).append(' ').append(hit.z())
                    .append("（距离 ").append((int) Math.round(Math.sqrt(hit.distanceSqr()))).append(" 格）\n");
        }
        return out.toString();
    }

    private static int distanceSqr(BlockPos origin, int x, int y, int z) {
        int dx = x - origin.getX();
        int dy = y - origin.getY();
        int dz = z - origin.getZ();
        return dx * dx + dy * dy + dz * dz;
    }

    private record Hit(int x, int y, int z, int distanceSqr) {
    }
}
