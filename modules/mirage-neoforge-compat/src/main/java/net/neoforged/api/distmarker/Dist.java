package net.neoforged.api.distmarker;

/**
 * 运行时桥接 stub：NeoForge {@code Dist}（clean-room，仅对齐签名与语义）。
 *
 * <p>真实 mod 的主类构造器常见 {@code (IEventBus, Dist)}；缺失会在链接期直接
 * {@code NoClassDefFoundError}。取值由加载器在 S6 之前注入（见
 * {@code io.mirage.neoforge.runtime.NeoCompatBootstrap}），不要在这里写死。
 */
public enum Dist {

    CLIENT,
    DEDICATED_SERVER;

    public boolean isClient() {
        return this == CLIENT;
    }

    public boolean isDedicatedServer() {
        return this == DEDICATED_SERVER;
    }
}
