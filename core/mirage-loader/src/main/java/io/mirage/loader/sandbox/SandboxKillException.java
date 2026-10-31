package io.mirage.loader.sandbox;

/**
 * 沙盒内 mod 调 {@code System.exit} / {@code Runtime.exit} / {@code Runtime.halt} 时，
 * 代替真实的进程退出抛出本异常。
 *
 * <p>MSR P1 的第一条止血：这些调用一旦真的执行，杀掉的是 <b>launcher 进程本身</b>
 * （mod 构造与 launcher 同 JVM），沙盒录制还没结束整个启动就没了。
 *
 * <p>继承 {@link Error} 而不是 {@link Exception}：mod 代码里常见的
 * {@code catch (Exception e)} 不能把"进程退出"这种意图悄悄吞掉。
 *
 * <p>关闭栈帧（{@code writableStackTrace=false}）：录制只需要退出码，
 * 抛一次建一条完整栈在沙盒里是纯浪费。
 */
public final class SandboxKillException extends Error {

    private static final long serialVersionUID = 1L;

    /** mod 请求的退出码原值（{@code System.exit(code)} 的 code）。 */
    private final int exitCode;

    public SandboxKillException(int exitCode) {
        super("exit(" + exitCode + ")", null, false, false);
        this.exitCode = exitCode;
    }

    public int exitCode() {
        return exitCode;
    }
}
