package io.mirage.loader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MSR W1 路线 A：真启动缺口探针（2026-11-13）。
 *
 * <p><b>它做什么</b>：真启动时，把「加载器自己实际撞到、而 classpath 里没有」的
 * {@code net.neoforged.*} 类名记下来，落盘成可直接喂
 * {@code FakeGenCli --only-file} 的清单。P3 的 MID 是<b>沙盒录制</b>得到的缺口；
 * 这个类把口径升级成「真启动实际撞到的缺口」，闭环更准。
 *
 * <p><b>它绝不做什么</b>：不改类加载结果。调用点在
 * {@link MirageClassLoader#findClass(String)} 的资源缺失分支，记完账之后
 * 仍走 {@code super.findClass(name)} 抛 {@code ClassNotFoundException}。
 * 不生成类、不 defineClass、不提供任何 fallback —— 一旦提供，就变成 C84 点名的
 * 「把启动期明确失败换成运行期晚失败」的假覆盖。
 *
 * <p><b>默认关闭</b>：必须显式给 {@code -Dmirage.msr.missingneo=on}。
 * 这是 W0 不变量 1「默认零变化」的硬要求：没给开关时，MCL 连磁盘都不查、
 * 不打日志、不写集合，行为与接线前逐字节一致。落盘路径也必须显式给
 * {@code -Dmirage.msr.missingneo.out=<file>} —— 绝不默认往 {@code <gameDir>} 写，
 * 以免碰用户的 {@code mc-shili/}（AGENTS 规则 8）。
 *
 * <p><b>为什么单独成类</b>：纯 JDK、无游戏类依赖，可以在 headless JVM 探针里
 * 对着真实生产代码跑门禁，而不是测一份副本（同 {@link GameDirs} 的思路）。
 */
public final class MissingNeoTracker {

    /** 开关属性：值必须等于 {@code on}（忽略大小写）才启用。 */
    public static final String PROPERTY_ENABLE = "mirage.msr.missingneo";

    /** 清单落盘路径属性：不显式给就只留内存快照 + 收尾日志，不写任何文件。 */
    public static final String PROPERTY_OUT = "mirage.msr.missingneo.out";

    /** 唯一允许记账的包域。{@code net.minecraft.*} 一律不记（真 MC 类由 26.2.jar 提供）。 */
    public static final String NEO_PREFIX = "net.neoforged.";

    private static final boolean ENABLED = initEnabled();
    private static final Path OUT = initOut();
    private static final Set<String> MISSING = ConcurrentHashMap.newKeySet();
    private static final boolean HOOK_REGISTERED = registerHook();

    private MissingNeoTracker() {}

    private static boolean initEnabled() {
        try {
            return "on".equalsIgnoreCase(System.getProperty(PROPERTY_ENABLE, ""));
        } catch (Throwable t) {
            // SecurityManager / 属性读取异常一律退化为「关闭」，绝不因诊断能力拖垮启动。
            return false;
        }
    }

    private static Path initOut() {
        try {
            String v = System.getProperty(PROPERTY_OUT);
            return (v == null || v.isBlank()) ? null : Paths.get(v).toAbsolutePath().normalize();
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean registerHook() {
        if (!ENABLED || OUT == null) return false;
        try {
            // 启动中途 FATAL 也要保住清单；显式 flush(Path) 仍可提前确定性落盘。
            Runtime.getRuntime().addShutdownHook(
                    new Thread(MissingNeoTracker::flushOnExit, "mirage-missing-neo"));
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static void flushOnExit() {
        try {
            flush(OUT);
        } catch (Throwable t) {
            // 落盘失败只告警，绝不升级成启动 FATAL。
            System.err.println("[Loader] WARN missing-neo 清单落盘失败: " + t);
        }
    }

    public static boolean isEnabled() { return ENABLED; }

    public static boolean hookRegistered() { return HOOK_REGISTERED; }

    public static Path outputPath() { return OUT; }

    /**
     * 资源缺失时记账。未启用或不在 neo 包域时立即返回（零 IO / 零日志）。
     */
    public static void recordIfEnabled(String name) {
        if (!ENABLED) return;
        if (name == null || !name.startsWith(NEO_PREFIX)) return;
        MISSING.add(name);
    }

    /** 排序快照（不暴露内部集合）。 */
    public static Set<String> snapshot() {
        return new TreeSet<>(MISSING);
    }

    public static int size() { return MISSING.size(); }

    /** 仅供离线门禁在场景之间清场；生产代码不调用。 */
    public static void clearForTest() { MISSING.clear(); }

    /**
     * 把清单写成 {@code --only-file} 能吃的格式：{@code #} 头注释 + 一行一个点分类名。
     * 父目录不存在时一并创建（目标由调用方显式指定，不写用户游戏目录）。
     *
     * @return 写入的类名条数
     */
    public static int flush(Path target) throws IOException {
        if (target == null) return 0;
        List<String> lines = new ArrayList<>();
        lines.add("# Mirage MSR W1 真启动缺口探针 (MissingNeoTracker)");
        lines.add("# 每行一个点分全限定类名；消费方式: FakeGenCli --only-file <this file>");
        lines.addAll(snapshot());
        Path parent = target.getParent();
        if (parent != null) Files.createDirectories(parent);
        Files.write(target, lines, StandardCharsets.UTF_8);
        return MISSING.size();
    }
}
