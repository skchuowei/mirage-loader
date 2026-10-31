package io.mirage.loader.passive;

import io.mirage.api.hook.PassiveHooks;
import io.mirage.api.log.MirageLog;

import io.mirage.loader.MidCache;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * MSR P2 · 被动捕获器：把"录制"变成真实加载的副产品。
 *
 * <p>P1 之前，一个 NeoForge mod 的构造函数要跑两遍（沙盒一遍、真实加载一遍），
 * 副作用双发。P2 之后：沙盒只在**缺版本区间**等少数情况下按需跑；
 * 其余 mod 的 API 触达 / 事件注册 / DeferredRegister 计数，全部在真实加载窗口里顺手记下。
 *
 * <p><b>窗口模型</b>：S6 是串行的（MG-Lifecycle 要求"构造顺序即加载图顺序"），
 * 因此同一时刻只存在一个窗口，用静态 {@code volatile} 持有即可，不做 ThreadLocal。
 * 窗口期外所有 hook 都是 no-op（{@link PassiveHooks} 的汇点在窗口关闭时清空），
 * 所以游戏运行期（tick、事件派发）的调用不会被误记。
 *
 * <p><b>如实记录的能力边界</b>：
 * 本类只看得见**我们自己代码里的入口**（`io.mirage.api.*`、compat stub、事件总线）。
 * mod 直接调用 MC 原版方法（`level.addFreshEntity` 之类）这里看不到 —— 那需要字节码插桩，
 * 属 roadmap P3。所以被动捕获的 {@code touched_apis} 天然比沙盒（fake 表面全量）窄，
 * 两者合并取并集，谁都不是全集。
 */
public final class PassiveRecorder {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 当前采集窗口；null = 不在采集。 */
    private static volatile Window current;

    private static volatile boolean hooksInstalled;

    /** 本轮被动捕获的统计（日志汇总用）。 */
    private static final AtomicInteger captured = new AtomicInteger();
    private static final AtomicInteger incomplete = new AtomicInteger();

    /** 一个 mod 的采集窗口。 */
    private static final class Window {
        final String modId;
        final long start = System.currentTimeMillis();
        final Set<String> apis = Collections.synchronizedSet(new LinkedHashSet<>());
        final Set<String> events = Collections.synchronizedSet(new LinkedHashSet<>());
        final AtomicInteger drCount = new AtomicInteger();
        volatile boolean complete = true;
        volatile String failReason;

        Window(String modId) {
            this.modId = modId;
        }
    }

    /** 采集结果。 */
    public record Result(String modId, List<String> apis, List<String> events,
                         int deferredRegisterCount, boolean complete, String failReason,
                         long durationMs) {}

    // ------------------------------------------------------------------ 窗口

    /**
     * 开始采集某个 mod。
     *
     * @param modId mod id（仅用于日志与结果归属）
     */
    public static void begin(String modId) {
        ensureHooks();
        Window prev = current;
        if (prev != null) {
            // 理论上 S6 串行不会发生；真发生了说明有阶段嵌套，如实告警并丢弃旧窗口
            MirageLog.of("Mirage:MID").warn("被动捕获窗口被重入（" + prev.modId + " → " + modId
                    + "），上一个窗口的数据丢弃");
        }
        current = new Window(modId);
    }

    /** 标记当前 mod 采集不完整（某个入口构造/初始化抛错）。 */
    public static void markFailure(String reason) {
        Window w = current;
        if (w == null) return;
        w.complete = false;
        if (w.failReason == null) w.failReason = reason;
    }

    /**
     * 结束采集。
     *
     * @return 采集结果；没有窗口时返回 null
     */
    public static Result end() {
        Window w = current;
        current = null;
        if (w == null) return null;
        captured.incrementAndGet();
        if (!w.complete) incomplete.incrementAndGet();
        List<String> apis;
        List<String> events;
        synchronized (w.apis) { apis = sorted(w.apis); }
        synchronized (w.events) { events = sorted(w.events); }
        return new Result(w.modId, apis, events, w.drCount.get(), w.complete, w.failReason,
                System.currentTimeMillis() - w.start);
    }

    /** 当前是否有窗口（测试与诊断用）。 */
    public static boolean active() {
        return current != null;
    }

    /** 本轮统计：[0]=窗口数，[1]=不完整数。 */
    public static int[] stats() {
        return new int[]{captured.get(), incomplete.get()};
    }

    /** 清空统计（测试用）。 */
    public static void resetStats() {
        captured.set(0);
        incomplete.set(0);
    }

    // ------------------------------------------------------------ 结果 → 载荷

