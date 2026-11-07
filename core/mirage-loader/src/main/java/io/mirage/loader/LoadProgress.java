package io.mirage.loader;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 加载期<b>真实</b>进度模型：meter 栈 + 计数器 + 事件环。
 *
 * <p>纯逻辑类——只依赖 JDK，不碰 GL / AWT / MC / 日志门面，因此可以离线 javac 编译并断言
 * （见 {@code scripts/verify_loadprogress.ps1}）。把算比例、拼文案、游标轨迹都放在这里，
 * 而不是散在两个渲染器里，是为了不在回归脚本里重写一遍生产算法（经验 C41）。
 *
 * <h2>为什么需要它</h2>
 * 旧版 {@code MirageDebug.progress} 是一串手写常量（3/8/14/22/32/42/72/85/92/95/100），
 * 和真实工作量没有任何关系：S4 的 {@code Bootstrap.bootStrap()} 实测 6.8~40.2s、S7 资源重载
 * 23.6~68.1s，这两段时间里屏幕上的百分比一动不动。用户看到的「色块」就是这么来的。
 *
 * <h2>核心原则</h2>
 * <ul>
 *   <li><b>分母已知 → 确定条</b>（{@code steps > 0}，画 {@code current/steps}）；</li>
 *   <li><b>分母未知 → 不定条</b>（{@code steps == 0}，画来回滑动的游标 + 实时秒表），
 *       <b>绝不编一个百分比</b>；</li>
 *   <li>每根 meter 是「一个正在跑的任务」，彼此独立；主条是 7 个阶段的分段总览，
 *       段内比例取自当前阶段真实 meter 的分子/分母。</li>
 * </ul>
 *
 * <p>语义参照 NeoForge {@code ProgressMeter}（{@code steps == 0} 即不定条）与
 * {@code StartupNotificationManager} 的 meter 栈，实现为 clean-room：Java 代码全部自写。
 */
public final class LoadProgress {

    /** 事件环容量（渲染侧只显示最近若干行，缓冲区留 32 条便于回看）。 */
    public static final int EVENT_CAPACITY = 32;
    /** 不定进度游标的宽度占条宽的比例。两窗口都从这一个常量取值。 */
    public static final float CURSOR_FRACTION = 0.2f;
    /** 主阶段数 S1..S7。 */
    public static final int STAGE_COUNT = 7;
    /** 阶段名（与 {@code MirageDebug.PHASE_NAMES} 同源，后者直接引用本数组）。 */
    public static final String[] STAGE_NAMES = {
            "发现Mod", "解析清单", "字节码变换", "合并注册表", "事件总线", "初始化Mod", "启动客户端"
    };

    private static final long START_NANOS = System.nanoTime();
    private static final Object LOCK = new Object();
    private static final Deque<Meter> METERS = new ArrayDeque<>();
    private static final Deque<Event> EVENTS = new ArrayDeque<>();
    private static final Map<String, Long> COUNTERS = new LinkedHashMap<>();

    private static int stage;
    private static String detail = "";
    private static long stageStartNanos = START_NANOS;
    private static boolean finished;
    private static int nextId = 1;

    private LoadProgress() {
    }

    /** 一根正在跑的任务条。{@code steps == 0} 表示分母未知（不定条）。 */
    public static final class Meter {
        private final int id;
        private final int steps;
        private final long startNanos;
        private volatile String label;
        private volatile int current;
        private volatile boolean ended;

        private Meter(int id, String label, int steps, long startNanos) {
            this.id = id;
            this.label = label;
            this.steps = steps;
            this.startNanos = startNanos;
        }

        public int id() { return id; }
        public int steps() { return steps; }
        public String label() { return label; }
        public boolean ended() { return ended; }
    }

    /** 渲染侧拿到的 meter 快照；{@code indeterminate == true} 时 {@code fraction == -1}。 */
    public record Snapshot(int id, String label, int steps, int current, float fraction,
                           boolean indeterminate, long elapsedMs) {
    }

    /** 事件流里的一条（时间戳为相对进程启动的毫秒）。 */
    public record Event(long atMs, String channel, String text) {
    }

