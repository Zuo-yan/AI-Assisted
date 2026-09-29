package org.gwfx.aiassisted.tool;

import com.google.gson.JsonObject;
import net.minecraft.core.NonNullList;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import org.gwfx.aiassisted.core.agent.ToolArgs;
import org.gwfx.aiassisted.core.agent.ToolOutcome;
import org.gwfx.aiassisted.core.config.AiConfig;
import org.gwfx.aiassisted.core.llm.ToolSpec;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 工具 {@code search_recipes}：查「某物品怎么做」，列出配方 id 与所需材料。
 *
 * <p>补的缺口很明确：<b>配方数据完全不在上下文里</b>。上下文只给背包与附近情况，
 * 所以「钻石剑怎么做」原本只能靠模型自己背原版知识 —— 装了模组就会瞎编。
 *
 * <p>成本控制：反查走 {@link RecipeIndex}（按 RecipeManager 实例失效），
 * 每条配方只做一次材料描述；返回条数沿用 {@code ai.toolMaxResults}。
 *
 * <p><b>必须在服务端主线程调用</b>。
 */
public final class SearchRecipesTool {

    public static final String NAME = "search_recipes";

    /** 单个材料位最多列出几种可接受的物品（例如「任意木板」会命中很多种）。 */
    private static final int MAX_INGREDIENT_NAMES = 4;

    private SearchRecipesTool() {
    }

    public static ToolSpec spec() {
        return ToolSpec.builder(NAME, "查询某个物品能用哪些配方做出来，列出配方 id 与所需材料。"
                        + "适用于「钻石剑怎么做」「这个物品要什么材料」这类问题。")
                .stringParam("item_id", "成品物品的注册表 id，例如 minecraft:diamond_sword", true)
                .build();
    }

    static ToolOutcome invoke(ServerLevel level, RecipeIndex index, AiConfig config, JsonObject args) {
        Optional<String> rawId = ToolArgs.string(args, "item_id");
        if (rawId.isEmpty()) {
            return ToolOutcome.error("缺少参数 item_id（成品物品的注册表 id，例如 minecraft:diamond_sword）");
        }
        ResourceLocation id = ResourceLocation.tryParse(rawId.get());
        if (id == null) {
            return ToolOutcome.error("item_id 不是合法的注册表 id：" + rawId.get());
        }
        if (BuiltInRegistries.ITEM.getOptional(id).isEmpty()) {
            return ToolOutcome.error("注册表里不存在这个物品：" + id);
        }

        List<ResourceLocation> keys = index.recipesFor(level, id.toString());
        if (keys.isEmpty()) {
            return ToolOutcome.ok("没有任何已加载的配方能产出 " + id
                    + "。它可能是通过交易、怪物掉落或结构获得的，或者相关配方未被数据包加载。");
        }

        int limit = Math.min(keys.size(), Math.max(1, config.toolMaxResults()));
        StringBuilder out = new StringBuilder(320);
        out.append("能产出 ").append(id).append(" 的配方共 ").append(keys.size()).append(" 个");
        if (keys.size() > limit) {
            out.append("（只列出前 ").append(limit).append(" 个）");
        }
        out.append("：\n");

        for (int i = 0; i < limit; i++) {
            ResourceLocation key = keys.get(i);
            out.append("- 配方 ").append(key);
            Optional<Recipe<?>> recipe = index.recipe(key);
            if (recipe.isEmpty()) {
                // 索引与当前配方表不一致（极罕见：刚好在构建索引时重载了数据包）
                out.append("：（已失效，请重试）\n");
                continue;
            }
            out.append('\n').append(describeIngredients(recipe.get()));
        }
        return ToolOutcome.ok(out.toString());
    }

    /**
     * 描述一条配方的材料。
     *
     * <p><b>份数怎么来的</b>：{@code getIngredients()} 是「一个占用槽位一条记录」，<b>不做去重</b> ——
     * 例如「四格各放一块木板」会返回 4 个等价 Ingredient。1.20.1 的 {@code Ingredient}
     * 不实现 equals，所以必须按「可接受物品集合」归并计数，而不是对象身份或槽位下标。
     */
    private static String describeIngredients(Recipe<?> recipe) {
        NonNullList<Ingredient> ingredients = recipe.getIngredients();
        if (ingredients.isEmpty()) {
            // 动态配方（如地图复制、旗帜图案）没有固定的摆放形式，硬列材料只会错
            return "  材料：（该配方不是常规摆放型，无法列出材料）\n";
        }

        Map<String, Integer> counts = new LinkedHashMap<>();
        Map<String, Ingredient> repr = new LinkedHashMap<>();
        for (Ingredient ingredient : ingredients) {
            String key = ingredientKey(ingredient);
            counts.merge(key, 1, Integer::sum);
            repr.putIfAbsent(key, ingredient);
        }

        int kinds = counts.size();
        int total = ingredients.size();
        StringBuilder line = new StringBuilder("  材料（").append(kinds).append(" 种 / ").append(total).append(" 份）：");
        boolean first = true;
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            if (!first) {
                line.append("、");
            }
            first = false;
            line.append(describeIngredient(repr.get(entry.getKey()))).append(" ×").append(entry.getValue());
        }
        return line.append('\n').toString();
    }

    /** 材料位的归并键：可接受物品 id 集合（排序后拼接）。 */
    private static String ingredientKey(Ingredient ingredient) {
        return Arrays.stream(ingredient.getItems())
                .map(stack -> BuiltInRegistries.ITEM.getKey(stack.getItem()).toString())
                .sorted()
                .collect(Collectors.joining(","));
    }

    /** 描述一个材料位可以接受的物品。 */
    private static String describeIngredient(Ingredient ingredient) {
        // getItems() 对标签型材料会展开为全部可接受物品；只取前几个命名
        List<String> names = Arrays.stream(ingredient.getItems())
                .map(ItemStack::getItem)
                .map(item -> {
                    ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
                    return id == null ? "(未知)" : id.toString();
                })
                .distinct()
                .limit(MAX_INGREDIENT_NAMES + 1L)
                .toList();

        if (names.isEmpty()) {
            return "(空材料)";
        }
        if (names.size() > MAX_INGREDIENT_NAMES) {
            // 标签型材料（如「任意木板」）会命中很多物品，只列前几个并标注可替代
            return String.join("/", names.subList(0, MAX_INGREDIENT_NAMES)) + "…(可用多种)";
        }
        return String.join("/", names);
    }
}
