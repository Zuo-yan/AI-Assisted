package org.gwfx.aiassisted.tool;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 配方反查索引：成品物品 id → 能产出它的配方。
 *
 * <p><b>为什么需要它</b>：{@code RecipeManager} 只支持「按输入找配方」，没有「按成品反查」，
 * 而模型问的是「钻石剑怎么做」—— 成品才是输入。没有索引就只能每次全量枚举并逐个解析成品，
 * 对一条每轮都可能被调用的工具来说太浪费。
 *
 * <p><b>26.3 → 1.20.1 的差异</b>：1.20.1 还没有 {@code RecipeHolder}（1.20.2 才引入），
 * 配方 id 直接挂在 {@code Recipe#getId()} 上、成品用 {@code getResultItem(RegistryAccess)} 解析；
 * 26.3 走的则是新的 {@code RecipeDisplay} 展示体系。索引顺带保留 id → 配方的映射，
 * 供 {@code SearchRecipesTool} 取回配方本体。
 *
 * <p><b>失效策略</b>：缓存键是 {@link RecipeManager} <b>实例本身</b>。配方只会在数据包重载时
 * 整体替换（换出新实例），因此「实例变了就重建」既不需要监听重载事件，也不可能读到过期索引。
 *
 * <p><b>线程</b>：必须在服务端主线程使用（读配方与注册表）。
 */
public final class RecipeIndex {

    private RecipeManager cachedManager;
    private Map<String, List<ResourceLocation>> resultsByItem = Map.of();
    private Map<ResourceLocation, Recipe<?>> recipesById = Map.of();

    List<ResourceLocation> recipesFor(ServerLevel level, String itemId) {
        RecipeManager manager = level.getRecipeManager();
        if (manager != this.cachedManager) {
            rebuild(manager, level);
        }
        return this.resultsByItem.getOrDefault(itemId, List.of());
    }

    /** 按 id 取配方本体；索引与当前配方表不一致（罕见竞态）时返回空。 */
    Optional<Recipe<?>> recipe(ResourceLocation id) {
        return Optional.ofNullable(this.recipesById.get(id));
    }

    private void rebuild(RecipeManager manager, ServerLevel level) {
        Map<String, List<ResourceLocation>> index = new HashMap<>();
        Map<ResourceLocation, Recipe<?>> recipes = new HashMap<>();
        for (Recipe<?> recipe : manager.getRecipes()) {
            String resultId = resultItemId(recipe, level);
            if (resultId == null) {
                // 解析不出成品的配方（部分动态/特殊配方的结果为空）直接跳过，
                // 不能当成错误：它们本来就不该出现在"怎么做某物"的答案里
                continue;
            }
            index.computeIfAbsent(resultId, key -> new ArrayList<>(2)).add(recipe.getId());
            recipes.put(recipe.getId(), recipe);
        }
        this.resultsByItem = Map.copyOf(index);
        this.recipesById = Map.copyOf(recipes);
        this.cachedManager = manager;
    }

    /** 取配方成品的物品 id；特殊配方（地图复制、旗帜图案等）可能没有固定结果 —— 返回空并跳过。 */
    private static String resultItemId(Recipe<?> recipe, ServerLevel level) {
        ItemStack result = recipe.getResultItem(level.registryAccess());
        if (result == null || result.isEmpty()) {
            return null;
        }
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(result.getItem());
        return id == null ? null : id.toString();
    }
}
