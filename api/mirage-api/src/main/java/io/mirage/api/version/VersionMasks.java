package io.mirage.api.version;

import io.mirage.api.log.MirageLog;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 按 mod 上报版本的伪装表（MSR P2「版本伪装回放」的运行时载体）。
 *
 * <p>设计要点：NeoForge mod 常在自己的构造函数里查加载器 / MC 版本，然后按版本走不同分支
 * （{@code if (neoForgeVersion.startsWith("26.1")) ...}）。MG 不是真 NeoForge，
 * 直接报"当前版本"会把 mod 逼到不匹配的分支，甚至被 mod 主动拒绝加载。
 * 因此：**按 mod 上下文报告它自己声明过期望的那个版本**。
 *
 * <p>数据来源是 MID（`.mid-cache` 里该 mod 的 `detected.neoForgeVersionRange` /
 * `mcVersionRange`），由 loader 侧 {@code LoaderVersionMasquerade} 在启动早期灌进来。
 * 本类只负责"当前线程正在初始化哪个 mod → 它该看到什么版本"的查表，
 * 不含任何版本区间解析逻辑（那是 loader 的事）。
 *
 * <p><b>为什么用 ThreadLocal 而不是全局变量</b>：S6 是串行的，但 mod 构造里可能起线程、
 * 也可能有 mixin 回调在别的线程跑；按线程隔离可以把串号风险限制在"跨线程查询退回基线"，
 * 而不是"整个进程只认最后一个 mod"。
 *
 * <p>本类在 {@code api} 模块，被 compat 的 {@code net.neoforged.fml.loading.FMLLoader} stub 直接引用。
 */
public final class VersionMasks {

    /** 一个 mod 看到的 (MC 版本, NeoForge 版本)。 */
    public record Mask(String mcVersion, String neoForgeVersion) {
        @Override
        public String toString() {
            return "mc=" + mcVersion + ", neoforge=" + neoForgeVersion;
        }
    }

    /** 进程基线（当前真实 MC 版本 / MG 兼容层版本），由 loader 启动早期设置。 */
    private static volatile Mask fallback = new Mask("0.0.0", "0.0.0");

    private static final Map<String, Mask> BY_MOD = new ConcurrentHashMap<>();

    private static final ThreadLocal<String> ACTIVE_MOD = new ThreadLocal<>();

    /** 基线尚未安装就被查询：只在真实启动路径上有意义，测试里常直接 put。 */
    private static volatile boolean warnedUnset;

    // ------------------------------------------------------------------ 装配

    /** 设置基线（真实版本）。 */
    public static void setFallback(Mask mask) {
        if (mask != null) fallback = mask;
    }

    public static Mask fallback() {
        return fallback;
    }

    /** 登记某个 mod 的伪装值。 */
    public static void put(String modId, Mask mask) {
        if (modId == null || modId.isEmpty() || mask == null) return;
        BY_MOD.put(modId, mask);
    }

    /** 清空整张表（新一轮启动 / 测试隔离）。 */
    public static void clear() {
        BY_MOD.clear();
    }

    public static int size() {
        return BY_MOD.size();
    }

    /** 某 mod 的伪装值；未登记返回 null。 */
    public static Mask get(String modId) {
        return modId == null ? null : BY_MOD.get(modId);
    }

    // ------------------------------------------------------------- 当前上下文

    /** 进入某个 mod 的初始化上下文（S6 在构造入口类前后调用）。 */
    public static void enter(String modId) {
        if (modId == null) ACTIVE_MOD.remove();
        else ACTIVE_MOD.set(modId);
    }

    /** 离开上下文，退回基线。 */
    public static void leave() {
        ACTIVE_MOD.remove();
    }

    /** 当前线程活动 modId；无上下文返回 null。 */
    public static String activeMod() {
        return ACTIVE_MOD.get();
    }

    /**
     * 当前上下文下应当上报的版本。
     * 优先活动 mod 的伪装值；无上下文 / 未登记 → 基线。
     */
    public static Mask current() {
        String mod = ACTIVE_MOD.get();
        if (mod != null) {
            Mask m = BY_MOD.get(mod);
            if (m != null) return m;
        }
        Mask base = fallback;
        if (!warnedUnset && "0.0.0".equals(base.mcVersion())) {
            warnedUnset = true;
            MirageLog.of("Mirage:Version").debug("版本基线尚未安装（VersionMasks.setFallback 未被调用），先报 0.0.0");
        }
        return base;
    }

    public static String mcVersion() {
        return current().mcVersion();
    }

    public static String neoForgeVersion() {
        return current().neoForgeVersion();
    }

    private VersionMasks() {}
}
