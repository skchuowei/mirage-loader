package io.mirage.loader;

import io.mirage.api.log.MirageLog;

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
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * MSR W1 路线 B：真启动的<b>诊断</b> fallback（2026-11-13）。
 *
 * <p><b>用途边界（先看这个）</b>：这不是让 mod 跑起来的实现，而是「让一次真启动把
 * 自己缺哪些 neo 类完整说出来」的测量工具。fake pack 里的类方法体一律抛
 * {@code UnsupportedOperationException}，且超类解析是<b>沙盒形状</b>
 * （缺失的 {@code net.minecraft.*} 超类型回落 {@code java/lang/Object}，
 * 因为生成时 {@code --ref} 不含 {@code 26.2.jar}）。真启动 classpath 里有
 * 10372 个真 mc 类 ⇒ 继承关系对不上 ⇒ 开了这个开关之后「mod 加载过去了」
 * <b>不代表任何兼容性结论</b>，甚至可能把启动期 CNFE 换成更晚的 CCE / VerifyError。
 * 这是 C84 点名的假覆盖，所以必须显式双开关 + 横幅，绝不作为发布默认。
 *
 * <p><b>为什么不复用 {@code FakePackProvider}</b>：录制端那个 provider 的
 * {@code ALLOWED} 是 {@code {"net.neoforged.", "net.minecraft."}}。真启动侧一旦
 * 允许 {@code net.minecraft.}，pack 就能遮蔽 {@code 26.2.jar} 里的真 MC 类。
 * 本类另写一份更窄的闸（只认 {@code net.neoforged.}），录制端既有行为一行不改。
 *
 * <p><b>三道闸</b>：{@code -Dmirage.msr.fakepack.home=<dir>}（目录必须存在）+
 * {@code -Dmirage.msr.fakepack.runtime=on} + kill switch
 * {@code -Dmirage.msr.fakeprovider=off} 未设置。三者全满足才启用。
 * 任何一个不满足时，调用点只读一个静态 boolean 就返回，零 IO / 零日志。
 *
 * <p><b>接线契约</b>（见 W0 计划 §3 路线 B）：字节码必须由
 * {@link MirageClassLoader} 自己 {@code defineClass}，不进
 * {@code MirageTransformer} / {@code MirageMixinBootstrap} /
 * {@code LoadProgress.increment("mixin.classes")}；且只在
 * {@code readClassBytes(name) == null} 之后调用 ⇒ fat jar 的 141 个 compat stub
 * 与真 MC 类<b>天然优先</b>，不会撞名、不会打瘸 N1 已有功能。
 * <b>pack 目录绝不能挂进 MCL 的 URL</b>，否则 {@code getResource} 会直接命中、
 * fallback 永远走不到（门禁假绿）。
 */
public final class RuntimeFakePack {

    /** pack 家目录（与录制端 {@code FakePackProvider.HOME_PROPERTY} 同名，语义一致）。 */
    public static final String PROPERTY_HOME = "mirage.msr.fakepack.home";

    /** 运行时开关：必须显式等于 {@code on}。只给 home 不生效。 */
    public static final String PROPERTY_RUNTIME = "mirage.msr.fakepack.runtime";

    /** kill switch：值为 {@code off} 时整个 fallback 关闭（与 P2 录制端共用）。 */
    public static final String PROPERTY_KILL = "mirage.msr.fakeprovider";

    /** 命中清单落盘路径（可选）。不显式给就只打收尾日志，绝不默认往 gameDir 写。 */
    public static final String PROPERTY_OUT = "mirage.msr.fakepack.out";

    /** 唯一允许的包域：只认 neo。真 MC 类一律走 {@code 26.2.jar}。 */
    public static final String NEO_PREFIX = "net.neoforged.";

    private static final boolean ENABLED = initEnabled();
    private static final Path ROOT = initRoot();
    private static final Path OUT = initOut();
    private static final Set<String> HITS = ConcurrentHashMap.newKeySet();
    private static final AtomicBoolean BANNER_DONE = new AtomicBoolean(false);
    private static final boolean HOOK_REGISTERED = registerHook();

