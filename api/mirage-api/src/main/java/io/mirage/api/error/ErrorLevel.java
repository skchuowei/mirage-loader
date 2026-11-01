package io.mirage.api.error;

import io.mirage.api.log.MirageLog;

/**
 * MG 的错误分级（MEL, Mirage Error Level）。
 *
 * <p>把"出了问题"分成四档，判据是**用户需不需要管**：
 * <ul>
 *   <li>{@link #FATAL} —— 不可继续。必须让流程停下来（通常是抛
 *       {@link MirageException} 让启动失败），否则后续行为全是错的。</li>
 *   <li>{@link #ERROR} —— 功能受损但流程可继续。被影响的子系统不可用，
 *       其余部分照常跑。</li>
 *   <li>{@link #WARN} —— 已降级，功能还在，但行为与预期有出入。多为
 *       "某个可选能力缺失，走兜底路径"。</li>
 *   <li>{@link #IGNORE} —— 预期内的失败，已被显式兜住，用户无需关心。
 *       记录它是为了"将来出问题时能查到当时发生过什么"，不是为了让人紧张。</li>
 * </ul>
 *
 * <p>与 {@link MirageLog} 的五级日志（TRACE–FATAL）是**两件事**，不要混：
 * 日志级别说的是"这条信息多重要"，MEL 说的是"这件事有多严重"。
 * 一个 ERROR 级的 MEL 记录在默认日志级别下会输出（用户该看到），
 * 而一条 WARN 级的日志在 {@code -Dmirage.log.level=error} 下会被静音（用户不需要）。
 *
 * @see MirageErrors 记录门面
 * @see MirageException 带级别的异常
 */
public enum ErrorLevel {

    /** 不可继续：流程必须停下来，否则后续全是错误行为。 */
    FATAL("MG-FATAL", MirageLog.Level.FATAL),

    /** 功能受损但可继续：受影响的子系统不可用，其余照常。 */
    ERROR("MG-ERROR", MirageLog.Level.ERROR),

    /** 已降级：功能还在，但行为与预期有出入。 */
    WARN("MG-WARN", MirageLog.Level.WARN),

    /** 预期内的失败，已兜住，仅留痕备查。 */
    IGNORE("MG-IGNORE", MirageLog.Level.INFO);

    private final String prefix;
    private final MirageLog.Level logLevel;

    ErrorLevel(String prefix, MirageLog.Level logLevel) {
        this.prefix = prefix;
        this.logLevel = logLevel;
    }

    /**
     * 日志行前缀，如 {@code MG-FATAL}。输出形如
     * {@code [MG-WARN] 某 mod 初始化失败——该 mod 的功能将不可用}，
     * 这样 grep {@code [MG-FATAL]} / {@code [MG-WARN]} / {@code [MG-IGNORE]} 就能分档捞。
     */
    public String prefix() {
        return prefix;
    }

    /**
     * 映射到日志门控级别。IGNORE 映射到 INFO 而非 DEBUG——
     * 它虽然"无需关心"，但仍应出现在默认日志里（否则事后无从查起）；
     * 真正想静音时用户可以整体调 {@code -Dmirage.log.level}。
     */
    public MirageLog.Level logLevel() {
        return logLevel;
    }
}
