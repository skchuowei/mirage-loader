package net.neoforged.neoforge.registries;

import java.util.function.Supplier;

/**
 * 沙盒 fake：NeoForge 注册句柄基类 {@code RegistryObject}（旧名，26.x 仍常见写法）。
 * 占位：不真正回填 MC 注册表。
 */
public class RegistryObject<T> implements Supplier<T> {

    private final String name;
    private final String modId;
    private final Supplier<? extends T> supplier;
    private T value;

    RegistryObject(String name, String modId, Supplier<? extends T> supplier) {
        this.name = name;
        this.modId = modId;
        this.supplier = supplier;
    }

    @Override
    public synchronized T get() {
        if (value == null) {
            value = supplier.get();
        }
        return value;
    }

    public boolean isPresent() {
        return value != null;
    }

    public String getId() {
        return modId + ":" + name;
    }
}
