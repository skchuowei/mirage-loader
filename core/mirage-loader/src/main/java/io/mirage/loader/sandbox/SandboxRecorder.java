package io.mirage.loader.sandbox;

import io.mirage.api.log.MirageLog;

import io.mirage.loader.MidCache;
import io.mirage.loader.NeoForgeModDetector;

import java.io.File;
import java.lang.reflect.Constructor;
import java.net.URL;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.TimeZone;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * MSR-neo 主控：在隔离沙盒里跑 NeoForge mod 主类的 &lt;clinit&gt; + 构造函数，
 * 收集 {@link MidCache.Recorded}。
 *
 * <p>不 fork 子 JVM：在 launcher 进程内起一个一次性 {@link SandboxClassLoader}，
 * 2 秒超时熔断；任何 {@link Throwable} 都降级为 {@code failed=true}，绝不抛出。
 *
 * <p>录制的三块：
 * <ol>
 *   <li>{@code recorded_loader_version} / {@code recorded_mc_version}：来自
 *       {@link NeoForgeModDetector.Detected} 解析的依赖版本区间；</li>
 *   <li>{@code touched_apis} / {@code registered_events} / {@code deferred_register_count}：
 *       fake 表面通过 {@link Sink} 写入；</li>
 *   <li>{@code failed} / {@code error} / {@code incomplete}：构造崩溃 / 超时 / 缺 fake 表面。</li>
 * </ol>
 *
 * <p><b>MSR P1 加固（进程内止血）</b>：
 * <ul>
 *   <li>{@link SandboxKillException}：mod 的 {@code System.exit}/{@code Runtime.halt} 已被
 *       {@link SandboxBytecodeGuard} 改道，不再杀 launcher；</li>
 *   <li>超时 {@code interrupt} + {@code incomplete=true}，<b>半成品数据一律不入库</b>；</li>
 *   <li>录制前快照 / 录制后恢复 {@link Locale}、{@link TimeZone}、{@link System#getProperties()}；</li>
 *   <li>{@link #fakeVersion()}：fake 表面指纹，进 {@code .mid-cache} key，表面扩充后老缓存自动失效。</li>
 * </ul>
 * <p>进程内仍然拦不住的（已知边界，由 roadmap P3「实例即耗材」消化）：
 * 文件/网络副作用、{@code Runtime.exec}、反射拿到的 exit {@code Method}。
 */
public final class SandboxRecorder {

    /** 构造超时（毫秒）。超时视为"构造函数有副作用"，降级 failed + incomplete。 */
    private static final long TIMEOUT_MS = 2000L;

    /**
     * 录制一个 NeoForge mod 主类。
     *
     * @param jar     mod jar 路径
     * @param detected 已识别的元数据（含 mainClass / neoForgeVersionRange / mcVersionRange）
     * @return 录制结果（永不返回 null；失败时 {@code failed=true}）
     */
    public static MidCache.Recorded record(Path jar, NeoForgeModDetector.Detected detected) {
        MidCache.Recorded r = new MidCache.Recorded();
        r.touched_apis = new ArrayList<>();
        r.registered_events = new ArrayList<>();
        r.recorded_loader_version = detected.neoForgeVersionRange != null ? detected.neoForgeVersionRange : "";
        r.recorded_mc_version = detected.mcVersionRange != null ? detected.mcVersionRange : "";

        String mainClass = detected.mainClass;
        if (mainClass == null || mainClass.isEmpty()) {
            MirageLog.MIRAGE.debug("[Sandbox] " + detected.id + " 无主类，跳过录制");
            r.failed = true;
            r.error = "no main class";
            return r;
        }
        MirageLog.MIRAGE.debug("[Sandbox] 开始录制 " + detected.id + " mainClass=" + mainClass
                + " fake=" + fakeVersion());

        long start = System.currentTimeMillis();
        // 全局态快照必须在 mod 代码跑起来之前、Sink 挂上之时：之后无论怎么失败都要恢复。
        StateSnapshot snapshot = StateSnapshot.capture();
        RecordingSinkImpl sink = new RecordingSinkImpl();
        Sink.current = sink;

        SandboxClassLoader loader = null;
        try {
            // 一次扫描同时得到 fake 包前缀集合与表面指纹（表面变动 → 指纹变 → 老缓存失效）
            FakeSurface surface = FakeSurface.get();
            URL[] urls = new URL[]{ jar.toUri().toURL() };
            loader = new SandboxClassLoader(urls, SandboxRecorder.class.getClassLoader(), surface.packages);

            // 在独立线程跑，主线程 join 超时熔断
            final SandboxClassLoader cl = loader;
            final String mc = mainClass;
            final Throwable[] err = new Throwable[1];
            Thread t = new Thread(() -> {
                try {
                    // 1) <clinit>（含 static final DeferredRegister 字段初始化）
                    Class<?> c = Class.forName(mc, true, cl);
                    // 2) 构造函数：优先无参；回退 IEventBus 注入式
                    Object inst = construct(c, cl);
                    if (inst == null) {
                        err[0] = new NoSuchMethodException("无可用构造函数（无参 / IEventBus 注入）");
                    }
                } catch (Throwable ex) {
                    err[0] = ex;
                }
            }, "mirage-sandbox-" + detected.id);
            t.setDaemon(true);
            t.start();
            t.join(TIMEOUT_MS);

            if (t.isAlive()) {
                // best-effort：interrupt 拦得住 sleep/wait/可中断阻塞，拦不住纯 CPU 自旋
                // （Java 25 已无 Thread.stop，进程内不承诺硬杀 —— 硬杀只有 P3 的子进程能做）。
                t.interrupt();
                r.failed = true;
                r.incomplete = true;
                r.error = "timeout";
                MirageLog.MIRAGE.warn("[Sandbox] " + detected.id + " 录制超时 " + TIMEOUT_MS
                        + "ms，已 interrupt；半成品数据不入库");
            } else if (err[0] != null) {
                r.failed = true;
                r.error = describe(err[0]);
                if (err[0] instanceof SandboxKillException) {
                    MirageLog.MIRAGE.warn("[Sandbox] " + detected.id
                            + " 构造里请求退出进程，已拦截: " + r.error);
                }
            } else {
                r.failed = false;
            }

            int rewritten = loader.rewrittenCalls();
            if (rewritten > 0) {
                MirageLog.MIRAGE.debug("[Sandbox] " + detected.id
                        + " 共改写 " + rewritten + " 处退出调用");
            }
        } catch (Throwable e) {
            // 沙盒自身失败（fake 资源缺失、URL 失败等）
            r.failed = true;
            r.error = describe(e);
        } finally {
            if (r.incomplete) {
                // 半成品一律不入库：宁可这次没数据，也不要把半截 touched_apis 写进缓存。
                r.touched_apis = new ArrayList<>();
                r.registered_events = new ArrayList<>();
                r.deferred_register_count = 0;
            } else {
                r.touched_apis = sink.apis();
                r.registered_events = sink.events();
                r.deferred_register_count = sink.deferredRegisterCount();
            }
            r.record_duration_ms = System.currentTimeMillis() - start;
            Sink.current = null;
            if (loader != null) {
                try { loader.close(); } catch (Exception ignore) {}
            }
            // 最后恢复全局态：mod 在构造里改的 Locale / TimeZone / 系统属性不能留在 launcher 进程里
            snapshot.restore();
        }
        return r;
    }

    /**
     * fake 表面指纹（fat jar 内 {@code mirage/sandbox-fake/**} 的确定性摘要）。
     * 作为 {@code .mid-cache} 的一个校对维度：fake 表面扩充/修改后，老 {@code recorded} 自动失效重录。
     */
    public static String fakeVersion() {
        return FakeSurface.get().version;
    }

    /** 构造主类实例：优先无参，失败回退 {@code (IEventBus)} 注入式。 */
    private static Object construct(Class<?> cls, ClassLoader cl) throws Exception {
        try {
            Constructor<?> c = cls.getDeclaredConstructor();
            c.setAccessible(true);
            return c.newInstance();
        } catch (NoSuchMethodException noArg) {
            // 回退 IEventBus 注入式（NeoForge 4.x 标准写法）
            Class<?> busCls = Class.forName("net.neoforged.bus.api.IEventBus", false, cl);
            Constructor<?> c = cls.getDeclaredConstructor(busCls);
            c.setAccessible(true);
            Object bus = Class.forName("net.neoforged.fml.javafmlmod.FMLJavaModLoadingContext", true, cl)
                    .getMethod("get").invoke(null);
            bus = bus.getClass().getMethod("getModEventBus").invoke(bus);
            return c.newInstance(bus);
        }
    }

    /** 把异常描述成"类名 + message + cause 链"，截断 500 字符。 */
    private static String describe(Throwable t) {
        StringBuilder sb = new StringBuilder();
        Throwable cur = t;
        int depth = 0;
        while (cur != null && depth < 5) {
            if (depth > 0) sb.append(" <- ");
            sb.append(cur.getClass().getSimpleName());
            if (cur.getMessage() != null && !cur.getMessage().isEmpty()) {
                sb.append(": ").append(cur.getMessage());
            }
            cur = cur.getCause();
            depth++;
        }
        String msg = sb.toString();
        if (msg.length() > 500) msg = msg.substring(0, 500);
        return msg;
    }

    // ------------------------------------------------------------------ 全局态

    /**
     * 录制前后的全局态快照。只覆盖 mod 构造里最常见、且会污染 launcher 的三类：
     * 默认 Locale、默认时区、系统属性表。
     */
    private static final class StateSnapshot {

        private final Locale locale;
        private final TimeZone timeZone;
        private final Properties props;

        private StateSnapshot(Locale locale, TimeZone timeZone, Properties props) {
            this.locale = locale;
            this.timeZone = timeZone;
            this.props = props;
        }

        static StateSnapshot capture() {
            return new StateSnapshot(
                    Locale.getDefault(),
                    TimeZone.getDefault(),
                    (Properties) System.getProperties().clone());
        }

        void restore() {
            try {
                Locale.setDefault(locale);
                TimeZone.setDefault(timeZone);

                Properties cur = System.getProperties();
                // mod 新加的键全部删掉（先收集再删，避免 ConcurrentModificationException）
                List<String> added = new ArrayList<>();
                for (String key : cur.stringPropertyNames()) {
                    if (!props.containsKey(key)) added.add(key);
                }
                for (String key : added) cur.remove(key);
                // 原有键逐个写回（mod 覆盖过的值在此复位）
                for (String key : props.stringPropertyNames()) {
                    cur.setProperty(key, props.getProperty(key));
                }
            } catch (Throwable t) {
                MirageLog.MIRAGE.warn("[Sandbox] 全局态恢复失败（mod 的 Locale/属性改动可能残留）", t);
            }
        }
    }

    // ------------------------------------------------------------ fake 表面扫描

    /**
     * fake 表面：包前缀集合 + 指纹。进程内只扫一次（fat jar 条目遍历，2.6 MB 量级，可忽略）。
     */
    private static final class FakeSurface {

        private static volatile FakeSurface instance;

        final Set<String> packages;
        final String version;

        private FakeSurface(Set<String> packages, String version) {
            this.packages = packages;
            this.version = version;
        }

        static FakeSurface get() {
            FakeSurface s = instance;
            if (s != null) return s;
            synchronized (FakeSurface.class) {
                if (instance == null) instance = scan();
                return instance;
            }
        }

        private static FakeSurface scan() {
            Set<String> pkgs = new LinkedHashSet<>();
            Set<String> fingerprints = new LinkedHashSet<>();

            // (a) 首选：本类自己的 code source（生产 = fat jar，dev = build/classes 目录）。
            //     这是唯一不依赖"资源目录条目存在"的定位方式，也是 P0 那条实现踩坑的地方。
            URL self = selfLocation();
            if (self != null && "file".equals(self.getProtocol())) {
                try {
                    File f = new File(self.toURI());
                    if (f.isFile()) {
                        collectJar(f, SandboxClassLoader.FAKE_PREFIX, pkgs, fingerprints);
                    } else {
                        File fakeDir = new File(f, SandboxClassLoader.FAKE_PREFIX);
                        if (fakeDir.isDirectory()) collectDir(fakeDir, fakeDir, pkgs, fingerprints);
                    }
                } catch (Exception ignore) {}
            }

            // (b) 补扫：类路径上任何暴露 fake 资源前缀的位置（多 jar 叠放 / 自定义 classpath 场景）
            try {
                Enumeration<URL> res = SandboxRecorder.class.getClassLoader()
                        .getResources(SandboxClassLoader.FAKE_PREFIX);
                while (res.hasMoreElements()) {
                    URL u = res.nextElement();
                    if ("jar".equals(u.getProtocol())) {
                        collectFromJarUrl(u, pkgs, fingerprints);
                    } else if ("file".equals(u.getProtocol())) {
                        collectFromDir(u, pkgs, fingerprints);
                    }
                }
            } catch (Exception ignore) {
                // 扫不到就退化成"无指纹"，不影响录制
            }

            if (pkgs.isEmpty()) {
                // 兜底：标准 fake 包前缀（指纹记 none，说明此刻无法判断表面是否变化）
                pkgs.add("net.minecraft.");
                pkgs.add("net.neoforged.");
                return new FakeSurface(pkgs, "none");
            }
            return new FakeSurface(pkgs, digest(fingerprints));
        }

        private static URL selfLocation() {
            try {
                java.security.CodeSource cs = SandboxRecorder.class.getProtectionDomain().getCodeSource();
                return (cs != null) ? cs.getLocation() : null;
            } catch (Throwable t) {
                return null;
            }
        }

        /**
         * 从 {@code jar:file:/x.jar!/<prefix>} 形式的 URL 扫。
         *
         * <p><b>坑</b>：{@code u.getPath()} 返回的是 {@code file:/D:/x.jar!/mirage/...}，
         * 直接 {@code new File(...)} 在 Windows 上是非法路径并抛异常 —— P0 的实现就是这么被静默吞掉、
         * 一直靠兜底白名单工作的。这里必须先切出 {@code file:/D:/x.jar} 再按 URI 转 File。
         */
        private static void collectFromJarUrl(URL u, Set<String> pkgs, Set<String> fingerprints) {
            try {
                String path = u.getPath();
                int bang = path.indexOf("!/");
                if (bang < 0) return;
                URL jarUrl = java.net.URI.create(path.substring(0, bang)).toURL();
                File jarFile = new File(jarUrl.toURI());
                String prefix = path.substring(bang + 2); // 去掉 !/，如 mirage/sandbox-fake/
                collectJar(jarFile, prefix, pkgs, fingerprints);
            } catch (Exception ignore) {}
        }

        private static void collectJar(File jarFile, String prefix,
                                       Set<String> pkgs, Set<String> fingerprints) {
            if (!jarFile.isFile()) return;
            try (JarFile jf = new JarFile(jarFile)) {
                Enumeration<JarEntry> entries = jf.entries();
                while (entries.hasMoreElements()) {
                    JarEntry e = entries.nextElement();
                    String name = e.getName();
                    if (!name.startsWith(prefix) || !name.endsWith(".class")) continue;
                    String cls = name.substring(prefix.length(), name.length() - ".class".length());
                    int slash = cls.lastIndexOf('/');
                    if (slash > 0) {
                        pkgs.add(cls.substring(0, slash + 1).replace('/', '.'));
                    }
                    // size + CRC 足以发现任何字节级改动，且不必读条目内容
                    fingerprints.add(name + ':' + e.getSize() + ':' + e.getCrc());
                }
            } catch (Exception ignore) {}
        }

        private static void collectFromDir(URL u, Set<String> pkgs, Set<String> fingerprints) {
            try {
                File dir = new File(u.toURI());
                collectDir(dir, dir, pkgs, fingerprints);
            } catch (Exception ignore) {}
        }

        private static void collectDir(File root, File dir, Set<String> pkgs, Set<String> fingerprints) {
            File[] children = dir.listFiles();
            if (children == null) return;
            for (File f : children) {
                if (f.isDirectory()) {
                    collectDir(root, f, pkgs, fingerprints);
                } else if (f.getName().endsWith(".class")) {
                    String rel = root.toURI().relativize(f.toURI()).getPath();
                    String cls = rel.substring(0, rel.length() - ".class".length());
                    int slash = cls.lastIndexOf('/');
                    if (slash > 0) pkgs.add(cls.substring(0, slash + 1).replace('/', '.'));
                    fingerprints.add(rel + ':' + f.length());
                }
            }
        }

        private static String digest(Set<String> fingerprints) {
            if (fingerprints.isEmpty()) return "none";
            List<String> sorted = new ArrayList<>(fingerprints);
            Collections.sort(sorted);
            try {
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                for (String s : sorted) {
                    md.update(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    md.update((byte) '\n');
                }
                return HexFormat.of().formatHex(md.digest()).substring(0, 16);
            } catch (NoSuchAlgorithmException e) {
                return "unknown";
            }
        }
    }

    private SandboxRecorder() {}
}