    /** 一帧要画的全部文本与数值。渲染侧每 200ms 取一次，避免每帧重拼字符串。 */
    public record View(String totalLabel, String breadcrumb, String counter1, String counter2,
                       float[] segments, List<Snapshot> meters, List<String> eventLines,
                       long elapsedMs) {
    }

    // ------------------------------------------------------------------ meter 栈

    /** 开一根不定条（分母未知）。 */
    public static Meter begin(String label) {
        return begin(label, 0);
    }

    /** 开一根条：{@code steps > 0} 为确定条，{@code <= 0} 为不定条。 */
    public static Meter begin(String label, int steps) {
        Meter m;
        synchronized (LOCK) {
            m = new Meter(nextId++, label == null ? "" : label,
                    Math.max(0, steps), System.nanoTime());
            METERS.addLast(m);
        }
        event("METER", "开始 " + m.label() + (m.steps() > 0 ? " 0/" + m.steps() : " (不定)"));
        return m;
    }

    /** 改 label（例如 S6 每换一个 mod 就把 mod id 写进 label）。 */
    public static void label(Meter m, String label) {
        if (m != null && label != null) m.label = label;
    }

    /** 完成一个单位。 */
    public static void step(Meter m) {
        if (m == null || m.ended) return;
        synchronized (LOCK) {
            if (m.steps > 0 && m.current < m.steps) m.current++;
        }
    }

    /** 设绝对进度（越界自动夹到 0..steps；不定条忽略）。 */
    public static void set(Meter m, int absolute) {
        if (m == null || m.ended || m.steps <= 0) return;
        synchronized (LOCK) {
            m.current = Math.max(0, Math.min(m.steps, absolute));
        }
    }

    /** 结束一根条（出栈，幂等）。 */
    public static void end(Meter m) {
        if (m == null || m.ended) return;
        long ms;
        synchronized (LOCK) {
            m.ended = true;
            METERS.remove(m);
            ms = (System.nanoTime() - m.startNanos) / 1_000_000L;
        }
        event("METER", "结束 " + m.label() + " · " + seconds(ms));
    }

    /** 渲染侧可见的 meter 快照，最多 {@code max} 根，取<b>最近创建</b>的那些，保持创建顺序。 */
    public static List<Snapshot> snapshot(int max) {
        if (max <= 0) return List.of();
        synchronized (LOCK) {
            int size = METERS.size();
            int limit = Math.min(max, size);
            List<Snapshot> out = new ArrayList<>(limit);
            int skip = size - limit;
            int i = 0;
            long now = System.nanoTime();
            for (Meter m : METERS) {
                if (i++ < skip) continue;
                boolean indeterminate = m.steps == 0;
                float fraction = indeterminate ? -1f : clamp01((float) m.current / m.steps);
                out.add(new Snapshot(m.id, m.label, m.steps, m.current, fraction,
                        indeterminate, (now - m.startNanos) / 1_000_000L));
            }
            return out;
        }
    }

    /** meter label 的左半段（任务名），渲染侧左对齐。 */
    public static String meterHead(Snapshot s) {
        return s == null || s.label() == null ? "" : s.label();
    }

    /** meter label 的右半段（分子/分母 + 耗时），渲染侧右对齐到条外框右缘。 */
    public static String meterTail(Snapshot s) {
        if (s == null) return "";
        String count = s.indeterminate() ? "不定" : s.current() + "/" + s.steps();
        return count + " · " + seconds(s.elapsedMs());
    }

    /** 一根 meter 的整行文案：确定条带 {@code 分子/分母}，不定条只带秒表；两者都带耗时。 */
    public static String meterLabel(Snapshot s) {
        return meterHead(s) + " · " + meterTail(s);
    }

    // ------------------------------------------------------------------ 主阶段

    /** 进入主阶段 {@code index}（0..6 对应 S1..S7）。 */
    public static void stage(int index) {
        if (index < 0 || index >= STAGE_COUNT) return;
        synchronized (LOCK) {
            stage = index;
            stageStartNanos = System.nanoTime();
        }
        event("STAGE", "进入 S" + (index + 1) + " " + STAGE_NAMES[index]);
    }

