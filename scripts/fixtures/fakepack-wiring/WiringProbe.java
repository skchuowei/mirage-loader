package probe;

import io.mirage.loader.LoadProgress;
import io.mirage.loader.MirageClassLoader;
import io.mirage.loader.MissingNeoTracker;
import io.mirage.loader.RuntimeFakePack;

import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * MSR W1 接线门禁的 headless JVM 探针（2026-11-13）。
 *
 * <p>构造一个<b>真实的</b> MirageClassLoader（parent = platform loader，URL 只挂 compat
 * classes），然后按固定顺序加载 4 个探针类，把状态打成 {@code KEY=value} 行供
 * PowerShell 逐条断言。<b>不创建任何窗口、不启动 MC、不写 mc-shili/</b>，
 * 属 AGENTS 第 2 节 (B) 类可自行启动的进程。
 *
 * <p><b>关键</b>：pack 目录只通过 {@code -Dmirage.msr.fakepack.home} 给
 * {@link RuntimeFakePack}，<b>绝不挂进 MCL 的 URL</b> —— 否则 getResource 直接命中，
 * fallback 分支永远走不到，门禁会假绿。
 *
 * <p>系统属性在 JVM 启动时固定，故每个场景由门禁脚本单独起一个进程。
 */
public final class WiringProbe {

    private static final String NEO_FAKE = "net.neoforged.wiringprobe.FakeOnly";
    private static final String MC_FAKE = "net.minecraft.wiringprobe.McFake";
    private static final String ION_FAKE = "io.mirage.neoforge.wiringprobe.CompatFake";
    private static final String COMPAT_DIST = "net.neoforged.api.distmarker.Dist";

    public static void main(String[] args) throws Exception {
        Path compat = Paths.get(args[0]);
        Path pack = Paths.get(args[1]);
        Path outA = Paths.get(args[2]);
        Path outB = Paths.get(args[3]);

        // sanity：pack 必须在磁盘上，但绝不作为 MCL 的 URL
        System.out.println("PACK_DIR_EXISTS=" + Files.isDirectory(pack));
        System.out.println("COMPAT_DIR_EXISTS=" + Files.isDirectory(compat));

        URL[] urls = new URL[] { compat.toUri().toURL() };
        ClassLoader parent = ClassLoader.getSystemClassLoader().getParent();
        MirageClassLoader mcl = new MirageClassLoader(urls, parent);

        long mixinBefore = LoadProgress.counterValue("mixin.classes", 0L);

        String neo = tryLoad(mcl, NEO_FAKE);
        String mc = tryLoad(mcl, MC_FAKE);
        String ion = tryLoad(mcl, ION_FAKE);
        long mixinAfterLookups = LoadProgress.counterValue("mixin.classes", 0L);

        String dist = tryLoad(mcl, COMPAT_DIST);
        long mixinAfterDist = LoadProgress.counterValue("mixin.classes", 0L);

        System.out.println("NEO_MISSING=" + neo);
        System.out.println("MC_MISSING=" + mc);
        System.out.println("ION_MISSING=" + ion);
        System.out.println("COMPAT_DIST=" + dist);
        System.out.println("MIXIN_BEFORE=" + mixinBefore);
        System.out.println("MIXIN_AFTER_LOOKUPS=" + mixinAfterLookups);
        System.out.println("MIXIN_AFTER_DIST=" + mixinAfterDist);

        // 真 compat stub 优先：pack 里那份 Dist 带 PACK_MARKER，真 stub 没有。
        System.out.println("COMPAT_PACK_MARKER=" + hasPackMarker(mcl, COMPAT_DIST));
        System.out.println("NEO_PING=" + ping(mcl, NEO_FAKE));

        // ---- 路线 A ----
        System.out.println("A_ENABLED=" + MissingNeoTracker.isEnabled());
        System.out.println("A_HOOK=" + MissingNeoTracker.hookRegistered());
        System.out.println("A_SIZE=" + MissingNeoTracker.size());
        System.out.println("A_HAS_NEO=" + MissingNeoTracker.snapshot().contains(NEO_FAKE));
        System.out.println("A_HAS_MC=" + MissingNeoTracker.snapshot().contains(MC_FAKE));
        System.out.println("A_HAS_ION=" + MissingNeoTracker.snapshot().contains(ION_FAKE));
        System.out.println("A_HAS_DIST=" + MissingNeoTracker.snapshot().contains(COMPAT_DIST));
        int aFlush = -1;
        if (MissingNeoTracker.isEnabled()) {
            try {
                aFlush = MissingNeoTracker.flush(outA);
            } catch (Throwable t) {
                aFlush = -2;
            }
        }
        System.out.println("A_FLUSH=" + aFlush);
        System.out.println("A_FILE=" + Files.isRegularFile(outA));

        // ---- 路线 B ----
        System.out.println("B_ENABLED=" + RuntimeFakePack.isEnabled());
        System.out.println("B_HOOK=" + RuntimeFakePack.hookRegistered());
        System.out.println("B_HITS=" + RuntimeFakePack.hitCount());
        System.out.println("B_HAS_NEO=" + RuntimeFakePack.snapshot().contains(NEO_FAKE));
        System.out.println("B_HAS_MC=" + RuntimeFakePack.snapshot().contains(MC_FAKE));
        System.out.println("B_HAS_ION=" + RuntimeFakePack.snapshot().contains(ION_FAKE));
        System.out.println("B_HAS_DIST=" + RuntimeFakePack.snapshot().contains(COMPAT_DIST));
        System.out.println("B_BANNER=" + RuntimeFakePack.bannerSeen());
        int bFlush = -1;
        if (RuntimeFakePack.isEnabled()) {
            try {
                bFlush = RuntimeFakePack.flush(outB);
            } catch (Throwable t) {
                bFlush = -2;
            }
        }
        System.out.println("B_FLUSH=" + bFlush);
        System.out.println("B_FILE=" + Files.isRegularFile(outB));
    }

    private static String tryLoad(ClassLoader cl, String name) {
        try {
            Class<?> c = cl.loadClass(name);
            ClassLoader owner = c.getClassLoader();
            String who = (owner == cl) ? "mcl"
                    : (owner == ClassLoader.getSystemClassLoader()) ? "app"
                    : (owner == null) ? "bootstrap" : "other:" + owner.getClass().getSimpleName();
            return "OK:" + who;
        } catch (ClassNotFoundException e) {
            return "CNFE";
        } catch (Throwable t) {
            return "ERR:" + t.getClass().getSimpleName();
        }
    }

    private static String ping(ClassLoader cl, String name) {
        try {
            Class<?> c = cl.loadClass(name);
            return String.valueOf(c.getMethod("ping").invoke(null));
        } catch (Throwable t) {
            return "ERR:" + t.getClass().getSimpleName();
        }
    }

    private static boolean hasPackMarker(ClassLoader cl, String name) {
        try {
            cl.loadClass(name).getField("PACK_MARKER");
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}
