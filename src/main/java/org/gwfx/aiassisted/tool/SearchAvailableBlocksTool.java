package org.gwfx.aiassisted.tool;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.gwfx.aiassisted.core.agent.ToolArgs;
import org.gwfx.aiassisted.core.agent.ToolOutcome;
import org.gwfx.aiassisted.core.build.BlueprintValidator;
import org.gwfx.aiassisted.core.llm.ToolSpec;

import java.util.Locale;

/**
 * 工具 {@code search_available_blocks}：搜索当前游戏及第三方模组中可用于建造的方块与材料。
 */
public final class SearchAvailableBlocksTool {

    public static final String NAME = "search_available_blocks";
    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 40;

    private SearchAvailableBlocksTool() {
    }

    public static ToolSpec spec() {
        return ToolSpec.builder(NAME,
                        "搜索当前世界及已安装第三方模组（如 twilightforest、refurbished_furniture 等）中可用的建筑方块与物品。"
                                + "你可以按模组 ID、英文名或中文名称进行模糊搜索，并将返回的真实方块 ID 填入建造蓝图 (propose_build) 的 palette 中。")
                .stringParam("query", "搜索关键词，例如 planks, wood, chair, table, 木板, 椅子, 桌子, 石砖, 灯 等", false)
                .stringParam("modid", "指定模组命名空间，例如 twilightforest, refurbished_furniture, create", false)
                .integerParam("limit", "最多返回多少条结果（默认 20，上限 40）", 1, 40, false)
                .build();
    }

    public static ToolOutcome invoke(JsonObject args) {
        String query = ToolArgs.string(args, "query").map(s -> s.strip().toLowerCase(Locale.ROOT)).orElse("");
        String modid = ToolArgs.string(args, "modid").map(s -> s.strip().toLowerCase(Locale.ROOT)).orElse("");
        int limit = ToolArgs.integer(args, "limit", DEFAULT_LIMIT, 1, MAX_LIMIT);

        JsonArray results = new JsonArray();
        int count = 0;

        for (Block block : BuiltInRegistries.BLOCK) {
            if (count >= limit) {
                break;
            }

            if (block == Blocks.AIR || block.defaultBlockState().isAir()) {
                continue;
            }

            Identifier key = BuiltInRegistries.BLOCK.getKey(block);
            if (key == null) {
                continue;
            }

            String namespace = key.getNamespace();
            String path = key.getPath();
            String fullId = key.toString();

            // 过滤危险黑名单方块（基岩/命令方块/TNT/刷怪笼等）
            if (BlueprintValidator.FORBIDDEN_BLOCKS.contains(fullId)) {
                continue;
            }

            // 命名空间过滤
            if (!modid.isEmpty() && !namespace.equalsIgnoreCase(modid)) {
                continue;
            }

            String localizedName = block.getName().getString();

            // 关键词匹配（匹配 ID、路径、或本地化名称）
            boolean matches = query.isEmpty()
                    || fullId.toLowerCase(Locale.ROOT).contains(query)
                    || localizedName.toLowerCase(Locale.ROOT).contains(query);

            if (matches) {
                JsonObject item = new JsonObject();
                item.addProperty("id", fullId);
                item.addProperty("name", localizedName);
                item.addProperty("modid", namespace);
                results.add(item);
                count++;
            }
        }

        JsonObject output = new JsonObject();
        output.addProperty("totalFound", count);
        output.add("blocks", results);

        if (count == 0) {
            return ToolOutcome.ok("未检索到符合条件的方块（query='" + query + "', modid='" + modid + "'）。提示：请尝试更换关键词，或将 modid 留空进行全库搜索。");
        }

        return ToolOutcome.ok(output.toString());
    }
}