    /** 当前阶段内的子任务描述（来自 {@code MirageDebug.setStage}）。 */
    public static void detail(String text) {
        synchronized (LOCK) {
            detail = text == null ? "" : text;
        }
    }

    /** 当前阶段索引。 */
    public static int stageIndex() {
        synchronized (LOCK) {
            return stage;
        }
    }

    /**
     * 分段总进度：长度 7，每段取值 0..1；<b>当前段为 -1 表示不定</b>（画段内游标）。
     * 已完成段=1、未开始段=0；当前段取栈顶第一根确定条的真实比例，没有就不定。
     * 段宽等分——权重不再用「按历史耗时估出来的百分比」，避免又引入一个编出来的分母。
     */
    public static float[] segments() {
        synchronized (LOCK) {
            float[] out = new float[STAGE_COUNT];
            if (finished) {
                Arrays.fill(out, 1f);
                return out;
            }
            for (int i = 0; i < STAGE_COUNT; i++) {
                if (i < stage) out[i] = 1f;
                else if (i > stage) out[i] = 0f;
                else out[i] = stageFractionLocked();
            }
            return out;
        }
    }

    /** 栈顶往下找第一根确定条，取其真实比例；找不到返回 -1（不定）。 */
    private static float stageFractionLocked() {
        Iterator<Meter> it = METERS.descendingIterator();
        while (it.hasNext()) {
            Meter m = it.next();
            if (m.steps > 0) return clamp01((float) m.current / m.steps);
        }
        return -1f;
    }

    /** TitleScreen 是否已经收尾（避免重复 finish 重复发事件）。 */
    public static boolean isFinished() {
        synchronized (LOCK) {
            return finished;
        }
    }

    /** TitleScreen 出现：收掉所有 meter，主条填满。 */
    public static void finish() {
        synchronized (LOCK) {
            finished = true;
            for (Meter m : METERS) m.ended = true;
            METERS.clear();
        }
        event("STAGE", "加载完成 · " + seconds(totalElapsedMs()));
    }

    /** 进程启动到现在的毫秒数。 */
    public static long totalElapsedMs() {
        return (System.nanoTime() - START_NANOS) / 1_000_000L;
    }

    /** 当前阶段已用毫秒数。 */
    public static long stageElapsedMs() {
        synchronized (LOCK) {
            return (System.nanoTime() - stageStartNanos) / 1_000_000L;
        }
    }

    /**
     * 不定进度游标左端在条上的比例（0..1-CURSOR_FRACTION）。
     * 三角波：先向右滑到底，再滑回来；渲染侧只负责把它乘到像素上。
     */
    public static float cursorLeft(int frame) {
        int period = 100;
        int p = Math.floorMod(frame, period);
        float t = p / (float) period;
        float tri = t < 0.5f ? t * 2f : 2f - 2f * t;
        return tri * (1f - CURSOR_FRACTION);
    }

    // ------------------------------------------------------------------ 计数器

    /** 计数行数据源（标量）。 */
    public static void counter(String key, long value) {
        if (key == null) return;
        synchronized (LOCK) {
            COUNTERS.put(key, value);
        }
    }

    public static void increment(String key) {
        if (key == null) return;
        synchronized (LOCK) {
            COUNTERS.merge(key, 1L, Long::sum);
        }
    }

    public static long counterValue(String key, long defaultValue) {
        synchronized (LOCK) {
            return COUNTERS.getOrDefault(key, defaultValue);
        }
    }

    // ------------------------------------------------------------------ 事件流

