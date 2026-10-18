package net.neoforged.neoforge.registries;

import java.util.function.Supplier;

/**
 * 运行时桥接 stub：NeoForge 26.x 注册句柄基类 {@code DeferredHolder}
 *（旧名 RegistryObject，26.x 改名。两者都保留以免 NoClassDefFound）。
 */
public class DeferredHolder<T> extends RegistryObject<T> {
    DeferredHolder(String name, String modId, Supplier<? extends T> supplier) {
        super(name, modId, supplier);
    }
}
