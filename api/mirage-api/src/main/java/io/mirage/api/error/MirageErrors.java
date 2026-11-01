package io.mirage.api.error;

import io.mirage.api.log.MirageLog;

import java.io.PrintStream;

/**
 * MG 错误记录门面（MEL）。
 *
 * <p>输出格式固定为 <b>{@code [MG-<级别>] <说明>}</b>，例如：
 * <pre>
 * [MG-FATAL] 关键织入失败，目标类: net.minecraft.client.Minecraft
 * [MG-ERROR] 初始化 mod expore (io.mirage.test.ExpOreMod) 失败——该 mod 的功能将不可用，游戏继续启动
 * [MG-WARN] Fabric server payload handler 抛出异常
 * [MG-IGNORE] mod jar 加入系统 classpath 失败（已走 mcl.getResources 兜底路径）
 * </pre>
 * 带异常时，异常链与栈帧接在消息之后（复用 {@link MirageLog#describe(Throwable)} 的渲染，
 * 保证与日志门面看到的是同一套栈帧裁剪规则）。
 *
 * <p>流向约定：FATAL / ERROR / WARN 走 stderr（与 {@link MirageLog} 的约定一致，
 * "正常玩一局用户需不需要看这行"——需要），IGNORE 走 stdout
 * （已兜住的问题不该污染错误流，但它仍要留在日志里备查）。
 *
 * <p>门控：按 {@link ErrorLevel#logLevel()} 走 {@link MirageLog} 的级别开关，
 * 即 {@code -Dmirage.log.level=error} 时 WARN/IGNORE 会静音、FATAL 保留。
 *
 * <p>与 {@link MirageLog} 的分工：{@code MirageLog} 是通用日志通道（{@code [LS]} / {@code [Loader]}），
 * 本类是**错误分档**通道。两者输出互不替代：需要"按严重程度 grep"就用本类，
 * 需要"按子系统 grep"就用 MirageLog 的通道。
 */
public final class MirageErrors {

    private MirageErrors() {
    }

    // ---------------------------------------------------------------- 记录

    /** 不可继续：应当让流程停下来。 */
    public static void fatal(String msg) {
        emit(ErrorLevel.FATAL, msg, null);
    }

    public static void fatal(String msg, Throwable t) {
        emit(ErrorLevel.FATAL, msg, t);
    }

    /** 功能受损但流程可继续。 */
    public static void error(String msg) {
        emit(ErrorLevel.ERROR, msg, null);
    }

    public static void error(String msg, Throwable t) {
        emit(ErrorLevel.ERROR, msg, t);
    }

    /** 已降级，功能还在但行为与预期有出入。 */
    public static void warn(String msg) {
        emit(ErrorLevel.WARN, msg, null);
    }

    public static void warn(String msg, Throwable t) {
        emit(ErrorLevel.WARN, msg, t);
    }

    /** 预期内的失败，已兜住，仅留痕备查。 */
    public static void ignore(String msg) {
        emit(ErrorLevel.IGNORE, msg, null);
    }

    public static void ignore(String msg, Throwable t) {
        emit(ErrorLevel.IGNORE, msg, t);
    }

    // ---------------------------------------------------------------- 异常

    /** 造一个带级别的异常（不记录，由调用方决定是抛还是先记录）。 */
    public static MirageException exception(ErrorLevel level, String msg) {
        return new MirageException(level, msg);
    }

    /** 造一个带级别的异常（不记录，由调用方决定是抛还是先记录）。 */
    public static MirageException exception(ErrorLevel level, String msg, Throwable cause) {
        return new MirageException(level, msg, cause);
    }

    // ---------------------------------------------------------------- 内部

    private static void emit(ErrorLevel level, String msg, Throwable t) {
        if (!MirageLog.isEnabled(level.logLevel())) {
            return;
        }
        StringBuilder sb = new StringBuilder(128);
        sb.append('[').append(level.prefix()).append("] ");
        if (msg != null && !msg.isEmpty()) {
            sb.append(msg);
        }
        if (t != null) {
            sb.append(System.lineSeparator()).append(MirageLog.describe(t));
        }
        PrintStream out = (level == ErrorLevel.IGNORE) ? System.out : System.err;
        out.println(sb);
        out.flush();
    }
}
