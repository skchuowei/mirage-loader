package io.mirage.loader.runtime;

import io.mirage.api.log.MirageLog;
import io.mirage.api.log.ModLogger;

/**
 * {@link ModLogger} 的加载器实现：桥接到统一日志门面 {@link MirageLog}。
 *
 * <p>改造前这里直接 {@code System.out/err.println}，且 {@code warn(msg, t)} 走
 * {@code t.printStackTrace()}——堆栈绕过统一格式直冲 stderr，没有时间戳、没有级别前缀，
 * 和加载器自己的日志在 latest.log 里完全对不上号。现在每个 mod 拿到的是一个
 * {@code [<modid>]} 通道：
 * <pre>
 * [expore] INFO  已注册 12 个方块
 * [expore] ERROR 注册 example_block 失败: java.lang.IllegalArgumentException: ...
 *                 at ...
 * </pre>
 * 级别、堆栈深度、是否输出全部跟随全局配置（{@code -Dmirage.log.level=}），
 * mod 作者无法绕过、也不需要自己管。
 */
public final class ModLoggerImpl implements ModLogger {

    private final String modId;
    private final MirageLog.Channel channel;

    public ModLoggerImpl(String modId) {
        this.modId = (modId == null || modId.isBlank()) ? "unknown" : modId;
        this.channel = MirageLog.of(this.modId);
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

    @Override public void debug(String message)                { channel.debug(message); }
    @Override public void debug(String f, Object... a)         { debug(fmt(f, a)); }

    @Override public void info(String message)                 { channel.info(message); }
    @Override public void info(String f, Object... a)          { info(fmt(f, a)); }

    @Override public void warn(String message)                 { channel.warn(message); }
    @Override public void warn(String f, Object... a)          { warn(fmt(f, a)); }
    @Override public void warn(String message, Throwable t)    { channel.warn(message, t); }

    @Override public void error(String message)                { channel.error(message); }
    @Override public void error(String f, Object... a)         { error(fmt(f, a)); }
    @Override public void error(String message, Throwable t)   { channel.error(message, t); }
}
