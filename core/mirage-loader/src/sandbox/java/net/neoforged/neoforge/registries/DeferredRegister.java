package net.neoforged.neoforge.registries;

import io.mirage.loader.sandbox.Sink;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.neoforged.bus.api.IEventBus;

import java.util.function.Supplier;

/**
 * 沙盒 fake：NeoForge {@code DeferredRegister} 的 recording proxy。
 * 不真注册到 MC 注册表，只把"被调用了谁"记进 {@code touched_apis}；
 * 返回的 {@link DeferredHolder} / {@link DeferredBlock} / {@link DeferredItem} 都是占位句柄。
 */
public class DeferredRegister<T> {

    protected final ResourceKey<? extends Registry<T>> registryKey;
    protected final String modId;
    private final String fixedPath;

    private DeferredRegister(ResourceKey<? extends Registry<T>> registryKey, String modId) {
        this(registryKey, modId, null);
    }

    private DeferredRegister(ResourceKey<? extends Registry<T>> registryKey, String modId, String fixedPath) {
        this.registryKey = registryKey;
        this.modId = modId;
        this.fixedPath = fixedPath;
    }

    // ---- 工厂方法（签名对齐 NeoForge） ----

    public static <T> DeferredRegister<T> create(Registry<T> registry, String namespace) {
        Sink.touch("net.neoforged.neoforge.registries.DeferredRegister.create");
        Sink.deferredRegisterCreated();
        return new DeferredRegister<>(registry.key(), namespace);
    }

    public static <T> DeferredRegister<T> create(ResourceKey<? extends Registry<T>> key, String namespace) {
        Sink.touch("net.neoforged.neoforge.registries.DeferredRegister.create");
        Sink.deferredRegisterCreated();
        return new DeferredRegister<>(key, namespace);
    }

    public static Blocks createBlocks(String modId) {
        Sink.touch("net.neoforged.neoforge.registries.DeferredRegister.createBlocks");
        Sink.deferredRegisterCreated();
        return new Blocks(modId);
    }

    public static Items createItems(String modId) {
        Sink.touch("net.neoforged.neoforge.registries.DeferredRegister.createItems");
        Sink.deferredRegisterCreated();
        return new Items(modId);
    }

    /** 方块专用子类型。 */
    public static final class Blocks extends DeferredRegister<Object> {
        private Blocks(String modId) {
            super(null, modId, "block");
        }

        public <I> DeferredBlock<I> register(String name, Supplier<I> supplier) {
            Sink.touch("net.neoforged.neoforge.registries.DeferredRegister.register(entry)");
            return new DeferredBlock<>(name, modId, supplier);
        }
    }

    /** 物品专用子类型。 */
    public static final class Items extends DeferredRegister<Object> {
        private Items(String modId) {
            super(null, modId, "item");
        }

        public <I> DeferredItem<I> register(String name, Supplier<I> supplier) {
            Sink.touch("net.neoforged.neoforge.registries.DeferredRegister.register(entry)");
            return new DeferredItem<>(name, modId, supplier);
        }
    }

    // ---- 条目声明 ----

    public <I extends T> DeferredHolder<I> register(String name, Supplier<I> supplier) {
        Sink.touch("net.neoforged.neoforge.registries.DeferredRegister.register(entry)");
        return new DeferredHolder<>(name, modId, supplier);
    }

    public void register(IEventBus bus) {
        Sink.touch("net.neoforged.neoforge.registries.DeferredRegister.register(bus)");
    }
}
