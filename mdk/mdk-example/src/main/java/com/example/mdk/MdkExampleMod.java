package com.example.mdk;

import io.mirage.api.Mod;
import io.mirage.api.SubscribeEvent;
import io.mirage.api.event.ModConstructEvent;
import io.mirage.api.registry.DeferredRegister;
import io.mirage.api.registry.RegistryObject;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;

/**
 * Mirage Loader MDK 示例 Mod 主类。
 *
 * 启动顺序：
 *   1. Launcher 扫描 mods/*.jar
 *   2. 读 mirage.mod.json，发现 @Mod("mdk-example")
 *   3. 实例化本类
 *   4. post ModConstructEvent → 注册方块和物品
 */
@Mod("mdk-example")
public class MdkExampleMod {

    public static final String MOD_ID = "mdk-example";

    // 方块 ResourceKey
    public static final ResourceKey<Block> EXAMPLE_BLOCK_KEY = ResourceKey.create(
        BuiltInRegistries.BLOCK.key(),
        Identifier.fromNamespaceAndPath(MOD_ID, "example_block"));

    // 自定义方块
    public static final Block EXAMPLE_BLOCK = new Block(
        BlockBehaviour.Properties.of()
            .setId(EXAMPLE_BLOCK_KEY)
            .mapColor(MapColor.STONE)
            .strength(3.0f, 3.0f)
            .requiresCorrectToolForDrops()
    );

    // 物品 ResourceKey
    public static final ResourceKey<Item> EXAMPLE_ITEM_KEY = ResourceKey.create(
        BuiltInRegistries.ITEM.key(),
        Identifier.fromNamespaceAndPath(MOD_ID, "example_block"));

    // 方块对应的物品
    public static final Item EXAMPLE_BLOCK_ITEM = new BlockItem(EXAMPLE_BLOCK,
        new Item.Properties().setId(EXAMPLE_ITEM_KEY));

    // ---- DeferredRegister 最小闭环演示 ----
    // 声明一个延迟注册器：这里只登记，不真正写 MC 注册表；
    // 加载器会在所有 mod 初始化完后统一 flush 到 BuiltInRegistries.ITEM。
    public static final DeferredRegister<Item> ITEMS =
        DeferredRegister.create(Item.class, MOD_ID);
    public static final RegistryObject<Item> MIRACLE_DUST = ITEMS.register("miracle_dust",
        () -> new Item(new Item.Properties().setId(ResourceKey.create(
            BuiltInRegistries.ITEM.key(),
            Identifier.fromNamespaceAndPath(MOD_ID, "miracle_dust")))));

    @SubscribeEvent
    public void onConstruct(ModConstructEvent event) {
        System.out.println("[MdkExample] 模组构造中...");

        // 注册方块
        Registry.register(BuiltInRegistries.BLOCK,
            EXAMPLE_BLOCK_KEY.identifier(),
            EXAMPLE_BLOCK);
        System.out.println("[MdkExample] 已注册方块: example_block");

        // 注册物品
        Registry.register(BuiltInRegistries.ITEM,
            EXAMPLE_ITEM_KEY.identifier(),
            EXAMPLE_BLOCK_ITEM);
        System.out.println("[MdkExample] 已注册物品: example_block_item");

        // hook DeferredRegister：加载器之后统一 flush（此时注册表仍在 unfreeze 窗口）
        ITEMS.register();
        System.out.println("[MdkExample] DeferredRegister 已 hook: miracle_dust");

        System.out.println("[MdkExample] 模组加载完成!");
    }
}
