package io.mirage.api.error;

/**
 * 带 {@link ErrorLevel} 的 MG 异常。
 *
 * <p>用途是让"抛异常"这件事本身携带严重程度，调用方不必再猜：
 * 捕获后 {@code catch (MirageException e) { switch (e.level()) {...} }}，
 * 或者直接交给 {@link MirageErrors#fatal(String, Throwable)} 之类的方法记录。
 *
 * <p>继承 {@link RuntimeException}（受检异常的负担太重，MG 内部大量用非受检），
 * 但**致命场景请用 {@link Error} 系**（如 MixinApplyFatalException）——
 * 上层大量 {@code catch (Exception)} 会把 RuntimeException 吞掉，致命信号穿不过去。
 */
public class MirageException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final ErrorLevel level;

    public MirageException(ErrorLevel level, String message) {
        super(message);
        this.level = level == null ? ErrorLevel.ERROR : level;
    }

    public MirageException(ErrorLevel level, String message, Throwable cause) {
        super(message, cause);
        this.level = level == null ? ErrorLevel.ERROR : level;
    }

    /** 本异常所属的严重程度。 */
    public ErrorLevel level() {
        return level;
    }

    @Override
    public String toString() {
        return "[" + level.prefix() + "] " + getMessage();
    }
}
