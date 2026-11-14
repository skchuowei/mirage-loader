package probe;

import io.mirage.loader.MirageClassLoader;
import io.mirage.loader.RuntimeFakePack;

import java.io.File;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * MSR W3 路线 C / real-shape 门禁探针（2026-11-14）。
 *
 * <p>从 fat jar 枚举 {@code mirage/fakepack/net/neoforged/**} 内置的<b>全部</b>类，
 * 用真实 {@link MirageClassLoader}（parent = platform loader，URL 只挂 fat jar）逐个
 * {@code loadClass}，分类统计 OK / CNFE / NCDFE / 其它，并统计「真实形状」：
 * 超类或接口落在 {@code net.neoforged.*} 的类数。UOE 骨架时代这些值全为 0（一律回落
 * {@code Object}）；real-shape + 超类闭包补齐后，应当全量可加载且 REALSUPER 明显大于 0。
 *
 * <p>系统属性在 JVM 启动时固定（门禁用 -Dmirage.msr.fakepack.runtime=on 与否分两个场景
 * 各起一个进程）。<b>不创建窗口、不启动 MC、不写 mc-shili/。</b>
 */
public final class AllLoadProbe {

    private static final String PREFIX = "mirage/fakepack/";

    public static void main(String[] args) throws Exception {
        File fatJar = new File(args[0]);
        List<String> candidates = listBuiltinNeoClasses(fatJar);

        URL[] urls = new URL[]{fatJar.toURI().toURL()};
        ClassLoader parent = ClassLoader.getPlatformClassLoader();
        MirageClassLoader mcl = new MirageClassLoader(urls, parent);

        int ok = 0, cnfe = 0, ncdfe = 0, other = 0;
        int realSuper = 0, realItf = 0;
        List<String> failures = new ArrayList<>();
        for (String c : candidates) {
            try {
                Class<?> k = mcl.loadClass(c);
                ok++;
                Class<?> sup = k.getSuperclass();
                if (sup != null && sup.getName().startsWith("net.neoforged.")) realSuper++;
                for (Class<?> i : k.getInterfaces()) {
                    if (i.getName().startsWith("net.neoforged.")) { realItf++; break; }
                }
            } catch (ClassNotFoundException e) {
                cnfe++;
            } catch (NoClassDefFoundError e) {
                ncdfe++;
                if (failures.size() < 30) failures.add("NCDFE " + c + " -> " + e.getMessage());
            } catch (Throwable t) {
                other++;
                if (failures.size() < 30) {
                    failures.add("ERR " + c + " -> " + t.getClass().getSimpleName() + ":" + t.getMessage());
                }
            }
        }

        System.out.println("BUILTIN_RESOURCE_COUNT=" + candidates.size());
        System.out.println("LOAD_TOTAL=" + candidates.size());
        System.out.println("LOAD_OK=" + ok);
        System.out.println("LOAD_CNFE=" + cnfe);
        System.out.println("LOAD_NCDFE=" + ncdfe);
        System.out.println("LOAD_OTHER=" + other);
        System.out.println("REALSUPER=" + realSuper);
        System.out.println("REALITF=" + realItf);
        System.out.println("B_ENABLED=" + RuntimeFakePack.isEnabled());
        System.out.println("B_HITS=" + RuntimeFakePack.hitCount());
        System.out.println("B_BANNER=" + RuntimeFakePack.bannerSeen());
        for (String f : failures) System.out.println("FAILROW " + f);
    }

    private static List<String> listBuiltinNeoClasses(File fatJar) {
        List<String> out = new ArrayList<>();
        if (!fatJar.isFile()) return out;
        try (JarFile jf = new JarFile(fatJar)) {
            Enumeration<JarEntry> e = jf.entries();
            while (e.hasMoreElements()) {
                String n = e.nextElement().getName();
                if (n.startsWith(PREFIX + "net/neoforged/") && n.endsWith(".class")) {
                    out.add(n.substring(PREFIX.length(), n.length() - ".class".length())
                            .replace('/', '.'));
                }
            }
        } catch (Throwable ignored) {
            // 枚举失败不致命，交给 LOAD_OK=0 兜底
        }
        Collections.sort(out);
        return out;
    }
}