    private RuntimeFakePack() {}

    private static boolean initEnabled() {
        try {
            String home = System.getProperty(PROPERTY_HOME, "");
            String runtime = System.getProperty(PROPERTY_RUNTIME, "");
            String kill = System.getProperty(PROPERTY_KILL, "");
            if (home.isBlank() || !"on".equalsIgnoreCase(runtime)) return false;
            if ("off".equalsIgnoreCase(kill)) return false;
            return Files.isDirectory(Paths.get(home).toAbsolutePath().normalize());
        } catch (Throwable t) {
            // 属性读取 / 路径解析异常一律退化为「关闭」，绝不因诊断能力拖垮启动。
            return false;
        }
    }

    private static Path initRoot() {
        try {
            String home = System.getProperty(PROPERTY_HOME, "");
            return home.isBlank() ? null : Paths.get(home).toAbsolutePath().normalize();
        } catch (Throwable t) {
            return null;
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
        if (!ENABLED) return false;
        try {
            Runtime.getRuntime().addShutdownHook(
                    new Thread(RuntimeFakePack::onExit, "mirage-fakepack-runtime"));
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static void onExit() {
        try {
            MirageLog.LOADER.warn("FAKE MODE 收尾：命中 " + HITS.size()
                    + " 个 neo 类 -> " + new TreeSet<>(HITS));
            if (OUT != null) flush(OUT);
        } catch (Throwable t) {
            System.err.println("[Loader] WARN fakepack 命中清单落盘失败: " + t);
        }
    }

    public static boolean isEnabled() { return ENABLED; }

    public static boolean hookRegistered() { return HOOK_REGISTERED; }

    public static Path root() { return ROOT; }

    public static Path outputPath() { return OUT; }

    public static boolean bannerSeen() { return BANNER_DONE.get(); }

    /**
     * 资源缺失时按需取 fake 字节码。未启用 / 非 neo 前缀 / 文件不存在都返回 {@code null}，
     * 由调用方继续走 {@code super.findClass(name)} 抛 CNFE。
     */
    public static byte[] findBytesIfEnabled(String name) {
        if (!ENABLED) return null;
        if (name == null || !name.startsWith(NEO_PREFIX)) return null;
        if (ROOT == null) return null;
        try {
            Path f = ROOT.resolve(name.replace('.', '/') + ".class").normalize();
            if (!f.startsWith(ROOT) || !Files.isRegularFile(f)) return null;
            byte[] b = Files.readAllBytes(f);
            HITS.add(name);
            banner();
            return b;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static void banner() {
        if (!BANNER_DONE.compareAndSet(false, true)) return;
        MirageLog.LOADER.warn("FAKE MODE 已启用（仅诊断）：pack 只提供 UOE 骨架、"
                + "mc 超类回落 Object 的沙盒形状，方法调用会抛 UnsupportedOperationException；"
                + "「加载成功」不代表兼容性结论，禁止作为发布默认。home=" + ROOT);
    }

    public static int hitCount() { return HITS.size(); }

    /** 排序快照（不暴露内部集合）。 */
    public static Set<String> snapshot() {
        return new TreeSet<>(HITS);
    }

    /** 仅供离线门禁在场景之间清场；生产代码不调用。 */
    public static void clearForTest() { HITS.clear(); }

    /**
     * 命中清单写成 {@code --only-file} 能吃的格式（{@code #} 头注释 + 一行一个点分类名）。
     *
     * @return 写入的类名条数
     */
    public static int flush(Path target) throws IOException {
        if (target == null) return 0;
        List<String> lines = new ArrayList<>();
        lines.add("# Mirage MSR W1 诊断 fallback 命中清单 (RuntimeFakePack)");
        lines.add("# 每行一个点分全限定类名；仅诊断，不代表兼容性结论");
        lines.addAll(snapshot());
        Path parent = target.getParent();
        if (parent != null) Files.createDirectories(parent);
        Files.write(target, lines, StandardCharsets.UTF_8);
        return HITS.size();
    }
}
