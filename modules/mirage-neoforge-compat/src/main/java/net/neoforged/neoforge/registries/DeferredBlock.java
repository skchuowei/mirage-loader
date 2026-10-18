package net.neoforged.neoforge.registries;

import java.util.function.Supplier;

/**
 * 运行时桥接 stub：NeoForge 26.x 新增的方块注册句柄 {@code DeferredBlock}。
 * 语义与 {@link RegistryObject} 一致，仅类型细分以匹配 mod 编译期签名。
 */
public class DeferredBlock<T> extends DeferredHolder<T> {
    DeferredBlock(String name, String modId, Supplier<? extends T> supplier) {
        super(name, modId, supplier);
    }
}
