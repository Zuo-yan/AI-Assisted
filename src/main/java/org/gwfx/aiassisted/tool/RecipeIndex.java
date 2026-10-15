package org.gwfx.aiassisted.tool;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 成品物品 ID 到配方 ID 的反查索引；仅在服务端主线程使用。 */
public final class RecipeIndex {
    private RecipeManager cachedManager;
    private Collection<RecipeHolder<?>> cachedRecipes;
    private Map<String, List<ResourceLocation>> resultsByItem = Map.of();

    List<ResourceLocation> recipesFor(ServerLevel level, String itemId) {
        RecipeManager manager = level.getRecipeManager();
        Collection<RecipeHolder<?>> recipes = manager.getRecipes();
        // 1.21.1 重载会替换配方表，RecipeManager 本身可能复用。
        if (manager != this.cachedManager || recipes != this.cachedRecipes) {
            Map<String, List<ResourceLocation>> index = new HashMap<>();
            for (RecipeHolder<?> holder : recipes) {
                ItemStack result = holder.value().getResultItem(level.registryAccess());
                if (!result.isEmpty()) {
                    String resultId = BuiltInRegistries.ITEM.getKey(result.getItem()).toString();
                    index.computeIfAbsent(resultId, key -> new ArrayList<>(2)).add(holder.id());
                }
            }
            this.resultsByItem = Map.copyOf(index);
            this.cachedManager = manager;
            this.cachedRecipes = recipes;
        }
        return this.resultsByItem.getOrDefault(itemId, List.of());
    }
}
