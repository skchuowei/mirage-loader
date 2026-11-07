package io.mirage.ls.stage;

import io.mirage.api.log.MirageLog;

import java.lang.reflect.Method;

/**
 * NeoForge 生命周期桥接 + mod 实例构造（保序迁移自拆分前 Launcher 的私有方法）。
 *
 * <p>反射而非硬依赖：launch/LS 侧不 import {@code net.neoforged.*} 实现，
 * stub 由 MirageClassLoader 与 mod 同 loader 加载（用 SystemClassLoader 会拿到
 * 另一份 Class 对象，post 不到 mod 注册的监听器）。
 */
public final class NeoLifecycle {

    private NeoLifecycle() {}

    /** 向 NeoForge mod bus post 一个生命周期事件；无 NeoForge mod / stub 缺失时仅打印，不影响主流程。 */
    public static void postNeoLifecycle(String eventClassName, ClassLoader neoLoader) {
        try {
            ClassLoader cl = neoLoader;
            Object ctx = Class.forName("net.neoforged.fml.javafmlmod.FMLJavaModLoadingContext", true, cl)
                    .getMethod("get").invoke(null);
            Object bus = ctx.getClass().getMethod("getModEventBus").invoke(ctx);
            Object event = Class.forName(eventClassName, true, cl).getDeclaredConstructor().newInstance();
            bus.getClass().getMethod("post", Object.class).invoke(bus, event);
        } catch (Throwable t) {
            MirageLog.MIRAGE.warn("post NeoForge 生命周期事件 " + eventClassName + " 失败（该事件的监听器不会触发）", t);
        }
    }

    /**
     * 实例化 mod 主类：按 NeoForge 支持的构造签名选择（见 {@link ModConstructorPicker}）。
     *
     * <p>支持 {@code ()} / {@code (IEventBus)} / {@code (IEventBus, Dist)} /
     * {@code (IEventBus, ModContainer)} / {@code (IEventBus, ModContainer, Dist)}。
     * {@code dist} / {@code container} 由 S6 从 MCL 侧取到（见 {@link NeoCompatBridge}）；
     * 任一为 null 时选择器会跳过对应签名，不会把 null 传进 mod 构造器。
     */
    public static Object constructModInstance(Class<?> cls, boolean fromNeoForge, Object dist, Object container)
            throws Exception {
        Object modBus = null;
        if (fromNeoForge) {
            ClassLoader cl = cls.getClassLoader();
            Class<?> busCls = Class.forName("net.neoforged.bus.api.IEventBus", false, cl);
            Object ctx = Class.forName("net.neoforged.fml.javafmlmod.FMLJavaModLoadingContext", true, cl)
                    .getMethod("get").invoke(null);
            modBus = busCls.cast(ctx.getClass().getMethod("getModEventBus").invoke(ctx));
        }
        ModConstructorPicker.Match match = ModConstructorPicker.pick(cls, modBus, dist, container);
        if (match == null) {
            throw new NoSuchMethodException("找不到可用构造器: " + cls.getName()
                    + "（支持 无参 / (IEventBus) / (IEventBus, Dist) / (IEventBus, ModContainer)"
                    + " / (IEventBus, ModContainer, Dist)）");
        }
        MirageLog.MIRAGE.debug(cls.getName() + " 使用构造签名 " + match.signature());
        return match.constructor.newInstance(match.args);
    }

    /**
     * 调用一个 mod 入口类的初始化方法。
     * 优先按 Fabric 接口（{@link net.fabricmc.api.ModInitializer}）强转调用；
     * 不实现该接口时回退到反射找 onInitialize()，兼容现有 mirage 原生 mod。
     */
    public static void callOnInitialize(Object instance) throws Exception {
        if (instance instanceof net.fabricmc.api.ModInitializer mi) {
            mi.onInitialize();
            return;
        }
        Method m = findMethod(instance.getClass(), "onInitialize");
        if (m != null) {
            m.invoke(instance);
        } else {
            MirageLog.MIRAGE.warn("! " + instance.getClass().getName()
                    + " 没有实现 ModInitializer，也没有 onInitialize()，跳过");
        }
    }

    public static Method findMethod(Class<?> cls, String name) {
        try {
            Method m = cls.getMethod(name);
            m.setAccessible(true);
            return m;
        } catch (NoSuchMethodException e) {
            return null;
        }
    }
}