    /** 事件流准入用的通道判定（全部 ASCII 短名，早期窗口字表不必补字）。 */
    public static String channelOf(String s) {
        if (s == null) return "MC";
        if (s.contains("[Mirage-Mixin]")) return "MIXIN";
        if (s.contains("[LS]")) return "LS";
        if (s.contains("[Loader]")) return "LOADER";
        if (s.contains("[MidCache]") || s.contains("[MID]")) return "CACHE";
        if (s.contains("[Fabric]")) return "FABRIC";
        if (s.contains("[NeoForge]") || s.contains("[Neo]")) return "NEO";
        if (s.contains("[MG-FATAL]") || s.contains("[MG-ERROR]")) return "ERROR";
        if (s.contains("[MG-WARN]") || s.contains("WARN") || s.contains("警告")) return "WARN";
        if (s.contains("[Mirage]")) return "MG";
        if (s.contains("ERROR") || s.contains("Exception") || s.contains("失败")) return "ERROR";
        return "MC";
    }

    /** 写一条事件（环形缓冲，容量 {@link #EVENT_CAPACITY}）。 */
    public static void event(String channel, String text) {
        if (text == null) return;
        String t = text.strip();
        if (t.isEmpty()) return;
        if (t.length() > 160) t = t.substring(0, 160);
        synchronized (LOCK) {
            if (EVENTS.size() >= EVENT_CAPACITY) EVENTS.pollFirst();
            EVENTS.addLast(new Event(totalElapsedMs(), channel == null ? "MC" : channel, t));
        }
    }

    /** 缓冲里当前有多少条事件（回归脚本用它钉住环形容量）。 */
    public static int eventCount() {
        synchronized (LOCK) {
            return EVENTS.size();
        }
    }

    /** 最近 {@code max} 条事件（旧→新）。 */
    public static List<Event> events(int max) {
        if (max <= 0) return List.of();
        synchronized (LOCK) {
            int limit = Math.min(max, EVENTS.size());
            List<Event> out = new ArrayList<>(limit);
            int skip = EVENTS.size() - limit;
            int i = 0;
            for (Event e : EVENTS) {
                if (i++ >= skip) out.add(e);
            }
            return out;
        }
    }

    // ------------------------------------------------------------------ 一帧视图

    /** 渲染侧唯一入口：一次 synchronized 拿到全部文本与数值。 */
    public static View view(int maxMeters, int maxEvents) {
        synchronized (LOCK) {
            long totalMs = totalElapsedMs();
            long stageMs = (System.nanoTime() - stageStartNanos) / 1_000_000L;
            String d = detail.isEmpty() ? "" : " · " + detail;
            String totalLabel = "S" + (stage + 1) + "/" + STAGE_COUNT + " "
                    + STAGE_NAMES[stage] + " · " + seconds(stageMs) + d;

            StringBuilder crumb = new StringBuilder();
            for (int i = 0; i < STAGE_COUNT; i++) {
                if (i > 0) crumb.append(' ');
                crumb.append('S').append(i + 1);
                crumb.append(i < stage ? '+' : (i == stage ? '>' : '.'));
            }

            String c1 = "mods " + COUNTERS.getOrDefault("mods.done", 0L) + "/"
                    + COUNTERS.getOrDefault("mods.total", 0L)
                    + " · mixin " + COUNTERS.getOrDefault("mixin.classes", 0L)
                    + " · reg " + COUNTERS.getOrDefault("reg.total", 0L);
            String c2 = "def " + COUNTERS.getOrDefault("def.done", 0L) + "/"
                    + COUNTERS.getOrDefault("def.total", 0L)
                    + " · fail " + COUNTERS.getOrDefault("fail", 0L)
                    + " · warn " + COUNTERS.getOrDefault("warn", 0L)
                    + " · t " + seconds(totalMs);

            List<Snapshot> snaps = snapshot(maxMeters);
            List<String> lines = new ArrayList<>();
            int limit = Math.min(maxEvents, EVENTS.size());
            int skip = EVENTS.size() - limit;
            int i = 0;
            for (Event e : EVENTS) {
                if (i++ >= skip) lines.add("[" + e.channel() + "] " + e.text());
            }
            return new View(totalLabel, crumb.toString(), c1, c2,
                    segments(), snaps, lines, totalMs);
        }
    }

    private static String seconds(long ms) {
        return String.format(Locale.ROOT, "%.1fs", ms / 1000f);
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }
}
