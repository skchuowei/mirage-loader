package io.mirage.msr.agent;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * MSR P3 · 注入器深录 agent 主体（2026-11-07）。
 *
 * <p><b>范式转变</b>：P2 的 MSR 是「mod 侧 API 代理（fake）」—— 枚举 mod 碰了哪些 API，
 * 天然看不见 mod 直接调 MC 原版方法。P3 改为「<b>游戏侧状态观测 + diff 驱动</b>」：
 * 插桩点收敛在 MC / NeoForge 侧的有限几个类上，比枚举整个 mod API 面容易几个数量级。
 *
 * <p><b>为什么用 javaagent</b>：agent 是<b>构建期 / 开发期工具</b>，
 * <b>不进 MG 运行时也不进 fat jar</b>（用户日常启动路径完全不受影响）。
 * {@code premain} 在 JVM 启动最早期挂钩 {@link java.lang.instrument.Instrumentation}，
 * 比 MG 自己的类加载器早，因此能观测到「原版 NeoForge」路径 —— 而 MG 的 fake pass
 * 只能观测到 stub 路径。
 *
 * <p><b>用法</b>：
 * <pre>
 * java -javaagent:mirage-msr-agent.jar \
 *      -Dmirage.msr.out=&lt;输出目录&gt; \
 *      -Dmirage.msr.baseline=&lt;true|false&gt; \
 *      ... 正常启动参数 ...
 * </pre>
 *
 * <p><b>观测点</b>（见 {@link RecorderTransformer}）：
 * <ol>
 *   <li>{@code net.minecraft.core.MappedRegistry#register} → 注册表新增的 id；</li>
 *   <li>{@code net.neoforged.bus.EventBus#addListener} → 事件订阅（带 Class 的重载）；</li>
 *   <li>{@code net.neoforged.bus.EventListenerFactory#create} → {@code @SubscribeEvent} 主路径。</li>
 * </ol>
 *
 * <p><b>取值一律走反射</b>：agent 不编译期依赖 MC / NeoForge 类，
 * 且 MC 26.2 已把 {@code ResourceLocation} 改名为 {@code Identifier}
 * （{@code ResourceKey} 只剩 {@code identifier()} / {@code registry()}，没有 {@code location()}）。
 * 反射 + 失败降级，让 agent 跨版本不会因 API 改名而崩。
 *
 * <p><b>周期落盘</b>：每 2 秒写一次快照 —— 运行器会超时强杀进程，
 * 此时 shutdown hook 不会执行，只有周期快照能保住已观测到的数据。
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

    /**
     * 录制期间是否<b>不显示</b>游戏窗口（{@code -Dmirage.msr.hidewindow=false} 可关闭）。
     *
     * <p>默认<b>开</b>：P3 必须真跑客户端，但两轮录制（基线 + 录制）里没人需要看画面，
     * 窗口只会占屏。关掉它的唯一场景是「对着画面排查加载卡住」。
     */
    private static volatile boolean hideWindow =
            !"false".equalsIgnoreCase(System.getProperty("mirage.msr.hidewindow", "true"));

    /** 本轮采集到的注册表新增 id（owner → id 列表）。 */
    private static final List<Registration> REGISTRATIONS = Collections.synchronizedList(new ArrayList<>());

    /** 本轮采集到的事件订阅（owner → 事件类名）。 */
    private static final List<Subscription> SUBSCRIPTIONS = Collections.synchronizedList(new ArrayList<>());

    /**
     * 注册项去重键（registry|id）。
     *
     * <p><b>为什么需要去重</b>：{@code MappedRegistry} 有两个 register 重载
     * （3 参 / 4 参带 int id），其中一个会委托给另一个 ⇒ 同一个 id 被两个插桩点各记一次。
     * 两个重载都保留插桩（避免漏掉只走其中一个路径的注册），去重放在这里做。
     */
    private static final java.util.Set<String> SEEN_REGISTRATIONS =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** 订阅去重键（事件类名）。 */
    private static final java.util.Set<String> SEEN_SUBSCRIPTIONS =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** 观测异常计数（诊断用）。 */
    private static final AtomicLong OBSERVE_ERRORS = new AtomicLong();

    /** 落盘次数（诊断用）。 */
    private static final AtomicLong FLUSHES = new AtomicLong();

    /** 上次落盘时的记录数（用于「无变化不重写」）。 */
    private static volatile int lastFlushedRegs = -1;
    private static volatile int lastFlushedSubs = -1;

    /** 一条注册记录：owner = 归因的 modId（分不清时是 "unknown"）。 */
    public record Registration(String owner, String registry, String id) {}

    /** 一条事件订阅记录：owner = 归因的 modId。 */
    public record Subscription(String owner, String eventType) {}

    // ---- 反射句柄（懒加载 + 缓存）----
    private static volatile Method mIdentifier;
    private static volatile Method mRegistryKey;

    /**
     * premain 入口。
     *
     * @param args            agent 参数（本实现不消费，保留给后续扩展）
     * @param instrumentation JVM 插桩句柄
     */
    public static void premain(String args, java.lang.instrument.Instrumentation instrumentation) {
        String out = System.getProperty("mirage.msr.out");
        if (out != null && !out.isBlank()) outDir = out;
        baseline = Boolean.parseBoolean(System.getProperty("mirage.msr.baseline", "false"));
        hideWindow = !"false".equalsIgnoreCase(System.getProperty("mirage.msr.hidewindow", "true"));

        instrumentation.addTransformer(new RecorderTransformer(), true);
        // 窗口隐藏是**独立**的插桩器：它只摘 GLFW.glfwShowWindow，不碰观测点。
        // 放这里而不是塞进 RecorderTransformer，是为了让「录制」与「看不看得见画面」
        // 两件事各自可关、各自可测（门禁也分两组断言）。
        if (hideWindow) {
            instrumentation.addTransformer(new WindowHideTransformer(), true);
        }

        // 周期快照：超时强杀进程时 shutdown hook 不会执行，只有周期快照能保住数据
        Thread ticker = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(2000L);
                } catch (InterruptedException e) {
                    return;
                }
                flushQuietly();
            }
        }, "mirage-msr-ticker");
        ticker.setDaemon(true);
        ticker.start();

        // JVM 正常退出时把观测结果落盘
        Runtime.getRuntime().addShutdownHook(new Thread(RecorderAgent::flushQuietly, "mirage-msr-flush"));

        System.out.println("[MSR-P3] agent 已安装：out=" + outDir
                + " baseline=" + baseline + " hideWindow=" + hideWindow);
    }

    /**
     * 记录一次注册表注册（插桩入口）。
     *
     * @param key {@code net.minecraft.resources.ResourceKey} 实例
     */
    public static void onRegisterKey(Object key) {
        try {
            if (key == null) return;
            String id = extractIdentifier(key);
            String registry = extractRegistry(key);
            if (id == null || id.isEmpty()) return;
            if (!SEEN_REGISTRATIONS.add(registry + "|" + id)) return;
            // 归属以 id 的 namespace 为准（栈回溯在注册点上取不到 mod 帧 —— 见
            // ModIdResolver.resolveByRegistrationId 的说明）。
            REGISTRATIONS.add(new Registration(ModIdResolver.resolveByRegistrationId(id), registry, id));
        } catch (Throwable t) {
            OBSERVE_ERRORS.incrementAndGet();
        }
    }

    /**
     * 记录一次事件订阅（插桩入口）。
     *
     * @param typeSource 事件类型来源：{@code Class}（addListener 重载）或
     *                   {@code Method}（{@code @SubscribeEvent} 方法）
     * @param listener   监听器实例（可能是 lambda），用于归属判定
     */
    public static void onSubscribeEvent(Object typeSource, Object listener) {
        try {
            String eventType = extractEventType(typeSource);
            if (eventType == null || eventType.isEmpty()) return;
            // 归属先看监听器实例的 CodeSource（订阅点上栈里全是平台帧，栈回溯基本恒 unknown），
            // 取不到再退回栈回溯。
            String owner = ModIdResolver.resolveForInstance(listener);
            if ("unknown".equals(owner)) {
                owner = ModIdResolver.resolve(null);
            }
            // **去重键必须是 owner|event**：多个 mod 订阅同一个事件是常态。
            // 只按事件名去重会把后注册的 mod 整条吞掉 —— 2026-11-08 真实 mod 实测踩到：
            // AppleSkin 有 4 条 @SubscribeEvent 订阅（RenderTooltipEvent$GatherComponents /
            // RegisterPayloadHandlersEvent / PlayerLoggedInEvent / ServerTickEvent$Post）
            // 全被 jade / mezz_config / 基线的同事件先占位而丢失。
            if (!SEEN_SUBSCRIPTIONS.add(owner + "|" + eventType)) return;
            SUBSCRIPTIONS.add(new Subscription(owner, eventType));
        } catch (Throwable t) {
            OBSERVE_ERRORS.incrementAndGet();
        }
    }

    /**
     * 从 {@code ResourceKey} 取条目的 {@code identifier}（如 {@code minecraft:stone}）。
     *
     * <p>26.2 起 {@code ResourceLocation} 更名为 {@code Identifier}，
     * {@code ResourceKey} 的取值方法为 {@code identifier()} / {@code registry()}。
     */
    static String extractIdentifier(Object key) {
        try {
            Method m = mIdentifier;
            if (m == null) {
                m = key.getClass().getMethod("identifier");
                m.setAccessible(true);
                mIdentifier = m;
            }
            Object v = m.invoke(key);
            return v == null ? null : v.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 从 {@code ResourceKey} 取注册表名（如 {@code minecraft:block}）。
     *
     * <p><b>坑</b>：{@code ResourceKey.registry()} 返回的是「本 key 归属的 registry 的
     * <b>标识</b>」，对条目 key 而言恒为 {@code minecraft:root}（那是 registry 命名空间本身），
     * <b>不是</b> {@code minecraft:block} 这种具体注册表名。
     * 真正的注册表名要取 {@code registryKey().identifier()} —— 本轮实机实测踩过。
     */
    static String extractRegistry(Object key) {
        try {
            Method m = mRegistryKey;
            if (m == null) {
                m = key.getClass().getMethod("registryKey");
                m.setAccessible(true);
                mRegistryKey = m;
            }
            Object registryKey = m.invoke(key);
            if (registryKey == null) return "";
            // registryKey 本身也是 ResourceKey，它的 identifier() 才是 "minecraft:block"
            Method idm = registryKey.getClass().getMethod("identifier");
            idm.setAccessible(true);
            Object v = idm.invoke(registryKey);
            return v == null ? "" : v.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * 提取事件类型名。
     *
     * @param typeSource {@code Class} 或 {@code Method}
     * @return 事件类全名；取不到返回 null
     */
    static String extractEventType(Object typeSource) {
        if (typeSource == null) return null;
        try {
            if (typeSource instanceof Class<?> c) {
                return c.getName();
            }
            if (typeSource instanceof Method m) {
                Class<?>[] params = m.getParameterTypes();
                if (params.length == 0) return null;
                // @SubscribeEvent 方法的第一个参数即事件类型
                return params[0].getName();
            }
        } catch (Throwable t) {
            return null;
        }
        return null;
    }

    /** 落盘观测结果（吞掉一切异常 —— 观测绝不能搞崩游戏）。 */
    static void flushQuietly() {
        try {
            // **必须先加锁取快照**：REGISTRATIONS 是 synchronizedList，但它的迭代器
            // 仍需外部同步 —— 直接 new ArrayList<>(list) 在并发写入时会抛
            // ConcurrentModificationException（本轮实机日志里出现过多次）。
            List<Registration> regSnapshot;
            List<Subscription> subSnapshot;
            synchronized (REGISTRATIONS) {
                regSnapshot = new ArrayList<>(REGISTRATIONS);
            }
            synchronized (SUBSCRIPTIONS) {
                subSnapshot = new ArrayList<>(SUBSCRIPTIONS);
            }
            // 没有新数据就不重写（每 2 秒重写几百 KB 会白白拖慢游戏）
            if (regSnapshot.size() == lastFlushedRegs && subSnapshot.size() == lastFlushedSubs) {
                return;
            }
            lastFlushedRegs = regSnapshot.size();
            lastFlushedSubs = subSnapshot.size();
            MidIrWriter.write(outDir, baseline, regSnapshot, subSnapshot);
            FLUSHES.incrementAndGet();
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

    /** 录制期间是否隐藏游戏窗口（测试 / 诊断用）。 */
    public static boolean isHideWindow() {
        return hideWindow;
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
        SEEN_REGISTRATIONS.clear();
        SEEN_SUBSCRIPTIONS.clear();
        lastFlushedRegs = -1;
        lastFlushedSubs = -1;
    }

    /** 观测异常计数（诊断用）。 */
    public static long observeErrors() {
        return OBSERVE_ERRORS.get();
    }

    /** 落盘次数（诊断用）。 */
    public static long flushes() {
        return FLUSHES.get();
    }

    /** 诊断快照（测试用）。 */
    public static Map<String, Object> diagnostics() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("outDir", outDir);
        m.put("baseline", baseline);
        m.put("registrations", REGISTRATIONS.size());
        m.put("subscriptions", SUBSCRIPTIONS.size());
        m.put("observeErrors", OBSERVE_ERRORS.get());
        m.put("flushes", FLUSHES.get());
        m.put("instrumentedClasses", RecorderTransformer.instrumentedClasses());
        m.put("hideWindow", hideWindow);
        m.put("showWindowStripped", WindowHideTransformer.strippedCalls());
        m.put("hiddenClasses", WindowHideTransformer.touchedClasses());
        return m;
    }
}
