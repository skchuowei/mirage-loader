package net.neoforged.fml.loading;

import io.mirage.api.version.VersionMasks;

/**
 * NeoForge 加载器入口 stub（clean-room，MSR P2）。
 *
 * <p>只实现 mod 真正会调的一小块：{@link #versionInfo()}。mod 用它来判断
 * "我在哪个 MC / NeoForge 版本上跑"，进而决定注册什么、跳过什么。
 *
 * <p>返回的对象是活表（见 {@link VersionInfo}）：取值时按当前 mod 上下文查
 * {@link VersionMasks}，因此同一个进程里不同 mod 可以各自看到自己期望的版本 —— 这正是
 * "版本伪装回放"的语义。
 *
 * <p>未实现（真实 NeoForge 有、但 MG 侧目前没有消费方）：{@code getDist()}、
 * {@code getGamePath()}、{@code getModsPath()}、{@code isProduction()}。
 * 缺了会让调到它们的 mod 抛 {@link NoSuchMethodError} —— 这是**故意暴露**的缺口，
 * 不静默返回假值（缺口清单见 docs/NeoForge兼容层缺口清单.md）。
 */
public final class FMLLoader {

    private static final VersionInfo VERSION_INFO = new VersionInfo();

    public static VersionInfo versionInfo() {
        return VERSION_INFO;
    }

    private FMLLoader() {}
}
