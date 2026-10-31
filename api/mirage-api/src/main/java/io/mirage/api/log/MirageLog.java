package io.mirage.api.log;

import java.io.PrintStream;

/**
 * Mirage 统一日志门面（加载器内部 + mod 桥接共用）。
 *
 * <h2>为什么需要它</h2>
 * 改造前全仓库 238 处裸 {@code System.out/err.println}，导致四类问题：
 * <ol>
 *   <li><b>异常链丢失</b>——最常见的写法 {@code "...失败: " + e}，对
 *       {@code InvocationTargetException} / {@code ExceptionInInitializerError} /
 *       {@code NullPointerException} 只会打出 {@code "java.lang.reflect.InvocationTargetException"}
 *       或 {@code "null"}，真正的失败原因（被包装的 cause）完全不可见。启动日志里
 *       {@code [Mirage] 事件处理错误: null} 就是这类。</li>
 *   <li><b>没有级别</b>——反射探测、贴图绑定、每次绘制成功之类的诊断噪声与真正的致命错误
 *       混在同一层，既没法在正常游玩时关掉，也没法一次 grep 出所有错误。</li>
 *   <li><b>格式分散</b>——{@code [Mirage]} / {@code [LS]} / {@code [modid]} 三套前缀各异，
 *       级别词靠手写（只有个别地方写了 {@code "WARN "}）。</li>
 *   <li><b>堆栈不可控</b>——要么全无（{@code + e}），要么 {@code printStackTrace()} 直冲
 *       stderr 绕过统一格式。</li>
 * </ol>
 *
 * <h2>格式</h2>
 * <pre>
 * [Mirage] INFO  发现 4 个 mod
 * [LS]     WARN  无人值守环境，ParseFailPolicy=confirm 自动降级为 crash
 * [Mirage] ERROR 初始化 mod neop0test 失败: java.lang.ClassNotFoundException: io.mirage.test.NeoP0TestMod
 *                 at io.mirage.loader.MirageClassLoader.loadClass(MirageClassLoader.java:88)
 *                 ... 3 more
 * </pre>
 * 前缀沿用既有的 {@code [Mirage]} / {@code [LS]}（HUD 日志节选按 {@code [Mirage} 关键字过滤，
 * 见 {@code MirageDebug.isImportantLog}），只在中间补级别词，因此既有 grep 习惯不失效。
 *
 * <h2>级别控制</h2>
 * 优先级：系统属性 {@code -Dmirage.log.level=} &gt; 环境变量 {@code MIRAGE_LOG_LEVEL} &gt; 缺省 {@code INFO}。
 * 取值：{@code trace|debug|info|warn|error|fatal|off}（大小写不敏感）。
 * <pre>
 * -Dmirage.log.level=debug        # 排查启动问题时打开全部诊断输出
 * -Dmirage.log.level=warn         # 只关心问题时的精简日志
 * </pre>
 *
 * <h2>堆栈策略</h2>
 * {@code -Dmirage.log.stack=smart|full|none}（缺省 {@code smart}）：
 * <ul>
 *   <li>{@code smart}——保留前 2 帧定位入口 + 所有项目相关帧（{@code io.mirage.} /
 *       {@code net.minecraft.} / {@code net.neoforged.} / {@code net.fabricmc.} / {@code com.mojang.}），
 *       最多 16 行，其余折叠为 {@code ... N more}。启动期的日志已经被几千行 MC 输出淹没，
 *       全量堆栈反而让人找不到重点。</li>
 *   <li>{@code full}——完整堆栈（开发调试用）。</li>
 *   <li>{@code none}——只打一行摘要。</li>
 * </ul>
 *
 * <h2>输出流向</h2>
 * {@code TRACE/DEBUG/INFO} → stdout，{@code WARN/ERROR/FATAL} → stderr（沿用改造前的语义，
 * 这样 {@code 2>&1} 之外的启动器分流行为不变）。<b>每次写都重新取 {@code System.out}，
 * 不缓存引用</b>——Launcher 会在 main 早期把 stdout/stderr 换成 tee 流（旁路喂 HUD 日志节选），
 * 缓存引用会让之后的日志绕过 tee、HUD 上就看不到。</p>
 *
 * <p><b>FATAL 不在这里 {@code System.exit}</b>：本类只负责「标记不可恢复 + 打全堆栈」，
 * 是否终止进程由调用方决定（LS 阶段机统一 exit 1，见 {@code LifecycleScheduler}）——
 * 库里藏 System.exit 会让单测和 GUI 场景无法复用。</p>
 */
