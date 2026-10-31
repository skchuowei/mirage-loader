package io.mirage.ls;

import io.mirage.api.log.MirageLog;

import io.mirage.api.env.Dist;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 阶段间共享上下文（LS-Interfaces.md v0.2 §4）。
 *
 * <p>阶段之间只通过本类传状态；properties key 用阶段 id 前缀约定
 * （如 "S1.mods"、"S3.classLoader"），正式 key 清单见 LS-Interfaces.md 附录 A，
 * 未登记的 key 视为阶段私有，禁止跨阶段引用。
 *
 * <p>v0.2 扩展（phase 2）：启动装配期解析的全部环境量（物理端 / 沙盒录制开关 /
 * MID 缓存开关 / 解析失败策略）统一由本类持有并暴露，阶段类不再各自解析参数——
 * 「LS 决定当前该干什么」的决策集中在这一层。
 */
public final class LifecycleContext {

    private final String[] args;
    private final LifecycleMode mode;
    private final LsConfig config;        // C4 配置；非 CUSTOM 为 null
    private final Path gameDir;
    private final Dist dist;
    private final String sandboxMode;     // off/auto/force（已按周期+配置+flag 解析）
    private final boolean midCacheForceRegen;
    private final boolean midCacheVerbose;
    private final ParseFailPolicy failPolicy;
    private final Map<String, Object> properties = new ConcurrentHashMap<>();
    private final Set<String> skippedMods = java.util.Collections.newSetFromMap(new ConcurrentHashMap<>());

    public LifecycleContext(String[] args, LifecycleMode mode, LsConfig config) {
        this.args = args;
        this.mode = mode;
        this.config = config;
        this.gameDir = resolveGameDir(args);
        this.dist = detectDist();
        this.sandboxMode = resolveSandboxMode(args, mode, config);
        this.midCacheForceRegen = hasFlag(args, "--mid-cache-force-regen");
        this.midCacheVerbose = hasFlag(args, "--mid-cache-verbose");
        this.failPolicy = resolveFailPolicy(args, mode, config);
    }

    public String[] args() {
        return args;
    }

    public LifecycleMode mode() {
        return mode;
    }

    /** C4 自定义配置；非 CUSTOM 周期为 null。 */
    public LsConfig config() {
        return config;
    }

    /** 与现有 Launcher.parseGameDir 一致：--gameDir &lt;path&gt;，缺省当前目录。 */
    public Path gameDir() {
        return gameDir;
    }

    /**
     * P1-7 物理端判定（自 Launcher.detectDist 迁入）：-Dmirage.dist / MIRAGE_DIST，
     * 缺省 CLIENT（PCL 场景恒客户端）；无法识别时按 CLIENT 处理并告警。
     */
    public Dist dist() {
        return dist;
    }

    /** 沙盒录制开关 off/auto/force（优先级：flag &gt; C4 配置 &gt; 周期模板缺省）。 */
    public String sandboxMode() {
        return sandboxMode;
    }

    public boolean midCacheForceRegen() {
        return midCacheForceRegen;
    }

    public boolean midCacheVerbose() {
        return midCacheVerbose;
    }

    /** 已解析的解析失败策略（无人值守时 confirm 自动降级 crash，见解析方法）。 */
    public ParseFailPolicy failPolicy() {
        return failPolicy;
    }

    /**
     * S2 按 ParseFailPolicy 判定为「跳过」的 modId 集合；S6 初始化循环据此跳过。
     * 缺省（C1/C2）为空——不改变现有启动行为。
     */
    public Set<String> skippedMods() {
        return skippedMods;
    }

    public Map<String, Object> properties() {
        return properties;
    }

    // ------------------------------------------------------------------
    // 启动装配期解析（LS-Interfaces §1 / §4；决策集中在 LS 侧）
    // ------------------------------------------------------------------

    public static Path resolveGameDir(String[] args) {
        for (int i = 0; i < args.length - 1; i++) {
            if ("--gameDir".equals(args[i])) {
                return Paths.get(args[i + 1]);
            }
        }
        return Paths.get(".").toAbsolutePath();
    }

    private static Dist detectDist() {
        String v = System.getProperty("mirage.dist");
        if (v == null || v.isEmpty()) v = System.getenv("MIRAGE_DIST");
        if (v != null && !v.isEmpty()) {
            if (v.equalsIgnoreCase("server") || v.equalsIgnoreCase("dedicated")) return Dist.DEDICATED_SERVER;
            if (v.equalsIgnoreCase("client")) return Dist.CLIENT;
            MirageLog.MIRAGE.warn("无法识别的 mirage.dist=\"" + v + "\"（合法值 client/server），按 CLIENT 处理");
        }
        return Dist.CLIENT;
    }

    /**
     * 沙盒录制开关解析：命令行 --sandbox-record=off|auto|force（非法值归 auto，
     * 与既有 Launcher.parseSandboxMode 策略一致）&gt; C4 配置 record.mode &gt; 周期模板缺省
     * （C1/C3=off，C2=auto）。
     */
    private static String resolveSandboxMode(String[] args, LifecycleMode mode, LsConfig config) {
        String flag = null;
        for (String a : args) {
            if (a.startsWith("--sandbox-record=")) {
                String v = a.substring("--sandbox-record=".length()).trim().toLowerCase(Locale.ROOT);
                if (v.equals("off") || v.equals("auto") || v.equals("force")) flag = v;
            }
        }
        if (config != null && config.recordMode() != null) return config.recordMode();
        if (flag != null) return flag;
        return mode.defaultSandboxMode();
    }

    /**
     * 解析失败策略解析：CLI --lifecycle-parse-fail 覆盖 &gt; C4 配置 parse.failPolicy &gt;
     * 周期模板缺省（C3=confirm，其余=skip）。无人值守（无控制台）时 confirm 自动降级
     * crash——交互式等待在无人值守环境会无限挂起。
     */
    private static ParseFailPolicy resolveFailPolicy(String[] args, LifecycleMode mode, LsConfig config) {
        ParseFailPolicy policy = ParseFailPolicy.parseOverride(args);
        if (policy == null && config != null) policy = config.failPolicy();
        if (policy == null) policy = mode.defaultFailPolicy();
        if (policy == ParseFailPolicy.CONFIRM && System.console() == null) {
            MirageLog.LS.warn("无人值守环境（无控制台），ParseFailPolicy=confirm 自动降级为 crash");
            policy = ParseFailPolicy.CRASH;
        }
        return policy;
    }

    private static boolean hasFlag(String[] args, String name) {
        for (String a : args) if (name.equals(a)) return true;
        return false;
    }

    /** 调试快照（S5 检查点 / 启动摘要用）。 */
    public Map<String, String> summary() {
        Map<String, String> m = new HashMap<>();
        m.put("mode", mode.logName());
        m.put("gameDir", gameDir.toString());
        m.put("dist", dist.name());
        m.put("sandboxRecord", sandboxMode);
        m.put("failPolicy", failPolicy.logName());
        if (config != null) m.put("config", config.source().toString());
        return m;
    }
}
