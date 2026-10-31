package io.mirage.api.registry;

import io.mirage.api.event.Event;
import io.mirage.api.event.ModConstructEvent;
import io.mirage.api.event.ModInitEvent;
import io.mirage.api.hook.PassiveHooks;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 轻量版延迟注册器。
 *
 * <p>典型用法：
 * <pre>{@code
 * public static final DeferredRegister<Item> ITEMS =
 *     DeferredRegister.create(Item.class, "mymod");
 *
 * public static final RegistryObject<Item> RUBY =
 *     ITEMS.register("ruby", () -> new Item(new Item.Properties()));
 *
 * // 在 Mod 主类构造时：
 * ITEMS.register();   // 挂到事件总线，等待 ModInitEvent 触发
 * }</pre>
 *
 * <p>设计要点：
 * <ul>
 *   <li>构造 Mod 时只声明、不真正注册，避免在 MC 注册表还没就绪时写入；</li>
 *   <li>{@link #register()} 一次性把自己挂到全局事件总线；</li>
 *   <li>真正写进 MC 注册表的动作，由加载器在 bootstrap 时通过
 *       {@link #setBackend(Consumer)} 注入。</li>
 * </ul>
 *
 * @param <T> 被注册对象的类型（Item / Block / Enchantment ...）
 */
public final class DeferredRegister<T> {

    /**
     * 注册后端：拿到所有 (path, supplier) 后，由加载器真正写入 MC 注册表。
     * 同一个 DeferredRegister 的 backend 在 bootstrap 时被加载器一次性设置。
     */
    private static volatile Consumer<Map<String, Supplier<?>>> globalBackend;

    /**
     * 原生后端：加载器在 bootstrap 时安装，flush() 时把 (this, entries) 交回。
     * 相比 {@link #globalBackend}，这里能拿到 modId / 注册表类型，
     * 从而知道往 BuiltInRegistries.ITEM 还是 BLOCK 写。
     */
    private static volatile BiConsumer<DeferredRegister<?>, Map<String, Supplier<?>>> nativeBackend;

    private final Class<T> type;
    private final String modId;
    private final List<Entry<T>> entries = new ArrayList<>();
    private boolean hooked;
    /**
     * 注册表路径（{@code minecraft:<path>} 去掉命名空间），来自 NeoForge 兼容层桥接时显式带入。
     * 用于 nativeBackend 反射定位 {@code BuiltInRegistries} 字段——因为元素类型简单名（如
     * {@code PoiType}）与注册表路径（{@code point_of_interest_type}）并不总是一致。
     * 缺省 {@code null}，此时 nativeBackend 退化为元素类型简单名小写。
     */
    private String registryPath;

    private DeferredRegister(Class<T> type, String modId) {
        this.type = type;
        this.modId = modId;
    }

    /** 由 NeoForge 兼容层在桥接时调用：显式声明注册表路径。 */
    public DeferredRegister<T> setRegistryPath(String path) {
        this.registryPath = path;
        return this;
    }

    /** 注册表路径；没有显式声明时退化为元素类型简单名小写。 */
    public String registryPath() {
        if (registryPath != null) return registryPath;
        return type.getSimpleName().toLowerCase();
    }

    public static <T> DeferredRegister<T> create(Class<T> type, String modId) {
        // MSR P2 被动捕获：真实加载时记一笔（沙盒模式下由 Sink 另行记录）
        PassiveHooks.api("io.mirage.api.registry.DeferredRegister.create");
        PassiveHooks.deferredRegister();
        return new DeferredRegister<>(type, modId);
    }

    /**
     * 声明一个待注册对象。立即返回一个 {@link RegistryObject}，
     * 其 {@link RegistryObject#get()} 在注册完成前不可用。
     */
    public RegistryObject<T> register(String name, Supplier<T> supplier) {
        PassiveHooks.api("io.mirage.api.registry.DeferredRegister.register");
        // NeoForge 兼容：register(bus) 后仍允许动态追加条目（如 ModBlocks.registerBlockItem
        // 在 ModItems.ITEMS 已挂总线后追加 BlockItem）。flush 在所有 mod 构造完之后统一跑，
        // 此时追加的条目仍会被 flush 看到。
        RegistryKey<T> key = RegistryKey.create(type.getSimpleName().toLowerCase(), modId, name);
        RegistryObject<T> holder = new RegistryObject<>(key);
        entries.add(new Entry<>(name, supplier, holder));
        return holder;
    }

    /**
     * 把本注册器挂到全局事件总线。
     * 在 {@link ModConstructEvent} 阶段调用即可；
     * 真正注册发生在 {@link ModInitEvent}。
     */
    public void register() {
        if (hooked) return;
        hooked = true;
        PassiveHooks.api("io.mirage.api.registry.DeferredRegister.register()");
        // 挂接动作通过全局 backend 完成；此处只把 entries 交给 backend。
        // 加载器在 M5 bootstrap 时会遍历所有 DeferredRegister 并调用 flush()。
        DeferredRegisterHooks.onHook(this);
    }

    /** 由加载器在 bootstrap 阶段调用：把所有条目交给 backend 真正注册。 */
    @SuppressWarnings("unchecked")
    public void flush() {
        Map<String, Supplier<?>> pending = new HashMap<>();
        for (Entry<T> e : entries) pending.put(e.name, e.supplier);

        BiConsumer<DeferredRegister<?>, Map<String, Supplier<?>>> nativeBk = nativeBackend;
        if (nativeBk != null) {
            // 加载器原生后端：知道 modId 和注册表类型，真正写进 MC 注册表。
            nativeBk.accept(this, pending);
        } else {
            Consumer<Map<String, Supplier<?>>> backend = globalBackend;
            if (backend == null) {
                // 没有后端也允许"空跑"——单元测试 / 纯 API 编译场景。
                for (Entry<T> e : entries) {
                    e.holder.set(e.supplier.get());
                }
                return;
            }
            backend.accept(pending);
        }
        // backend 负责在 MC 注册表写入后回调 RegistryObject#set。
        // 这里保留兜底：若 backend 未回填，就用 supplier 的结果回填。
        for (Entry<T> e : entries) {
            if (!e.holder.isPresent()) {
                e.holder.set(e.supplier.get());
            }
        }
    }

    /** 由加载器在启动早期调用：安装全局注册后端。 */
    public static void setBackend(Consumer<Map<String, Supplier<?>>> backend) {
        globalBackend = backend;
    }

    /**
     * 由加载器在启动早期调用：安装"原生"注册后端。
     * 收到 (this, path->supplier) 后，可根据 {@link #modId()} / {@link #type()}
     * 把对象写进对应的 MC 注册表（ITEM / BLOCK ...）。
     */
    public static void setNativeBackend(BiConsumer<DeferredRegister<?>, Map<String, Supplier<?>>> backend) {
        nativeBackend = backend;
    }

    /** 由加载器调用：订阅"哪个 DeferredRegister 被 hook 了"，以便之后统一 flush。 */
    public static void setHookListener(Consumer<DeferredRegister<?>> listener) {
        DeferredRegisterHooks.setHookListener(listener);
    }

    public Class<T> type()  { return type; }
    public String modId()   { return modId; }
    public int size()       { return entries.size(); }

    /** 内部 hook：让 core 能感知"哪些 DeferredRegister 被 hook 了"。 */
    static final class DeferredRegisterHooks {
        private static Consumer<DeferredRegister<?>> hookListener = dr -> {};

        static void onHook(DeferredRegister<?> dr) {
            hookListener.accept(dr);
        }

        public static void setHookListener(Consumer<DeferredRegister<?>> l) {
            hookListener = l;
        }
    }

    private record Entry<T>(String name, Supplier<T> supplier, RegistryObject<T> holder) {}
}
