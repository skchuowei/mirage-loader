package io.mirage.msr.agent;

import java.lang.instrument.Instrumentation;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * MSR P3 · 注入器深录 agent 主体（2026-11-07）。
 *
 * <p><b>范式转变</b>：P2 的 MSR 是「mod 侧 API 代理（fake）」—— 枚举 mod 碰了哪些 API，
 * 天然看不见 mod 直接调 MC 原版方法。P3 改为「<b>游戏侧状态观测 + diff 驱动</b>」：
 * 插桩点收敛在 MC / NeoForge 侧的有限几个类上，比枚举整个 mod API 面容易几个数量级。
 *
 * <p><b>为什么用 javaagent</b>：agent 是<b>构建期 / 开发期工具</b>，
 * <b>不进 MG 运行时也不进 fat jar</b>（用户日常启动路径完全不受影响）。
 * {@code premain} 在 JVM 启动最早期挂钩 {@link Instrumentation}，
 * 比 MG 自己的类加载器早，因此能观测到「原版 NeoForge」路径 —— 而 MG 的 fake pass
 * 只能观测到 stub 路径。
 *
 * <p><b>用法</b>：
 * <pre>
 * java -javaagent:mirage-recorder-agent.jar \
 *      -Dmirage.msr.out=&lt;输出目录&gt; \
 *      -Dmirage.msr.baseline=&lt;true|false&gt; \
 *      ... 正常启动参数 ...
 * </pre>
 *
 * <p><b>观测点</b>（见 {@link RecorderTransformer}）：
 * <ol>
 *   <li>{@code net.minecraft.core.MappedRegistry#register} → 记录注册表新增的 id；</li>
 *   <li>{@code net.neoforged.bus.api.EventBus#addListener} → 记录事件订阅；</li>
 *   <li>{@code net.neoforged.fml.config.ModConfig} 相关 → 记录配置加载。</li>
 * </ol>
 *
 * <p><b>归属判定</b>：{@code DeferredRegister} 自带 modId 为主，栈回溯找 mod jar 类兜底；
 * 分不清的标「归属未知」，<b>不猜</b>（MSR 的一贯原则：宁可标未知，不要给错数据）。
 *
 * <p><b>合规边界</b>：本 agent 只做<b>观察</b>，不修改 NeoForge / MC 的语义，
 * 也不把它们的实现带入 MG（LGPL 2.1 的 clean-room 红线，见 AGENTS 合规边界）。
 */
public final class RecorderAgent {

    /** 输出目录（系统属性 {@code mirage.msr.out}）。 */
    private static volatile String outDir = System.getProperty("java.io.tmpdir");

    /** 是否为基线实例（基线实例不产出 mod 归因，只产出全量快照）。 */
    private static volatile boolean baseline =
            Boolean.parseBoolean(System.getProperty("mirage.msr.baseline", "false"));

    /** 本轮采集到的注册表新增 id（owner → id 列表）。 */
    private static final List<Registration> REGISTRATIONS = Collections.synchronizedList(new ArrayList<>());

    /** 本轮采集到的事件订阅（owner → 事件类名）。 */
    private static final List<Subscription> SUBSCRIPTIONS = Collections.synchronizedList(new ArrayList<>());

    /** 一条注册记录：owner = 归因的 modId（分不清时是 "unknown"）。 */
    public record Registration(String owner, String registry, String id) {}

    /** 一条事件订阅记录：owner = 归因的 modId。 */
    public record Subscription(String owner, String eventType) {}

    /**
     * premain 入口。
     *
     * @param args            agent 参数（本实现不消费，保留给后续扩展）
     * @param instrumentation JVM 插桩句柄
     */
    public static void premain(String args, Instrumentation instrumentation) {
        String out = System.getProperty("mirage.msr.out");
        if (out != null && !out.isBlank()) outDir = out;
        baseline = Boolean.parseBoolean(System.getProperty("mirage.msr.baseline", "false"));

        instrumentation.addTransformer(new RecorderTransformer(), true);

        // JVM 退出时把观测结果落盘（不能用 shutdownHook 改退出码，只写文件）
        Runtime.getRuntime().addShutdownHook(new Thread(RecorderAgent::flush, "mirage-msr-flush"));

        System.out.println("[MSR-P3] agent 已安装：out=" + outDir
                + " baseline=" + baseline);
    }

    /** 记录一次注册表注册。 */
    public static void onRegister(String registry, String method, String id) {
        REGISTRATIONS.add(new Registration(resolveOwner(null), registry + "|" + method, id));
    }

    /** 记录一次事件订阅。 */
    public static void onSubscribe(String bus, String method, String eventType) {
        SUBSCRIPTIONS.add(new Subscription(resolveOwner(null), bus + "|" + method + "|" + eventType));
    }

    /**
     * 归属判定：优先用 {@code DeferredRegister} 自带的 modId，
     * 其次用栈回溯找 mod jar 里的类，分不清就标「归属未知」。
     *
     * <p><b>不猜</b>：返回 {@code "unknown"} 时下游能明确知道这条数据不可归因，
     * 比给一个可能错的 modId 强。
     */
    public static String resolveOwner(String declaredModId) {
        if (declaredModId != null && !declaredModId.isBlank()) return declaredModId;
        // 栈回溯兜底：找第一个不在 NeoForge / MC / JDK 里的类
        try {
            for (StackTraceElement el : Thread.currentThread().getStackTrace()) {
                String cn = el.getClassName();
                if (cn.startsWith("net.minecraft.") || cn.startsWith("net.neoforged.")
                        || cn.startsWith("java.") || cn.startsWith("jdk.")
                        || cn.startsWith("io.mirage.msr.")) {
                    continue;
                }
                return "unknown:" + cn;
            }
        } catch (Throwable ignored) {
            // 栈回溯失败就走 unknown
        }
        return "unknown";
    }

    /** 落盘观测结果。 */
    static void flush() {
        try {
            MidIrWriter.write(outDir, baseline, REGISTRATIONS, SUBSCRIPTIONS);
        } catch (Throwable t) {
            System.err.println("[MSR-P3] 落盘失败: " + t);
        }
    }

    /** 当前输出目录（测试用）。 */
    public static String outDir() {
        return outDir;
    }

    /** 是否基线实例（测试用）。 */
    public static boolean isBaseline() {
        return baseline;
    }

    /** 已采集的注册记录（测试用）。 */
    public static List<Registration> registrations() {
        return new ArrayList<>(REGISTRATIONS);
    }

    /** 已采集的订阅记录（测试用）。 */
    public static List<Subscription> subscriptions() {
        return new ArrayList<>(SUBSCRIPTIONS);
    }

    /** 清空采集（测试用）。 */
    public static void reset() {
        REGISTRATIONS.clear();
        SUBSCRIPTIONS.clear();
    }
}
