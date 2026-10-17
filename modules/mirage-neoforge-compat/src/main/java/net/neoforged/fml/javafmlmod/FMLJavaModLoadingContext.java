package net.neoforged.fml.javafmlmod;

import io.mirage.neoforge.runtime.NeoEventBusImpl;
import net.neoforged.bus.api.IEventBus;

/**
 * 运行时桥接 stub：{@code FMLJavaModLoadingContext}。
 *
 * <p>NeoForge mod 主类构造函数里最常见的两行：
 * <pre>{@code
 * FMLJavaModLoadingContext.get().getModEventBus().register(this);
 * }</pre>
 *
 * <p>真实 NeoForge 中它是 per-mod 的加载期上下文。第一版简化为全局单例
 * （mod 构造都发生在 Launcher 主线程的同一窗口内，单例足够）；
 * 每个 mod 拿到同一个 mod bus，{@code register} 行为正确，不静默失败。
 */
public final class FMLJavaModLoadingContext {

    private static final NeoEventBusImpl MOD_BUS = new NeoEventBusImpl();
    private static final FMLJavaModLoadingContext INSTANCE = new FMLJavaModLoadingContext();

    public static FMLJavaModLoadingContext get() {
        return INSTANCE;
    }

    public IEventBus getModEventBus() {
        return MOD_BUS;
    }

    private FMLJavaModLoadingContext() {}
}
