package net.neoforged.fml.loading;

/**
 * 版本信息（NeoForge {@code FMLLoader.versionInfo()} 的 clean-room stub，MSR P2「版本伪装回放」）。
 *
 * <p>真实 NeoForge 的 {@code VersionInfo} 是构建时生成的常量对象；MG 没有真 NeoForge，
 * 所以这里是一只"活表"：任何取值都实时问 {@link io.mirage.api.version.VersionMasks}，
 * 而后者按**当前正在初始化的 mod** 返回该 mod 自己声明过的版本。
 *
 * <p>这样 mod 里常见的写法
 * <pre>{@code
 * String neo = FMLLoader.versionInfo().neoForgeVersion();
 * if (!neo.startsWith("26.1")) { ... }
 * }</pre>
 * 会走到它自己期望的那条分支，而不是被"当前版本"逼到未验证路径上。
 *
 * <p>字段语义对齐 NeoForge，但 MG 只有"一个兼容层版本"，
 * 因此 {@link #fmlVersion()} 与 {@link #mavenVersion()} 都退化为 NeoForge 版本串，
 * {@link #buildNumber()} 恒为 0 —— 不猜不存在的构建号。
 */
public final class VersionInfo {

    public String mcVersion() {
        return io.mirage.api.version.VersionMasks.mcVersion();
    }

    /**
     * 主版本（去掉补丁位）：{@code 26.2.1 -> 26.2}，{@code 26.2 -> 26.2}。
     * NeoForge 用它做粗粒度分支（如 {@code "1.20"}）。
     */
    public String majorMcVersion() {
        String v = mcVersion();
        int first = v.indexOf('.');
        if (first < 0) return v;
        int second = v.indexOf('.', first + 1);
        return second < 0 ? v : v.substring(0, second);
    }

    public String neoForgeVersion() {
        return io.mirage.api.version.VersionMasks.neoForgeVersion();
    }

    /** MG 没有独立的 FML 版本线，退化为 NeoForge 版本。 */
    public String fmlVersion() {
        return neoForgeVersion();
    }

    /** MG 不是 Maven 发布物，退化为 NeoForge 版本串（mod 一般只用于打日志）。 */
    public String mavenVersion() {
        return neoForgeVersion();
    }

    /** 无构建号可言：恒 0，不编造。 */
    public int buildNumber() {
        return 0;
    }

    @Override
    public String toString() {
        return "VersionInfo[mc=" + mcVersion() + ", neoForge=" + neoForgeVersion() + "]";
    }
}
