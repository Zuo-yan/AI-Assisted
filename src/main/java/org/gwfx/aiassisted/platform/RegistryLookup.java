package org.gwfx.aiassisted.platform;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;

import java.util.Optional;

/**
 * 版本适配层：内置注册表查询。
 *
 * <p>26.3 的 {@code Registry#get} 返回 {@code Optional<Holder.Reference<T>>}；
 * 1.20.1 直接提供 {@code getOptional(T)}，语义一致。
 */
public final class RegistryLookup {

    private RegistryLookup() {}

    public static Optional<Item> item(ResourceLocation id) {
        return id == null ? Optional.empty() : BuiltInRegistries.ITEM.getOptional(id);
    }

    public static boolean hasItem(ResourceLocation id) {
        return id != null && BuiltInRegistries.ITEM.containsKey(id);
    }

    public static Optional<Block> block(ResourceLocation id) {
        return id == null ? Optional.empty() : BuiltInRegistries.BLOCK.getOptional(id);
    }

    public static boolean hasBlock(ResourceLocation id) {
        return id != null && BuiltInRegistries.BLOCK.containsKey(id);
    }

    public static String blockId(Block block) {
        ResourceLocation id = block == null ? null : BuiltInRegistries.BLOCK.getKey(block);
        return id == null ? "" : id.toString();
    }
}