public final class MirageLog {

    /** 日志级别，序数即严重度。{@link #OFF} 关闭全部输出。 */
    public enum Level {
        TRACE, DEBUG, INFO, WARN, ERROR, FATAL, OFF;

        public static Level parse(String s, Level fallback) {
            if (s == null) return fallback;
            switch (s.trim().toLowerCase(java.util.Locale.ROOT)) {
                case "trace": return TRACE;
                case "debug", "fine": return DEBUG;
                case "info": return INFO;
                case "warn", "warning": return WARN;
                case "error", "severe": return ERROR;
                case "fatal": return FATAL;
                case "off", "none", "silent": return OFF;
                default: return fallback;
            }
        }
    }

    private static final String LEVEL_PROPERTY = "mirage.log.level";
    private static final String LEVEL_ENV = "MIRAGE_LOG_LEVEL";
    private static final String STACK_PROPERTY = "mirage.log.stack";

    /** 当前生效级别。可运行期动态调整（{@link #setLevel}），故为 volatile。 */
    private static volatile Level threshold = resolveLevel();
    /** 进程启动纳秒基准，用于 {@code -Dmirage.log.time=true} 时的相对时间戳。 */
    private static final long START_NANOS = System.nanoTime();
    private static volatile boolean showTime = Boolean.getBoolean("mirage.log.time");

    private static final int STACK_SMART = 0;
    private static final int STACK_FULL = 1;
    private static final int STACK_NONE = 2;
    private static final int stackMode = resolveStackMode();

    /** 缺省通道（前缀 {@code [Mirage]}）。 */
    public static final Channel MIRAGE = new Channel("Mirage");

    private MirageLog() {}

    // ------------------------------------------------------------------
    // 全局配置
    // ------------------------------------------------------------------

    private static Level resolveLevel() {
        String v = System.getProperty(LEVEL_PROPERTY);
        if (v == null || v.isBlank()) v = System.getenv(LEVEL_ENV);
        return Level.parse(v, Level.INFO);
    }

    private static int resolveStackMode() {
        String v = System.getProperty(STACK_PROPERTY);
        if (v == null) return STACK_SMART;
        return switch (v.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "full", "all" -> STACK_FULL;
            case "none", "off", "0" -> STACK_NONE;
            default -> STACK_SMART;
        };
    }

    public static Level level() { return threshold; }

    public static void setLevel(Level l) {
        if (l != null) threshold = l;
    }

    /** 是否输出该级别。高频路径（逐帧绘制、逐方块绑定）应先问再拼字符串。 */
    public static boolean isEnabled(Level l) {
        Level t = threshold;
        return t != Level.OFF && l.ordinal() >= t.ordinal();
    }

    public static boolean isDebugEnabled() { return isEnabled(Level.DEBUG); }

    public static void enableTimeStamp(boolean on) { showTime = on; }

    /** 取带前缀的通道。常用值见 {@link #MIRAGE}、{@link #LS}、{@link #LOADER}。 */
    public static Channel of(String tag) {
        return new Channel(tag == null || tag.isBlank() ? "Mirage" : tag);
    }

    /** 生命周期阶段机通道（{@code [LS]}）。 */
    public static final Channel LS = new Channel("LS");
    /** mod 发现/排序通道（{@code [Loader]}）。 */
    public static final Channel LOADER = new Channel("Loader");
    /** MID 磁盘缓存通道（{@code [MidCache]}）。 */
    public static final Channel MIDCACHE = new Channel("MidCache");
    /** Mixin 装配通道（{@code [Mirage-Mixin]}）。 */
    public static final Channel MIXIN = new Channel("Mirage-Mixin");

