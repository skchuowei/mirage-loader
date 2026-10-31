package io.mirage.ls;

import io.mirage.api.log.MirageLog;

/**
 * LS 启动周期（LS-Interfaces.md v0.2 §1，2026-10-25 由用户拍板定四条，取代 v0.1 的
 * NATIVE/TRANSPILE/RECORD 三模式）。
 *
 * <ul>
 *   <li>{@link #C1} 直接启动（MD 直启）：不走 AG、不走 MRS，只消费 mirage 原生描述符；</li>
 *   <li>{@link #C2} 游戏 mod 转译（硬解码 + MRS 含录制）：非原生 mod 走 AG 硬解码 +
 *       沙盒录制成 MID 再加载；录制失败降级 WARN 不阻断；<b>缺省周期</b>（兼容现有行为）；</li>
 *   <li>{@link #C3} 游戏 mod 转译（硬解码 + MRS 不含录制）：不跑沙盒构造；解析失败按
 *       {@link ParseFailPolicy} 处理（缺省 confirm）；</li>
 *   <li>{@link #CUSTOM} 自定义：按 {@code mirage.ls.json}（{@link LsConfig}）配置。</li>
 * </ul>
 *
 * <p>解析规则：命令行 {@code --lifecycle-mode=C1|C2|C3|custom}，大小写不敏感，缺省 {@link #C2}。
 * 兼容别名：{@code native→C1}、{@code transpile→C2}、{@code record→C2}（v0.1 三模式迁移用）。
 * <b>非法值 = FATAL</b>（打印合法取值后 exit 1）——周期决定整条启动路径，错拼必须响亮失败，
 * 不静默归默认（设计原则 3）。
 */
public enum LifecycleMode {
    C1,
    C2,
    C3,
    CUSTOM;

    private static final String FLAG_PREFIX = "--lifecycle-mode=";

    /**
     * 从命令行解析 --lifecycle-mode；缺省 C2。
     * 非法值 = FATAL：周期决定整条启动路径，错拼必须响亮失败，不静默归默认。
     */
    public static LifecycleMode parse(String[] args) {
        for (String a : args) {
            if (a.startsWith(FLAG_PREFIX)) {
                String v = a.substring(FLAG_PREFIX.length()).trim().toLowerCase(java.util.Locale.ROOT);
                LifecycleMode m = alias(v);
                if (m == null) {
                    MirageLog.LS.fatal("非法 --lifecycle-mode 值: " + v
                            + "（合法值: C1 | C2 | C3 | custom；兼容别名: native | transpile | record）");
                    System.exit(1);
                }
                return m;
            }
        }
        return C2;
    }

    /** v0.1 别名迁移 + C 编号；无法识别返回 null（调用方 FATAL）。 */
    private static LifecycleMode alias(String v) {
        return switch (v) {
            case "c1", "native" -> C1;
            case "c2", "transpile", "record" -> C2;
            case "c3" -> C3;
            case "c4", "custom" -> CUSTOM;
            default -> null;
        };
    }

    /** 日志用小写名（[LS] mode= 关键字格式冻结，不得变）。 */
    public String logName() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }

    /** 人类可读的周期描述（[LS] cycle= 行用）。 */
    public String describe() {
        return switch (this) {
            case C1 -> "C1 直接启动（MD 直启：无 AG 转译、无 MRS 录制，只消费 mirage 原生描述符）";
            case C2 -> "C2 游戏 mod 转译（硬解码 + MRS 含录制，录制失败降级 WARN 不阻断）";
            case C3 -> "C3 游戏 mod 转译（硬解码 + MRS 不含录制，解析失败按 ParseFailPolicy 处理）";
            case CUSTOM -> "C4 自定义（按 mirage.ls.json 配置周期模板/阶段开关/失败策略/录制模式）";
        };
    }

    /** 该周期模板下 MRS 沙盒录制的缺省开关（C4 由配置覆盖）。 */
    public String defaultSandboxMode() {
        return switch (this) {
            case C1, C3 -> "off";
            case C2, CUSTOM -> "auto";
        };
    }

    /** 该周期模板下解析失败的缺省策略（C4 由配置覆盖）。 */
    public ParseFailPolicy defaultFailPolicy() {
        return switch (this) {
            case C3 -> ParseFailPolicy.CONFIRM;
            case C1, C2, CUSTOM -> ParseFailPolicy.SKIP;
        };
    }
}
