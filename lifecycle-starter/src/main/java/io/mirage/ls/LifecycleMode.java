package io.mirage.ls;

/**
 * 启动模式（LS-Interfaces.md §1，2026-10-24 冻结）。
 *
 * <ul>
 *   <li>{@link #NATIVE}：只加载 mirage 原生 mod，跳过 AG 转译；</li>
 *   <li>{@link #TRANSPILE}：非 mirage 原生 mod 先经 AG 转译为 MID 再加载（默认，兼容现有行为）；</li>
 *   <li>{@link #RECORD}：启用 MSR 沙盒录制路径（phase 1 仅透传给现有 --sandbox-record 机制）。</li>
 * </ul>
 */
public enum LifecycleMode {
    NATIVE,
    TRANSPILE,
    RECORD;

    private static final String FLAG_PREFIX = "--lifecycle-mode=";

    /**
     * 从命令行解析 --lifecycle-mode；缺省 TRANSPILE。
     * 非法值 = FATAL：mode 决定整条启动路径，错拼必须响亮失败，不静默归默认。
     */
    public static LifecycleMode parse(String[] args) {
        for (String a : args) {
            if (a.startsWith(FLAG_PREFIX)) {
                String v = a.substring(FLAG_PREFIX.length()).trim().toUpperCase(java.util.Locale.ROOT);
                try {
                    return valueOf(v);
                } catch (IllegalArgumentException e) {
                    System.err.println("[LS] FATAL 非法 --lifecycle-mode 值: " + v
                            + "（合法值: native | transpile | record）");
                    System.exit(1);
                }
            }
        }
        return TRANSPILE;
    }

    /** 日志用小写名（[LS] mode= 关键字格式冻结，不得变）。 */
    public String logName() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}
