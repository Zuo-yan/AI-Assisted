package org.gwfx.aiassisted.tool;

import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import org.gwfx.aiassisted.core.agent.ToolArgs;
import org.gwfx.aiassisted.core.agent.ToolOutcome;
import org.gwfx.aiassisted.core.config.AiConfig;
import org.gwfx.aiassisted.core.llm.ToolSpec;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 工具 {@code search_containers}：按类型或内容过滤附近的容器。
 *
 * <p>补的缺口：上下文里的「附近容器」段只给「方块 id + 坐标 + 是否疑似战利品箱」，
 * 既不能按类型筛，更答不了「哪个箱子有钻石」。
 *
 * <p><b>按内容过滤受配置开关约束</b>（{@code ai.tool.containersReadContents}，默认开）：
 * 读箱内物品会把内容发给你配置的第三方 API，所以保留一个显式关闭的开关。
 * 关闭时只支持按方块类型过滤，而不是偷偷降级成"当它没有"。
 *
 * <p>无论开或关，结果都只给「坐标 + 命中数量」，<b>不逐槽列出整箱清单</b> ——
 * 够回答问题，又不必把一整箱物品抄进上下文预算。
 *
 * <p><b>必须在服务端主线程调用</b>；沿用「只看已加载区块（{@code getChunkNow}），绝不强加载」的策略。
 */
public final class SearchContainersTool {

    public static final String NAME = "search_containers";

    static final int DEFAULT_RADIUS = 16;
    static final int MIN_RADIUS = 1;
    static final int MAX_RADIUS = 32;

    private SearchContainersTool() {
    }

    public static ToolSpec spec() {
        return ToolSpec.builder(NAME, "在玩家周围已加载的区块里查找容器（箱子 / 木桶 / 熔炉等），"
                        + "可按方块类型或箱内物品过滤，返回坐标与命中数量。适用于「附近哪个箱子有钻石」这类问题。")
                .stringParam("block_id", "容器方块的注册表 id，例如 minecraft:chest", false)
                .stringParam("item_id", "只找装有该物品的容器，例如 minecraft:diamond（需服务端开启按内容过滤）", false)
                .integerParam("radius", "搜索半径（格）", MIN_RADIUS, MAX_RADIUS, false)
                .build();
    }

    static ToolOutcome invoke(ServerPlayer player, AiConfig config, JsonObject args) {
        Optional<String> rawBlock = ToolArgs.string(args, "block_id");
        Optional<String> rawItem = ToolArgs.string(args, "item_id");

        Block blockFilter = null;
        if (rawBlock.isPresent()) {
            Identifier id = Identifier.tryParse(rawBlock.get());
            if (id == null) {
                return ToolOutcome.error("block_id 不是合法的注册表 id：" + rawBlock.get());
            }
            Optional<Block> resolved = BuiltInRegistries.BLOCK.getOptional(id);
            if (resolved.isEmpty()) {
                return ToolOutcome.error("注册表里不存在这个方块：" + id);
            }
            blockFilter = resolved.get();
        }

        Item itemFilter = null;
        if (rawItem.isPresent()) {
            if (!config.containersReadContents()) {
                // 明确拒绝并给出替代方案，而不是静默忽略这个参数（模型会误以为"没有箱子装钻石"）
                return ToolOutcome.error("本服务端未开启按容器内容过滤（ai.tool.containersReadContents=false），"
                        + "只能按方块类型查找，请改用 block_id 参数。");
            }
            Identifier id = Identifier.tryParse(rawItem.get());
            if (id == null) {
                return ToolOutcome.error("item_id 不是合法的注册表 id：" + rawItem.get());
            }
            Optional<Item> resolved = BuiltInRegistries.ITEM.getOptional(id);
            if (resolved.isEmpty()) {
                return ToolOutcome.error("注册表里不存在这个物品：" + id);
            }
            itemFilter = resolved.get();
        }

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
        int inspected = 0;

        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                LevelChunk chunk = chunkSource.getChunkNow(chunkX, chunkZ);
                if (chunk == null) {
                    continue;
                }
                for (Map.Entry<BlockPos, BlockEntity> entry : chunk.getBlockEntities().entrySet()) {
                    BlockEntity blockEntity = entry.getValue();
                    if (!(blockEntity instanceof Container container)) {
                        continue;
                    }
                    BlockPos pos = entry.getKey();
                    int distanceSqr = distanceSqr(origin, pos);
                    if (distanceSqr > radiusSqr) {
                        continue;
                    }
                    inspected++;

                    Block block = blockEntity.getBlockState().getBlock();
                    if (blockFilter != null && block != blockFilter) {
                        continue;
                    }
                    int matched = 0;
                    if (itemFilter != null) {
                        matched = countItem(container, itemFilter);
                        if (matched == 0) {
                            continue;
                        }
                    }
                    Identifier blockId = BuiltInRegistries.BLOCK.getKey(block);
                    if (blockId == null) {
                        continue;
                    }
                    hits.add(new Hit(blockId.toString(), pos.getX(), pos.getY(), pos.getZ(), distanceSqr, matched));
                }
            }
        }

        hits.sort(Comparator.comparingInt(Hit::distanceSqr));
        return ToolOutcome.ok(render(radius, inspected, hits, itemFilter != null, config.toolMaxResults()));
    }

    private static String render(int radius, int inspected, List<Hit> hits, boolean filteredByItem, int maxResults) {
        StringBuilder out = new StringBuilder(256);
        out.append("在半径 ").append(radius).append(" 格内检查了 ").append(inspected)
                .append(" 个容器（仅已加载区块）：\n");

        if (hits.isEmpty()) {
            out.append(filteredByItem ? "没有找到装有该物品的容器。" : "没有找到符合条件的容器。");
            return out.toString();
        }

        int limit = Math.min(hits.size(), Math.max(1, maxResults));
        out.append("命中 ").append(hits.size()).append(" 个，最近的 ").append(limit).append(" 个：\n");
        for (int i = 0; i < limit; i++) {
            Hit hit = hits.get(i);
            out.append("- ").append(hit.blockId())
                    .append(" 位于 ").append(hit.x()).append(' ').append(hit.y()).append(' ').append(hit.z())
                    .append("（距离 ").append((int) Math.round(Math.sqrt(hit.distanceSqr()))).append(" 格");
            if (hit.matched() > 0) {
                out.append("，含目标物品共 ").append(hit.matched()).append(" 个");
            }
            out.append("）\n");
        }
        return out.toString();
    }

    private static int countItem(Container container, Item item) {
        int total = 0;
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack stack = container.getItem(slot);
            if (!stack.isEmpty() && stack.getItem() == item) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private static int distanceSqr(BlockPos origin, BlockPos target) {
        int dx = target.getX() - origin.getX();
        int dy = target.getY() - origin.getY();
        int dz = target.getZ() - origin.getZ();
        return dx * dx + dy * dy + dz * dz;
    }

    private record Hit(String blockId, int x, int y, int z, int distanceSqr, int matched) {
    }
}
