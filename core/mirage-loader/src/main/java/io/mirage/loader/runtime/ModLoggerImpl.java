package io.mirage.loader.runtime;

import io.mirage.api.log.ModLogger;

/**
 * 最简单的 ModLogger 实现：带 {@code [modid]} 前缀打到 stdout/stderr。
 *
 * <p>M8 之前桥接 Log4j2；当前阶段保证"Mod 日志能看到"即可。
 */
public final class ModLoggerImpl implements ModLogger {

    private final String modId;

    public ModLoggerImpl(String modId) {
        this.modId = modId;
    }

    @Override public String modId() { return modId; }

    private String fmt(String format, Object... args) {
        // 极简 {} 占位符替换；不引入 SLF4J
        String s = format;
        for (Object a : args) {
            int i = s.indexOf("{}");
            if (i < 0) break;
            s = s.substring(0, i) + String.valueOf(a) + s.substring(i + 2);
        }
        return s;
    }

    @Override public void debug(String message)                 { System.out.println("[" + modId + "] [DEBUG] " + message); }
    @Override public void debug(String f, Object... a)          { debug(fmt(f, a)); }

    @Override public void info(String message)                  { System.out.println("[" + modId + "] [INFO]  " + message); }
    @Override public void info(String f, Object... a)           { info(fmt(f, a)); }

    @Override public void warn(String message)                 { System.err.println("[" + modId + "] [WARN]  " + message); }
    @Override public void warn(String f, Object... a)           { warn(fmt(f, a)); }
    @Override public void warn(String message, Throwable t)    { warn(message); t.printStackTrace(); }

    @Override public void error(String message)               { System.err.println("[" + modId + "] [ERROR] " + message); }
    @Override public void error(String f, Object... a)         { error(fmt(f, a)); }
    @Override public void error(String message, Throwable t)  { error(message); t.printStackTrace(); }
}
