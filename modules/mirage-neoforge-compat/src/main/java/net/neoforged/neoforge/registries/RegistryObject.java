package net.neoforged.neoforge.registries;

import java.util.function.Supplier;

/**
 * 运行时桥接 stub：注册条目的句柄（NeoForge 4+ 真名为 {@code DeferredHolder}，
 * 但旧 mod / 教材常见 {@code RegistryObject} 写法，两者都提供以免 NoClassDefFound）。
 *
 * <p>第一版语义：{@link #get()} 直接回退到声明时的 supplier（首次调用时缓存）。
 * 真正挂到 MC 注册表的动作由 MG DeferredRegister 闭环完成；
 * 句柄与 MC 注册表条目的反向绑定留待后续（README TODO）。
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
