package io.mirage.loader;

import io.mirage.api.log.MirageLog;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;

/**
 * 性能锚点：统一的计时与内存快照埋点（Phase 1 验收标准 6「性能与体积基线」）。
 *
 * <p><b>为什么需要它</b>：LS 的阶段日志只有 {@code [LS] stage S4 RegistryMerge done (7433 ms)}
 * 这种<b>阶段粒度</b>的耗时。S4 实测在 6.8 ~ 25.9 秒之间波动（4 倍），是唯一的大头，
 * 但阶段粒度**无法回答"这 7 秒花在哪"**——而日志里 S4 区间几乎没有任何输出，
 * 只看阶段耗时无法区分「在算」和「在等」。
 *
 * <p><b>做法</b>：在关键位置打一行<b>冻结格式</b>的锚点行：
 * <pre>{@code [LS] perf <label> t=<ms> heapUsed=<MB> heapPeak=<MB>}</pre>
 * 其中 {@code t} 是相对基线（Launcher.main 入口）的毫秒数。
 * 相邻两行相减即该段耗时；{@code heapPeak} 给堆峰值——
 * 这正是 {@code docs/perf-baseline.md} 里一直标「未测」的那个数字。
 *
 * <p><b>格式稳定性</b>：本行格式被 {@code scripts/measure_startup.ps1} 按正则解析，
 * 改动格式必须同步改脚本，否则脚本会静默漏读锚点（脚本自带缺口检测，会报「锚点缺失」）。
 * 用 {@link MirageLog#raw} 输出而非带级别词的形式，与 LS 阶段骨架行保持同一风格。
 *
 * <p><b>类加载</b>：本类在 {@code io.mirage.loader} 包下，与 {@link MirageDebug} 同包，
 * 由 app loader 加载（{@code io.mirage.*} 前缀强制委派），与调用方（launcher / lifecycle-starter）
 * 看到的是同一份类，不会出现两份基准时刻。
 */
public final class MiragePerf {

    /** 基准时刻。类初始化即取 {@code System.nanoTime()}，Launcher 入口会再显式重置一次。 */
    private static volatile long baselineNanos = System.nanoTime();

    /** 已打出的锚点数量（脚本侧用它判断「锚点是否被静默丢弃」）。 */
    private static volatile int markCount;

    private MiragePerf() {
    }

    /**
     * 重置计时基准。必须在进程真正的起点调用（本项目是 {@code Launcher.main} 的第一行），
     * 否则 {@code t=} 的含义会随类加载时机漂移。
     *
     * <p>本方法是<b>全进程唯一的时间零点</b>：同一个 {@code now} 也会传给
     * {@link LoadProgress#resetBaseline(long)}，让加载界面的秒表与这里的 {@code [LS] perf t=}
     * 用同一把尺子（验收 D3）。新增别的计时基准时必须从这里取值，不要另起一次
     * {@code System.nanoTime()}。
     */
    public static void resetBaseline() {
        long now = System.nanoTime();
        baselineNanos = now;
        // 同一个 now 同步给 UI：否则 LoadProgress 的 t= 从「类初始化」起算，
        // 本类的 t= 从「Launcher.main 第一行」起算，两者差一个类加载时机，
        // 验收 D3 的「UI 耗时与日志可对账」就不成立。两处只取一次 nanoTime。
        LoadProgress.resetBaseline(now);
    }

    /** 距基准的毫秒数。 */
    public static long elapsedMillis() {
        return (System.nanoTime() - baselineNanos) / 1_000_000L;
    }

    /** 已打出的锚点数量。 */
    public static int markCount() {
        return markCount;
    }

    /**
     * 打一个性能锚点。
     *
     * @param label 锚点名，用点号分层便于脚本归类，例如 {@code s4.bootstrap}、{@code titlescreen}
     */
    public static void mark(String label) {
        long t = elapsedMillis();
        int used = (int) (usedHeapBytes() / (1024L * 1024L));
        int peak = (int) (peakHeapBytes() / (1024L * 1024L));
        markCount++;
        MirageLog.MIRAGE.raw("[LS] perf " + label + " t=" + t
                + " heapUsed=" + used + " heapPeak=" + peak);
    }

    /** 当前堆已用字节（近似值：已分配 − 空闲，含已分配未用部分）。 */
    public static long usedHeapBytes() {
        Runtime rt = Runtime.getRuntime();
        return rt.totalMemory() - rt.freeMemory();
    }

    /**
     * 堆峰值字节（近似值）。
     *
     * <p>实现：把 JMX 里所有 {@link MemoryType#HEAP} 池的 {@code getPeakUsage()} 求和。
     * **这是近似值而非精确值**——G1 的 Eden 峰值与 Survivor 峰值分别记录，
     * 同一批对象可能在两个池里各被记一次，求和会略微高估。
     * 它的价值在于「同一台机器、同一个 mod 集合下可比」，而不是绝对精确。
     */
    public static long peakHeapBytes() {
        long peak = 0;
        try {
            for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
                if (pool.getType() == MemoryType.HEAP) {
                    java.lang.management.MemoryUsage usage = pool.getPeakUsage();
                    if (usage != null) {
                        peak += usage.getUsed();
                    }
                }
            }
        } catch (Throwable ignored) {
            // JMX 不可用（JVM 被裁剪 / 安全限制）时返回 0，不影响启动。
        }
        return peak;
    }
}
