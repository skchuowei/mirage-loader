package net.neoforged.neoforge.registries;

import java.util.function.Supplier;

/**
 * 沙盒 fake：NeoForge 26.x 方块注册句柄 {@code DeferredBlock}。
 */
public class DeferredBlock<T> extends DeferredHolder<T> {
    DeferredBlock(String name, String modId, Supplier<? extends T> supplier) {
        super(name, modId, supplier);
    }
}
