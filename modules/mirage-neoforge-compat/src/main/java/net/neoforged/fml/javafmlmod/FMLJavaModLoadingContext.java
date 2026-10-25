package net.neoforged.fml.javafmlmod;

import io.mirage.neoforge.runtime.NeoEventBusImpl;
import io.mirage.neoforge.runtime.NeoForgeEventBridge;
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

    /** 与游戏事件派发共用同一条总线，保证 mod 注册的监听器能收到运行时事件。 */
    private static final NeoEventBusImpl MOD_BUS = NeoForgeEventBridge.bus();
    private static final FMLJavaModLoadingContext INSTANCE = new FMLJavaModLoadingContext();

    public static FMLJavaModLoadingContext get() {
        return INSTANCE;
    }

    public IEventBus getModEventBus() {
        return MOD_BUS;
    }

    private FMLJavaModLoadingContext() {}
}
