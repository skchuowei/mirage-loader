package io.mirage.api;

import io.mirage.api.bus.EventBusHandle;
import io.mirage.api.env.Dist;
import io.mirage.api.log.ModLogger;
import io.mirage.api.mod.ModMetadata;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.ServiceLoader;

/**
 * Mirage 公共门面 —— Mod 开发者与加载器交互的唯一入口。
 *
 * <p>所有方法都是静态方法，背后转发到当前 {@link MirageProvider}。
 * 在单元测试 / 未走加载器直接 {@code main()} 的场景下，provider 尚未就绪，
 * 调用会抛出 {@link IllegalStateException}，并提示开发者"你正在脱离加载器运行"。
 *
 * <p>典型用法：
 * <pre>{@code
 * if (Mirage.isModLoaded("jei")) { ... }
 * ModLogger log = Mirage.getLogger("mymod");
 * Mirage.getEventBus().register(this);
 * Path configDir = Mirage.getGameDir().resolve("config");
 * }</pre>
 */
public final class Mirage {

    private static volatile MirageProvider cached;

    private Mirage() {}

    /**
     * 由加载器在启动早期调用，注入运行时实现。
     *
     * <p>Mod 代码不应调用本方法。重复调用会抛 {@link IllegalStateException}，
     * 以避免多套 Provider 互相覆盖。
     */
    public static void bootstrap(MirageProvider provider) {
        if (cached != null) {
            throw new IllegalStateException("[Mirage] Provider 已初始化，禁止重复 bootstrap");
        }
        cached = Objects.requireNonNull(provider, "provider");
    }

    /** 拿到当前运行时 provider；未初始化时抛友好异常。 */
    public static MirageProvider provider() {
        MirageProvider p = cached;
        if (p != null) return p;
        synchronized (Mirage.class) {
            if (cached != null) return cached;
            ServiceLoader<MirageProvider> loader =
                    ServiceLoader.load(MirageProvider.class);
            for (MirageProvider impl : loader) {
                cached = impl;
                return cached;
            }
            throw new IllegalStateException(
                    "[Mirage] 运行时未初始化：未找到 MirageProvider 实现。\n" +
                    "你正在脱离 Mirage Loader 直接运行 Mod 主类吗？\n" +
                    "请通过 '26.2-mirage' 版本启动游戏后再调用 Mirage.* API。");
        }
    }

    // ---- 元信息 ----

    /** Mirage Loader 自身版本。 */
    public static String getLoaderVersion() {
        return provider().getLoaderVersion();
    }

    /** 当前物理端。 */
    public static Dist getDist() {
        return provider().getDist();
    }

    // ---- Mod 探测 ----

    /** 已加载的全部 Mod（按依赖拓扑顺序）。 */
    public static List<ModMetadata> getMods() {
        return provider().getMods();
    }

    /** 按 id 查 Mod。 */
    public static Optional<ModMetadata> getMod(String modId) {
        return provider().getMod(modId);
    }

    /** 某 Mod 是否已加载。 */
    public static boolean isModLoaded(String modId) {
        return provider().getMod(modId).isPresent();
    }

    // ---- 路径 ----

    /** 游戏根目录。 */
    public static Path getGameDir() {
        return provider().getGameDir();
    }

    /** mods 目录。 */
    public static Path getModsDir() {
        return provider().getModsDir();
    }

    // ---- 事件总线 & 日志 ----

    /** 全局事件总线。 */
    public static EventBusHandle getEventBus() {
        return provider().getEventBus();
    }

    /** 为指定 modId 取日志门面。 */
    public static ModLogger getLogger(String modId) {
        return provider().createLogger(modId);
    }
}
