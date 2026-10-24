package net.minecraft.core.registries;

import net.minecraft.core.Registry;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;

/**
 * 沙盒 fake：MC {@code Registries} 最小占位。
 * 只提供 mod 静态初始化常触达的三个注册表键（ITEM / BLOCK / CREATIVE_MODE_TAB）。
 */
public final class Registries {

    public static final ResourceKey<Registry<Item>> ITEM = key("item");
    public static final ResourceKey<Registry<Block>> BLOCK = key("block");
    public static final ResourceKey<Registry<CreativeModeTab>> CREATIVE_MODE_TAB = key("creative_mode_tab");

    private static <T> ResourceKey<Registry<T>> key(String path) {
        return ResourceKey.create(null, Identifier.fromNamespaceAndPath("minecraft", path));
    }

    private Registries() {}
}
