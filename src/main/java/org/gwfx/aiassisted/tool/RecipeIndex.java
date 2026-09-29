package org.gwfx.aiassisted.tool;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.context.ContextMap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.display.RecipeDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 配方反查索引：成品物品 id → 能产出它的配方。
 *
 * <p><b>为什么需要它</b>：{@code RecipeManager} 只支持「按输入找配方」，没有「按成品反查」，
 * 而模型问的是「钻石剑怎么做」—— 成品才是输入。没有索引就只能每次全量枚举并逐个解析成品，
 * 对一条每轮都可能被调用的工具来说太浪费。
 *
 * <p><b>失效策略</b>：缓存键是 {@link RecipeManager} <b>实例本身</b>。配方只会在数据包重载时
 * 整体替换（换出新实例），因此「实例变了就重建」既不需要监听重载事件，也不可能读到过期索引。
 *
 * <p><b>线程</b>：必须在服务端主线程使用（读配方与注册表）。
 */
public final class RecipeIndex {

    private RecipeManager cachedManager;
    private Map<String, List<ResourceKey<Recipe<?>>>> resultsByItem = Map.of();

    List<ResourceKey<Recipe<?>>> recipesFor(ServerLevel level, String itemId) {
        // 26.3 的 ServerLevel 覆写了 Level#recipeAccess()，返回类型直接就是 RecipeManager，无需强转
        RecipeManager manager = level.recipeAccess();
        if (manager != this.cachedManager) {
            rebuild(manager, level);
        }
        return this.resultsByItem.getOrDefault(itemId, List.of());
    }

    private void rebuild(RecipeManager manager, ServerLevel level) {
        Map<String, List<ResourceKey<Recipe<?>>>> index = new HashMap<>();
        ContextMap context = SlotDisplayContext.fromLevel(level);
        for (RecipeHolder<?> holder : manager.getRecipes()) {
            String resultId = resultItemId(holder, context);
            if (resultId == null) {
                // 解析不出成品的配方（部分动态/特殊配方 display() 为空）直接跳过，
                // 不能当成错误：它们本来就不该出现在"怎么做某物"的答案里
                continue;
            }
            index.computeIfAbsent(resultId, key -> new ArrayList<>(2)).add(holder.id());
        }
        this.resultsByItem = Map.copyOf(index);
        this.cachedManager = manager;
    }

    /**
     * 取配方成品的物品 id。
     *
     * <p>26.3 的 {@code Recipe} 接口<b>没有</b> {@code getResultItem}，只有 {@code assemble(输入)}；
     * 想在不真正合成的前提下拿到成品，唯一通用途径是 {@code display()} 里的展示结果。
     */
    private static String resultItemId(RecipeHolder<?> holder, ContextMap context) {
        List<RecipeDisplay> displays = holder.value().display();
        if (displays.isEmpty()) {
            return null;
        }
        ItemStack stack = displays.get(0).result().resolveForFirstStack(context);
        if (stack.isEmpty()) {
            return null;
        }
        Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return id == null ? null : id.toString();
    }
}
