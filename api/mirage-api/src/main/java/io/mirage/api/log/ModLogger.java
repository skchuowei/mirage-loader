package io.mirage.api.log;

/**
 * Mod 专用日志门面。
 *
 * <p>实现由加载器提供，自动给每行加上 {@code [modid]} 前缀，
 * 并桥接到 SLF4J / Log4j2 等后端。Mod 不应直接 new Logger。
 */
public interface ModLogger {

    String modId();

    void debug(String message);
    void debug(String format, Object... args);

    void info(String message);
    void info(String format, Object... args);

    void warn(String message);
    void warn(String format, Object... args);
    void warn(String message, Throwable t);

    void error(String message);
    void error(String format, Object... args);
    void error(String message, Throwable t);
}
