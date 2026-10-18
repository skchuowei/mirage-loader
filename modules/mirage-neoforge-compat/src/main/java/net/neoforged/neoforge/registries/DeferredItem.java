package net.neoforged.neoforge.registries;

import java.util.function.Supplier;

/**
 * 运行时桥接 stub：NeoForge 26.x 新增的物品注册句柄 {@code DeferredItem}。
 */
public class DeferredItem<T> extends DeferredHolder<T> {
    DeferredItem(String name, String modId, Supplier<? extends T> supplier) {
        super(name, modId, supplier);
    }
}
