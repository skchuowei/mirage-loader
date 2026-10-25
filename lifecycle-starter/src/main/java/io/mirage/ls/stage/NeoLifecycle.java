package io.mirage.ls.stage;

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
            System.err.println("[Mirage] post NeoForge 生命周期事件 " + eventClassName + " 失败: " + t);
        }
    }

    /**
     * 实例化 mod 主类：优先无参构造；NeoForge mod 无可用无参构造时，
     * 回退到 {@code public MyMod(net.neoforged.bus.api.IEventBus modBus)} 注入式构造
     * （NeoForge 4.x 标准写法）。
     */
    public static Object constructModInstance(Class<?> cls, boolean fromNeoForge) throws Exception {
        try {
            return cls.getDeclaredConstructor().newInstance();
        } catch (NoSuchMethodException nsme) {
            if (!fromNeoForge) throw nsme;
            ClassLoader cl = cls.getClassLoader();
            Class<?> busCls = Class.forName("net.neoforged.bus.api.IEventBus", false, cl);
            Object ctx = Class.forName("net.neoforged.fml.javafmlmod.FMLJavaModLoadingContext", true, cl)
                    .getMethod("get").invoke(null);
            Object modBus = busCls.cast(ctx.getClass().getMethod("getModEventBus").invoke(ctx));
            System.out.println("[Mirage] " + cls.getName() + " 使用 IEventBus 注入式构造");
            return cls.getConstructor(busCls).newInstance(modBus);
        }
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
            System.out.println("[Mirage]   ! " + instance.getClass().getName()
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
