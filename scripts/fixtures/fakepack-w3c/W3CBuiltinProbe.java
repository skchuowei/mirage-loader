package probe;

import io.mirage.loader.MirageClassLoader;
import io.mirage.loader.RuntimeFakePack;

import java.io.File;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * MSR W3 路线 C 门禁的 headless JVM 探针（2026-11-14）。
 *
 * <p>验证「P4b pack 已构建期固化进 fat jar（资源 mirage/fakepack/），并在真启动类加载器
 * 的 fallback 分支里被当作内置 pack 服务」这一能力，不依赖任何外部 home 目录。
 *
 * <p>构造一个<b>真实的</b> MirageClassLoader（parent = platform loader，URL 只挂 fat jar），
 * 然后从 fat jar 里枚举 {@code mirage/fakepack/net/neoforged/**} 资源，把每个反推成点分类名，
 * 逐个尝试加载，直到有一个由 MCL 的 defineClass 成功（即走了内置 fallback）。
 * 最后再验证前缀闸：{@code net.minecraft.*} / {@code io.mirage.neoforge.*} 不被服务。
 *
 * <p>系统属性在 JVM 启动时固定（gate 脚本按场景给 -Dmirage.msr.fakepack.runtime=on），
 * 故每个场景由门禁单独起一个进程。<b>不创建窗口、不启动 MC、不写 mc-shili/。</b>
 */
public final class W3CBuiltinProbe {

    /** 同步 W1 守卫探针用的假类命名空间，证明内置包不服务非 neo 前缀。 */
    private static final String MC_PROBE = "net.minecraft.wiringprobe.Nope";
    private static final String ION_PROBE = "io.mirage.neoforge.wiringprobe.Nope";

    public static void main(String[] args) throws Exception {
        File fatJar = new File(args[0]);
        String outFile = args.length > 1 ? args[1] : null;

        // 从 fat jar 枚举内置包里所有 neo 类，作为候选加载目标
        List<String> candidates = listBuiltinNeoClasses(fatJar);
        System.out.println("BUILTIN_RESOURCE_COUNT=" + candidates.size());

        // real-shape（2026-11-14）：超类闭包会补进若干 compat 根路径**已有**的类
        // （bus.api.Event / IConfigSpec / IModBusEvent ...）。它们在 DEFAULT 下就能由 compat
        // 正常加载，字典序又排在最前 —— 若拿它们当目标，探针会误判「默认零变化被破坏」，
        // 且 BUILTIN_ON 根本走不到 fallback（B_HITS=0）。这里显式跳过 compat 原生类，
        // 只测「只有内置 pack 能提供、必须走 fallback」的类，才符合本探针的语义。
        java.util.Set<String> compatRoot = listRootNeoClasses(fatJar);
        System.out.println("COMPAT_ROOT_COUNT=" + compatRoot.size());

        URL[] urls = new URL[] { fatJar.toURI().toURL() };
        ClassLoader parent = ClassLoader.getPlatformClassLoader();
        MirageClassLoader mcl = new MirageClassLoader(urls, parent);

        String neoTarget = "<none>";
        String neoResult = "CNFE";
        String neoSuper = "<n/a>";
        int tried = 0;
        for (String c : candidates) {
            if (compatRoot.contains(c)) continue;
            tried++;
            String r = tryLoad(mcl, c);
            if (r.startsWith("OK:")) {
                neoTarget = c;
                neoResult = r;
                try {
                    Class<?> loaded = mcl.loadClass(c);
                    Class<?> sup = loaded.getSuperclass();
                    neoSuper = (sup == null) ? "null" : sup.getName();
                } catch (Throwable ignore) { /* 仅取 super，失败忽略 */ }
                break;
            }
            if (tried >= 60) { neoResult = r; break; } // 防止极端情况下死循环扫描
        }
        System.out.println("NEO_TRIED=" + tried);
        System.out.println("NEO_TARGET=" + neoTarget);
        System.out.println("NEO_LOAD=" + neoResult);
        System.out.println("NEO_SUPER=" + neoSuper);

        // 前缀闸：内置 pack 只认 net.neoforged.，其余一律不服务
        System.out.println("MC_LOAD=" + tryLoad(mcl, MC_PROBE));
        System.out.println("ION_LOAD=" + tryLoad(mcl, ION_PROBE));

        // 路线 B / C 共享的开关与命中态
        System.out.println("B_ENABLED=" + RuntimeFakePack.isEnabled());
        System.out.println("B_HITS=" + RuntimeFakePack.hitCount());
        System.out.println("B_BANNER=" + RuntimeFakePack.bannerSeen());
        System.out.println("B_HAS_TARGET=" + RuntimeFakePack.snapshot().contains(neoTarget));

        if (outFile != null) {
            try (java.io.PrintWriter pw = new java.io.PrintWriter(outFile, java.nio.charset.StandardCharsets.UTF_8)) {
                pw.println("# W3C probe result");
            }
        }
    }

    private static List<String> listBuiltinNeoClasses(File fatJar) {
        List<String> out = new ArrayList<>();
        if (!fatJar.isFile()) return out;
        try (JarFile jf = new JarFile(fatJar)) {
            java.util.Enumeration<JarEntry> e = jf.entries();
            while (e.hasMoreElements()) {
                JarEntry je = e.nextElement();
                String n = je.getName();
                if (n.startsWith("mirage/fakepack/net/neoforged/") && n.endsWith(".class")) {
                    String cls = n.substring("mirage/fakepack/".length(), n.length() - ".class".length())
                            .replace('/', '.');
                    out.add(cls);
                }
            }
        } catch (Throwable t) {
            // 枚举失败不致命，交给 NEO_LOAD=CNFE 兜底
        }
        return out;
    }

    /** fat jar 根路径下的 neo 类（compat 原生 stub），探针须跳过它们。 */
    private static java.util.Set<String> listRootNeoClasses(File fatJar) {
        java.util.Set<String> out = new java.util.HashSet<>();
        if (!fatJar.isFile()) return out;
        try (JarFile jf = new JarFile(fatJar)) {
            java.util.Enumeration<JarEntry> e = jf.entries();
            while (e.hasMoreElements()) {
                String n = e.nextElement().getName();
                if (n.startsWith("net/neoforged/") && n.endsWith(".class")) {
                    out.add(n.substring(0, n.length() - ".class".length()).replace('/', '.'));
                }
            }
        } catch (Throwable ignored) {
            // 枚举失败不致命：不过滤时会退化为旧行为，门禁断言会如实变红
        }
        return out;
    }

    private static String tryLoad(ClassLoader cl, String name) {
        try {
            Class<?> c = cl.loadClass(name);
            ClassLoader owner = c.getClassLoader();
            String who = (owner == cl) ? "mcl"
                    : (owner == ClassLoader.getSystemClassLoader()) ? "app"
                    : (owner == null) ? "bootstrap" : "other:" + owner.getClass().getSimpleName();
            return "OK:" + who;
        } catch (ClassNotFoundException ex) {
            return "CNFE";
        } catch (Throwable t) {
            return "ERR:" + t.getClass().getSimpleName();
        }
    }
}
