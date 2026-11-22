package net.neoforged.fml.loading;

import io.mirage.api.version.VersionMasks;
import net.neoforged.api.distmarker.Dist;

/**
 * NeoForge 加载器入口 stub（clean-room，MSR P2）。
 *
 * <p>只实现 mod 真正会调的一小块：{@link #versionInfo()} 与 {@link #getDist()}。mod 用前者判断
 * "我在哪个 MC / NeoForge 版本上跑"，进而决定注册什么、跳过什么。
 *
 * <p>返回的对象是活表（见 {@link VersionInfo}）：取值时按当前 mod 上下文查
 * {@link VersionMasks}，因此同一个进程里不同 mod 可以各自看到自己期望的版本 —— 这正是
 * "版本伪装回放"的语义。
 *
 * <p>已实现端侧查询：{@link #getDist()} 返回加载器在 S6 之前注入到
 * {@link FMLEnvironment} 的真值（客户端运行即 {@code Dist.CLIENT}）。
 *
 * <p>未实现（真实 NeoForge 有、但 MG 侧目前没有消费方）：
 * {@code getGamePath()}、{@code getModsPath()}。
 * 缺了会让调到它们的 mod 抛 {@link NoSuchMethodError} —— 这是**故意暴露**的缺口，
 * 不静默返回假值（缺口清单见 docs/NeoForge兼容层缺口清单.md）。
 */
public final class FMLLoader {

    private static final VersionInfo VERSION_INFO = new VersionInfo();
    private static final FMLLoader CURRENT = new FMLLoader();

    public static VersionInfo versionInfo() {
        return VERSION_INFO;
    }

    /** 真实签名：{@code FMLLoader.getCurrent()}（FerriteCore 用它拿 LoadingModList）。 */
    public static FMLLoader getCurrent() {
        return CURRENT;
    }

    /** 真实签名：{@code FMLLoader.getCurrentOrNull()}（JEI 用）。当前进程恒有实例。 */
    public static FMLLoader getCurrentOrNull() {
        return CURRENT;
    }

    /**
     * 当前端侧。真实签名是实例方法（调用方字节码是 {@code getCurrent() → invokevirtual getDist()}，
     * 见 mezz_config 0.6.11 的 ConfigNeoForgePhysicalSideProvider）。
     *
     * <p>返回加载器注入的端侧真值，不在这里写死 {@code Dist.CLIENT}：
     * {@code NeoCompatBootstrap.init(gameDir, distName)} 在 S6 mod 构造之前把
     * {@code ctx.dist()} 写进 {@link FMLEnvironment}，客户端运行即为 {@code Dist.CLIENT}。
     */
    public Dist getDist() {
        return FMLEnvironment.getDist();
    }

    /** 开发/生产标识；MG 场景恒 false（与 {@link FMLEnvironment#isProduction()} 对齐）。 */
    public boolean isProduction() {
        return false;
    }

    public LoadingModList getLoadingModList() {
        return LoadingModList.get();
    }

    private FMLLoader() {}
}
