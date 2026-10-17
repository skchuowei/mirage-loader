package net.neoforged.neoforge.registries;

// 注意：不能 import io.mirage.api.registry.DeferredRegister——本类与之同名，
// 同一编译单元简单名冲突。下面一律用全限定名引用 MG 侧 DeferredRegister。
import net.minecraft.core.Registry;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.neoforged.bus.api.IEventBus;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * 运行时桥接 stub：NeoForge {@code DeferredRegister}。
 *
 * <p>典型 mod 写法：
 * <pre>{@code
 * public static final DeferredRegister<Item> ITEMS =
 *     DeferredRegister.register(Registries.ITEM, "mymod");
 * public static final RegistryObject<Item> RUBY =
 *     ITEMS.register("ruby", () -> new Item(...));
 *
 * public MyMod() {
 *     ITEMS.register(FMLJavaModLoadingContext.get().getModEventBus());
 * }
 * }</pre>
 *
 * <p>桥接策略（clean-room，零 Launcher 改动）：
 * <ol>
 *   <li>{@link #register(String, Supplier)} 阶段只把条目暂存在本类；</li>
 *   <li>{@link #register(IEventBus)} 时根据注册表 key 的 location（{@code minecraft:item} /
 *       {@code minecraft:block}）反射出 MC 元素类型，创建一个 MG
 *       {@link DeferredRegister} 代理，把暂存条目逐条转过去，再调代理的
 *       {@code register()} 挂上 MG hook——Launcher 已在 mod 实例化前装好
 *       hookListener 与 nativeBackend，flush 阶段统一写 MC 注册表，时序天然闭环。</li>
 * </ol>
 *
 * <p>未知注册表类型（entity_type / biome ...）打印警告并跳过，不静默失败、不猜类名。
 */
public class DeferredRegister<T> {

    private final ResourceKey<? extends Registry<T>> registryKey;
    private final String modId;
    private final List<RegisteredEntry<?>> entries = new ArrayList<>();
    private io.mirage.api.registry.DeferredRegister<T> bridge;

    private DeferredRegister(ResourceKey<? extends Registry<T>> registryKey, String modId) {
        this.registryKey = registryKey;
        this.modId = modId;
    }

    // ---------------------------------------------------------------
    // 工厂方法（签名对齐 NeoForge；参数类型来自 compile-stub 镜像）
    // ---------------------------------------------------------------

    public static <T> DeferredRegister<T> create(Registry<T> registry, String namespace) {
        return new DeferredRegister<>(registry.key(), namespace);
    }

    public static <T> DeferredRegister<T> create(ResourceKey<? extends Registry<T>> key, String namespace) {
        return new DeferredRegister<>(key, namespace);
    }

    // ---------------------------------------------------------------
    // 条目声明
    // ---------------------------------------------------------------

    public <I extends T> RegistryObject<I> register(String name, Supplier<I> supplier) {
        if (bridge != null) {
            throw new IllegalStateException("DeferredRegister (mod=" + modId
                    + ") 已 register(bus)，不能再声明新条目: " + name);
        }
        RegistryObject<I> holder = new RegistryObject<>(name, modId, supplier);
        entries.add(new RegisteredEntry<>(name, supplier, holder));
        return holder;
    }

    // ---------------------------------------------------------------
    // 挂总线：此时桥接到 MG DeferredRegister
    // ---------------------------------------------------------------

    public void register(IEventBus bus) {
        if (bridge != null) {
            return;
        }
        String path = resolveRegistryPath();
        Class<T> elementType = mapElementType(path);
        if (elementType == null) {
            System.out.println("[Mirage:NeoDR] " + modId + " 跳过未知注册表 " + path
                    + "（" + entries.size() + " 个条目未桥接）");
            return;
        }

        bridge = io.mirage.api.registry.DeferredRegister.create(elementType, modId);
        for (RegisteredEntry<?> e : entries) {
            e.forwardTo(bridge);
        }
        bridge.register();
        System.out.println("[Mirage:NeoDR] " + modId + ":" + path
                + " 桥接 " + entries.size() + " 个条目到 MG DeferredRegister");
    }

    /** registryKey.location().getPath()；镜像返回 null 时安全兜底。 */
    private String resolveRegistryPath() {
        try {
            Identifier loc = registryKey.location();
            return loc == null ? "" : loc.getPath();
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * 注册表 location 路径 → MC 元素类型（26.x 无混淆，类名稳定）。
     * 只映射 Launcher nativeBackend 已支持的 item/block；其余返回 null。
     */
    @SuppressWarnings("unchecked")
    private Class<T> mapElementType(String registryPath) {
        String cn = switch (registryPath) {
            case "item" -> "net.minecraft.world.item.Item";
            case "block" -> "net.minecraft.world.level.block.Block";
            default -> null;
        };
        if (cn == null) {
            return null;
        }
        try {
            // 当前类由 mcl 加载，Class.forName 不带 loader 即用 mcl，能找到 MC 真类
            return (Class<T>) Class.forName(cn);
        } catch (ClassNotFoundException e) {
            System.err.println("[Mirage:NeoDR] 找不到 MC 元素类型 " + cn + ": " + e);
            return null;
        }
    }

    /** 暂存条目：forward 给 MG 代理时带上泛型 supplier。 */
    private static final class RegisteredEntry<T> {
        final String name;
        final Supplier<T> supplier;
        final RegistryObject<T> holder;

        RegisteredEntry(String name, Supplier<T> supplier, RegistryObject<T> holder) {
            this.name = name;
            this.supplier = supplier;
            this.holder = holder;
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        void forwardTo(io.mirage.api.registry.DeferredRegister bridge) {
            bridge.register(name, supplier);
        }
    }
}
