package org.gwfx.aiassisted.platform;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;

import java.util.Optional;

/**
 * 版本适配层：内置注册表查询。
 */
public final class RegistryLookup {

    private RegistryLookup() {}

    public static Optional<Item> item(Identifier id) {
        return BuiltInRegistries.ITEM.get(id).map(Holder.Reference::value);
    }

    public static boolean hasItem(Identifier id) {
        return id != null && BuiltInRegistries.ITEM.containsKey(id);
    }

    public static Optional<Block> block(Identifier id) {
        return id == null ? Optional.empty() : BuiltInRegistries.BLOCK.get(id).map(Holder.Reference::value);
    }

    public static boolean hasBlock(Identifier id) {
        return id != null && BuiltInRegistries.BLOCK.containsKey(id);
    }

    public static String blockId(Block block) {
        Identifier id = block == null ? null : BuiltInRegistries.BLOCK.getKey(block);
        return id == null ? "" : id.toString();
    }
}