    // ------------------------------------------------------------------
    // 通道
    // ------------------------------------------------------------------

    /**
     * 一条带固定前缀的日志通道。轻量对象（只持有 tag 字符串），可随意 {@code static final} 持有。
     */
    public static final class Channel {

        private final String tag;

        Channel(String tag) { this.tag = tag; }

        public String tag() { return tag; }

        // ---- 无异常 ----

        public void trace(String msg) { emit(Level.TRACE, msg, null); }
        public void debug(String msg) { emit(Level.DEBUG, msg, null); }
        public void info(String msg)  { emit(Level.INFO, msg, null); }
        public void warn(String msg)  { emit(Level.WARN, msg, null); }
        public void error(String msg) { emit(Level.ERROR, msg, null); }
        public void fatal(String msg) { emit(Level.FATAL, msg, null); }

        // ---- 带异常：这是门面存在的主要理由 ----

        public void trace(String msg, Throwable t) { emit(Level.TRACE, msg, t); }
        public void debug(String msg, Throwable t) { emit(Level.DEBUG, msg, t); }
        public void info(String msg, Throwable t)  { emit(Level.INFO, msg, t); }
        public void warn(String msg, Throwable t)  { emit(Level.WARN, msg, t); }
        public void error(String msg, Throwable t) { emit(Level.ERROR, msg, t); }
        public void fatal(String msg, Throwable t) { emit(Level.FATAL, msg, t); }

        /**
         * 原样输出一行（不加级别词），仅供已经被文档/冒烟脚本冻结的关键字行使用，
         * 例如 {@code [LS] stage S4 RegistryMerge start}——这类行是 pipeline 骨架，
         * 格式变化会让历史日志对比失效。
         */
        public void raw(String line) {
            PrintStream out = System.out;
            out.println(line);
            out.flush();
        }

        // ---- 级别判断 ----

        public boolean isTrace() { return isEnabled(Level.TRACE); }
        public boolean isDebug() { return isEnabled(Level.DEBUG); }

        // ------------------------------------------------------------------

        private void emit(Level l, String msg, Throwable t) {
            if (!isEnabled(l)) return;
            StringBuilder sb = new StringBuilder(96);
            if (showTime) {
                long ms = (System.nanoTime() - START_NANOS) / 1_000_000L;
                sb.append(String.format(java.util.Locale.ROOT, "[%7.3fs] ", ms / 1000.0));
            }
            sb.append('[').append(tag).append("] ");
            sb.append(pad(l.name())).append(' ').append(msg == null ? "" : msg);
            // 带异常时把摘要并进首行：只打消息 + 纯栈帧会让人看不出「抛的是什么」。
            // summary 会剥掉 InvocationTargetException 一类包装，直接给真实 cause。
            if (t != null) sb.append(": ").append(summary(t));

            PrintStream out = (l.ordinal() >= Level.WARN.ordinal()) ? System.err : System.out;
            out.println(sb);
            if (t != null) appendStack(out, t);
            out.flush();
        }
    }

    /** 级别词定宽 5 列（INFO/WARN 补一个空格），让消息正文左对齐、便于竖着扫读。 */
    private static String pad(String name) {
        return name.length() >= 5 ? name : name + " ".repeat(5 - name.length());
    }

    // ------------------------------------------------------------------
    // 异常格式化
    // ------------------------------------------------------------------

    /** 栈帧缩进：对齐到 {@code [tag] LEVEL } 之后的消息正文列。 */
    private static final String INDENT = "                ";

