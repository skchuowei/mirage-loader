package io.mirage.loader.sandbox;

import io.mirage.loader.MidCache;
import io.mirage.loader.NeoForgeModDetector;

import java.lang.reflect.Constructor;
import java.net.URL;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

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
 *   <li>{@code failed} / {@code error}：构造崩溃 / 超时 / 缺 fake 表面。</li>
 * </ol>
 */
public final class SandboxRecorder {

    /** 构造超时（毫秒）。超时视为"构造函数有副作用"，降级 failed=true。 */
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
            r.failed = true;
            r.error = "no main class";
            return r;
        }

        long start = System.currentTimeMillis();
        RecordingSinkImpl sink = new RecordingSinkImpl();
        Sink.current = sink;

        SandboxClassLoader loader = null;
        try {
            // 从 fat jar 资源里枚举 fake 表面覆盖的包前缀
            Set<String> fakePackages = listFakePackages();
            URL[] urls = new URL[]{ jar.toUri().toURL() };
            loader = new SandboxClassLoader(urls, SandboxRecorder.class.getClassLoader(), fakePackages);

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
                r.failed = true;
                r.error = "timeout";
            } else if (err[0] != null) {
                r.failed = true;
                r.error = describe(err[0]);
            } else {
                r.failed = false;
            }
        } catch (Throwable e) {
            // 沙盒自身失败（fake 资源缺失、URL 失败等）
            r.failed = true;
            r.error = describe(e);
        } finally {
            r.touched_apis = sink.apis();
            r.registered_events = sink.events();
            r.deferred_register_count = sink.deferredRegisterCount();
            r.record_duration_ms = System.currentTimeMillis() - start;
            Sink.current = null;
            if (loader != null) {
                try { loader.close(); } catch (Exception ignore) {}
            }
        }
        return r;
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

    /** 把异常描述成"类名 + message"，截断 500 字符。 */
    private static String describe(Throwable t) {
        String msg = t.getClass().getName() + ": " + (t.getMessage() == null ? "" : t.getMessage());
        if (msg.length() > 500) msg = msg.substring(0, 500);
        return msg;
    }

    /**
     * 从 fat jar 资源里枚举 fake 表面的包前缀。
     * fake 字节码在 {@code mirage/sandbox-fake/} 下，包前缀集合 = 所有注册资源路径的目录前缀。
     * 找不到任何 fake 资源时回退到固定白名单（net.minecraft. / net.neoforged.）。
     */
    private static Set<String> listFakePackages() {
        Set<String> pkgs = new LinkedHashSet<>();
        try {
            java.util.Enumeration<URL> res = SandboxRecorder.class.getClassLoader()
                    .getResources(SandboxClassLoader.FAKE_PREFIX);
            while (res.hasMoreElements()) {
                URL u = res.nextElement();
                // 只在 file/jar 协议下枚举；jar 里一个 URL 对应整个前缀，用 zip 枚举子条目
                if ("jar".equals(u.getProtocol())) {
                    collectFromJar(u, pkgs);
                }
            }
        } catch (Exception ignore) {}
        if (pkgs.isEmpty()) {
            // 兜底：标准 fake 包前缀
            pkgs.add("net.minecraft.");
            pkgs.add("net.neoforged.");
        }
        return pkgs;
    }

    private static void collectFromJar(URL u, Set<String> pkgs) {
        try {
            // jar URL 形如 jar:file:/.../x.jar!/mirage/sandbox-fake/
            String path = u.getPath();
            int bang = path.indexOf("!/");
            if (bang < 0) return;
            String jarPath = path.substring(0, bang);
            String prefix = path.substring(bang + 2); // 去掉 !/，如 mirage/sandbox-fake/
            try (java.util.jar.JarFile jf = new java.util.jar.JarFile(new java.io.File(jarPath))) {
                java.util.Enumeration<java.util.jar.JarEntry> entries = jf.entries();
                while (entries.hasMoreElements()) {
                    String name = entries.nextElement().getName();
                    if (!name.startsWith(prefix) || !name.endsWith(".class")) continue;
                    String cls = name.substring(prefix.length(), name.length() - ".class".length());
                    int slash = cls.lastIndexOf('/');
                    if (slash > 0) {
                        pkgs.add(cls.substring(0, slash + 1).replace('/', '.'));
                    }
                }
            }
        } catch (Exception ignore) {}
    }

    private SandboxRecorder() {}
}
