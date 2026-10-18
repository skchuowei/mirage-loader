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

    protected final ResourceKey<? extends Registry<T>> registryKey;
    protected final String modId;
    /** 由 createBlocks/createItems 便捷工厂直接指定注册表路径，绕开编译期无 MC Registry 常量。 */
    private final String fixedPath;
    private final List<RegisteredEntry<?>> entries = new ArrayList<>();
    private io.mirage.api.registry.DeferredRegister<T> bridge;

    private DeferredRegister(ResourceKey<? extends Registry<T>> registryKey, String modId) {
        this(registryKey, modId, null);
    }

    private DeferredRegister(ResourceKey<? extends Registry<T>> registryKey, String modId, String fixedPath) {
        this.registryKey = registryKey;
        this.modId = modId;
        this.fixedPath = fixedPath;
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

    /**
     * NeoForge 26.x 便捷工厂：{@code DeferredRegister.createBlocks(modId)}。
     * 返回 {@link Blocks} 子类，JVM 链接时期望返回类型 {@code DeferredRegister$Blocks}。
     */
    public static Blocks createBlocks(String modId) {
        return new Blocks(modId);
    }

    /** NeoForge 26.x 便捷工厂：{@code DeferredRegister.createItems(modId)}。 */
    public static Items createItems(String modId) {
        return new Items(modId);
    }

    /** NeoForge 26.x 新增：方块专用 DeferredRegister 子类型。泛型擦除为 Object，运行时签名占位。 */
    public static final class Blocks extends DeferredRegister<Object> {
        private Blocks(String modId) { super(null, modId, "block"); }

        /** 协变返回 DeferredBlock，匹配 NeoForge 26.x 编译期签名。 */
        public <I> DeferredBlock<I> register(String name, Supplier<I> supplier) {
            super.register(name, supplier);
            return new DeferredBlock<>(name, modId, supplier);
        }
    }

    /** NeoForge 26.x 新增：物品专用 DeferredRegister 子类型。 */
    public static final class Items extends DeferredRegister<Object> {
        private Items(String modId) { super(null, modId, "item"); }

        public <I> DeferredItem<I> register(String name, Supplier<I> supplier) {
            super.register(name, supplier);
            return new DeferredItem<>(name, modId, supplier);
        }
    }

    // ---------------------------------------------------------------
    // 条目声明
    // ---------------------------------------------------------------

    public <I extends T> DeferredHolder<I> register(String name, Supplier<I> supplier) {
        // 兼容 mod 动态注册时序（如 ModBlocks.registerBlockItem 在 ModItems.ITEMS 已挂总线后
        // 再追加 BlockItem）：bridge 已存在时直接 forward，不抛异常。
        if (bridge != null) {
            @SuppressWarnings("unchecked")
            io.mirage.api.registry.DeferredRegister<I> b =
                    (io.mirage.api.registry.DeferredRegister<I>) bridge;
            b.register(name, supplier);
            return new DeferredHolder<>(name, modId, supplier);
        }
        DeferredHolder<I> holder = new DeferredHolder<>(name, modId, supplier);
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

    /** registryKey 类型识别：26.x 起 ResourceKey.location() 已移除，直接从 toString() 解析。 */
    private String resolveRegistryPath() {
        if (fixedPath != null) return fixedPath;
        // ResourceKey.toString() 形如 "ResourceKey[minecraft:root / minecraft:item]"
        // 斜杠后才是真正的注册表 ID "minecraft:item"
        String s = String.valueOf(registryKey);
        int slash = s.indexOf('/');
        if (slash >= 0) {
            String after = s.substring(slash + 1).trim();
            int close = after.indexOf(']');
            if (close > 0) after = after.substring(0, close).trim();
            int colon = after.indexOf(':');
            if (colon >= 0) after = after.substring(colon + 1);
            System.out.println("[Mirage:NeoDBG] modId=" + modId + " parsed registry path=" + after + " from " + s);
            return after;
        }
        return "";
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
            case "creative_mode_tab" -> "net.minecraft.world.item.CreativeModeTab";
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