    private static void appendStack(PrintStream out, Throwable t) {
        if (stackMode == STACK_NONE) return;
        Throwable cur = unwrap(t);
        int chain = 0;
        while (cur != null && chain < 4) {
            if (chain > 0) out.println(INDENT + "Caused by: " + summary(cur));
            StackTraceElement[] st = cur.getStackTrace();
            int printed = 0;
            int budget = stackMode == STACK_FULL ? Integer.MAX_VALUE : 16;
            for (int i = 0; i < st.length; i++) {
                if (stackMode == STACK_SMART && !isInteresting(st[i])) {
                    // 前 2 帧始终保留（定位「从哪被调起」），之后只留项目帧
                    if (i >= 2) continue;
                }
                if (printed >= budget) {
                    out.println(INDENT + "... " + (st.length - i) + " more");
                    break;
                }
                out.println(INDENT + "at " + st[i]);
                printed++;
            }
            cur = unwrap(cur.getCause());
            chain++;
        }
    }

    private static final String[] INTERESTING = {
            "io.mirage.", "net.minecraft.", "com.mojang.",
            "net.neoforged.", "net.fabricmc.", "org.spongepowered."
    };

    private static boolean isInteresting(StackTraceElement e) {
        String cn = e.getClassName();
        for (String p : INTERESTING) {
            if (cn.startsWith(p)) return true;
        }
        return false;
    }

    /**
     * 剥掉反射/初始化包装层。
     *
     * <p>{@code InvocationTargetException} 的 message 永远是 {@code null}，
     * 直接打印只能看到类名——这正是 {@code [Mirage] DeferredRegister 注册失败 ... ->
     * java.lang.reflect.InvocationTargetException} 让人无从下手的原因。
     */
    private static Throwable unwrap(Throwable t) {
        Throwable cur = t;
        int guard = 0;
        while (cur != null && guard++ < 8) {
            if (cur instanceof java.lang.reflect.InvocationTargetException && cur.getCause() != null) {
                cur = cur.getCause();
            } else if (cur instanceof ExceptionInInitializerError && cur.getCause() != null) {
                cur = cur.getCause();
            } else if (cur instanceof java.lang.reflect.UndeclaredThrowableException && cur.getCause() != null) {
                cur = cur.getCause();
            } else {
                break;
            }
        }
        return cur;
    }

    /** 单行摘要：{@code 类名: message}，message 为空时只留类名（不再出现裸 "null"）。 */
    public static String summary(Throwable t) {
        if (t == null) return "null";
        Throwable u = unwrap(t);
        String m = u.getMessage();
        String cn = u.getClass().getName();
        return (m == null || m.isBlank()) ? cn : cn + ": " + m;
    }

    /**
     * 把异常渲染成多行字符串，供不便使用 {@link Channel} 的调用点拼接：
     * <pre>
     * System.err.println("[Mirage] 失败: " + MirageLog.describe(t));
     * </pre>
     * 输出第一行是摘要，后续是（受策略约束的）堆栈帧，已带缩进。
     */
    public static String describe(Throwable t) {
        if (t == null) return "null";
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream(256);
        PrintStream ps = new PrintStream(buf, true, java.nio.charset.StandardCharsets.UTF_8);
        ps.print(summary(t));
        ps.println();
        appendStack(ps, t);
        ps.flush();
        return buf.toString(java.nio.charset.StandardCharsets.UTF_8).stripTrailing();
    }

    // ------------------------------------------------------------------
    // 进程内诊断
    // ------------------------------------------------------------------

    /** 进程已运行秒数（配合 {@code -Dmirage.log.time=true} 看启动各阶段耗时）。 */
    public static double uptimeSeconds() {
        return (System.nanoTime() - START_NANOS) / 1_000_000_000.0;
    }

    /** 一次性打印当前生效的日志配置，便于用户确认 {@code -D} 有没有生效。 */
    public static void printConfig() {
        if (!isEnabled(Level.DEBUG)) return;
        MIRAGE.debug("日志级别=" + threshold + " 堆栈=" + switch (stackMode) {
            case STACK_FULL -> "full";
            case STACK_NONE -> "none";
            default -> "smart";
        } + " 时间戳=" + showTime);
    }
}