    /** 采集结果转成 MID 载荷（{@code incomplete} 的永不入库由 {@link MidCache#storePassive} 把关）。 */
    public static MidCache.Recorded toRecorded(Result r) {
        MidCache.Recorded out = new MidCache.Recorded();
        out.touched_apis = new ArrayList<>(r.apis());
        out.registered_events = new ArrayList<>(r.events());
        out.deferred_register_count = r.deferredRegisterCount();
        out.record_duration_ms = r.durationMs();
        out.failed = !r.complete();
        out.error = r.complete() ? null : ("incomplete: " + (r.failReason() == null ? "unknown" : r.failReason()));
        out.incomplete = !r.complete();
        out.complete = r.complete();
        out.source = "passive";
        out.recorded_loader_version = "";
        out.recorded_mc_version = "";
        return out;
    }

    // ------------------------------------------------------------------ diff

    /**
     * 二启 diff：上一份 recorded（磁盘）vs 本轮被动采集。
     *
     * @return 差异行；无差异返回空列表
     */
    public static List<String> diff(MidCache.Recorded prev, MidCache.Recorded cur) {
        List<String> lines = new ArrayList<>();
        if (prev == null || cur == null) return lines;

        List<String> addedApis = minus(cur.touched_apis, prev.touched_apis);
        List<String> removedApis = minus(prev.touched_apis, cur.touched_apis);
        List<String> addedEvents = minus(cur.registered_events, prev.registered_events);
        List<String> removedEvents = minus(prev.registered_events, cur.registered_events);

        if (!addedApis.isEmpty()) lines.add("+apis(" + addedApis.size() + "): " + join(addedApis));
        if (!removedApis.isEmpty()) lines.add("-apis(" + removedApis.size() + "): " + join(removedApis));
        if (!addedEvents.isEmpty()) lines.add("+events(" + addedEvents.size() + "): " + join(addedEvents));
        if (!removedEvents.isEmpty()) lines.add("-events(" + removedEvents.size() + "): " + join(removedEvents));
        if (prev.deferred_register_count != cur.deferred_register_count) {
            lines.add("dr: " + prev.deferred_register_count + " -> " + cur.deferred_register_count);
        }
        return lines;
    }

    /**
     * 追加写 {@code .mid-cache/<mcVer>/mid-diff.log}。写失败只 debug（诊断产物，不阻断启动）。
     */
    public static void appendDiffReport(Path cacheRoot, String modId, List<String> lines) {
        if (cacheRoot == null || lines == null || lines.isEmpty()) return;
        Path log = cacheRoot.resolve("mid-diff.log");
        StringBuilder sb = new StringBuilder();
        sb.append('[').append(LocalDateTime.now().format(TS)).append("] ").append(modId).append('\n');
        for (String l : lines) sb.append("    ").append(l).append('\n');
        try {
            Files.writeString(log, sb.toString(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            MirageLog.of("Mirage:MID").debug("写 diff 报告失败: " + log, e);
        }
    }

    // ------------------------------------------------------------ 钩子安装

    /** 幂等安装（首次 {@link #begin} 时调用）。 */
    private static void ensureHooks() {
        if (hooksInstalled) return;
        synchronized (PassiveRecorder.class) {
            if (hooksInstalled) return;
            PassiveHooks.apiSink = PassiveRecorder::onApi;
            PassiveHooks.eventSink = PassiveRecorder::onEvent;
            PassiveHooks.deferredRegisterSink = PassiveRecorder::onDeferredRegister;
            hooksInstalled = true;
        }
    }

    private static void onApi(String api) {
        Window w = current;
        if (w != null) w.apis.add(api);
    }

    private static void onEvent(String eventClass) {
        Window w = current;
        if (w != null) w.events.add(eventClass);
    }

    private static void onDeferredRegister() {
        Window w = current;
        if (w != null) w.drCount.incrementAndGet();
    }

    // ------------------------------------------------------------------ 工具

    private static List<String> sorted(Set<String> s) {
        List<String> l = new ArrayList<>(s);
        l.sort(null);
        return l;
    }

    private static List<String> minus(List<String> a, List<String> b) {
        if (a == null || a.isEmpty()) return List.of();
        Set<String> base = new LinkedHashSet<>(b == null ? List.of() : b);
        List<String> out = new ArrayList<>();
        for (String s : a) if (!base.contains(s)) out.add(s);
        out.sort(null);
        return out;
    }

    private static String join(List<String> l) {
        return String.join(", ", l);
    }

    private PassiveRecorder() {}
}
