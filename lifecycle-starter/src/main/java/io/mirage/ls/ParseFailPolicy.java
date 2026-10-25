package io.mirage.ls;

/**
 * 解析失败策略（LS-Interfaces.md v0.2 §1 附属枚举；由 A1/MIS 冻结，禁止各模块自定义）。
 *
 * <p>适用场景：C3（及 C4 显式声明）下 LS 判定 mod「无法完全解析」时的行为。
 * 当前已接线的判定信号：<b>主类定位失败</b>（无 {@code @Mod} 注解、无可用入口——
 * {@code manifest.mainClass} 与 fabric entrypoints 均为空）。其余判定标准
 * （MBD 硬解码覆盖率、MID 必填校验、依赖满足）随 AG 解耦（phase 3）接入，
 * 阈值 {@code minCoverage} 已可由 C4 配置预置。
 *
 * <table>
 *   <caption>策略表（计划文档 2026-10-24_lifecycle-starter-LS.md 冻结）</caption>
 *   <tr><th>策略</th><th>行为</th><th>备注</th></tr>
 *   <tr><td>{@link #SKIP}</td><td>跳过该 mod，继续启动，记 WARN</td>
 *       <td>C1/C2 缺省；{@code skip} 显式开启才用于 C3——违反原则 3「禁止静默失败」，故必须带 WARN</td></tr>
 *   <tr><td>{@link #CRASH}</td><td>立即崩溃（MEL FATAL），输出缺失项清单</td>
 *       <td>无人值守（无控制台）时 confirm 自动降级为 crash</td></tr>
 *   <tr><td>{@link #CONFIRM}</td><td>控制台列出无法解析的 mod + 原因，等待用户确认
 *       （Y 继续 / S 跳过这些 mod / N 中止）</td><td>C3 缺省</td></tr>
 * </table>
 */
public enum ParseFailPolicy {
    SKIP,
    CRASH,
    CONFIRM;

    private static final String FLAG_PREFIX = "--lifecycle-parse-fail=";

    /** 命令行覆盖（调试用）：--lifecycle-parse-fail=skip|crash|confirm；缺省 null 表示按周期模板。 */
    public static ParseFailPolicy parseOverride(String[] args) {
        for (String a : args) {
            if (a.startsWith(FLAG_PREFIX)) {
                String v = a.substring(FLAG_PREFIX.length()).trim().toLowerCase(java.util.Locale.ROOT);
                try {
                    return valueOf(v.toUpperCase(java.util.Locale.ROOT));
                } catch (IllegalArgumentException e) {
                    System.err.println("[LS] FATAL 非法 " + FLAG_PREFIX + "值: " + v
                            + "（合法值: skip | crash | confirm）");
                    System.exit(1);
                }
            }
        }
        return null;
    }

    public static ParseFailPolicy parse(String v) {
        try {
            return valueOf(v.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException e) {
            return null;
        }
    }

    /** 日志用小写名。 */
    public String logName() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}
