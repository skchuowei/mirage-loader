package net.neoforged.neoforge.common;

import io.mirage.neoforge.runtime.NeoForgeEventBridge;
import net.neoforged.bus.api.IEventBus;

/**
 * 运行时桥接 stub：NeoForge 的公开静态入口（clean-room）。
 *
 * <p>真实 mod 的常见写法是 {@code NeoForge.EVENT_BUS.addListener(...)}（JEI 就这么用）。
 * 本项目当前是单总线模型，因此这里直接返回 {@code NeoForgeEventBridge.bus()}，
 * 与 {@code FMLJavaModLoadingContext.get().getModEventBus()} 是同一个实例。
 */
public final class NeoForge {

    public static final IEventBus EVENT_BUS = NeoForgeEventBridge.bus();

    private NeoForge() {
    }
}
