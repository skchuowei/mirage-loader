package io.mirage.loader;

import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Mirage 调试状态：Launcher 写，ASM 织入的 LoadingOverlay 读。
 * 跨类桥接——Launcher 阶段更新字段，MC 渲染线程每帧读出来画。
 */
public final class MirageDebug {

    public static volatile String version = "Mirage v0.4.0";
    public static volatile String mcVersion = "MC 26.2";
    public static volatile String stage = "Starting ...";
    public static volatile String detail = "";
    public static volatile int progress = 0;
    public static volatile int modCount = 0;
    public static volatile int modTotal = 0;
    public static volatile String currentMod = "";
    public static volatile String modList = "";
    public static volatile boolean showMods = false;
    /** 所有初始化完成后设为 true，TitleScreen 上的调试面板据此隐藏 */
    public static volatile boolean loaded = false;

    /** Mod 命名空间列表（Launcher 发现 mod 时填充），用于 ASM 织入资源包暴露 */
    public static volatile java.util.List<String> modNamespaces = new java.util.ArrayList<>();

    /** 最近日志行（最多 5 行） */
    private static final Deque<String> recentLogs = new ArrayDeque<>(5);
    private static final OperatingSystemMXBean osBean = ManagementFactory.getOperatingSystemMXBean();

    public static void setStage(String s) { stage = s; }
    public static void setProgress(int p) { progress = p; }
    public static void setModCount(int n) { modCount = n; }
    public static void setModTotal(int n) { modTotal = n; }
    public static void setCurrentMod(String id) { currentMod = id; }
    public static void setModList(String s) { modList = s; }
    public static void setLoaded() { loaded = true; }
    public static void toggleMods() { showMods = !showMods; }

    /**
     * ASM 织入 VanillaPackResourcesBuilder.build() 时调用。
     * 1) 暴露 mod 命名空间
     * 2) 把 mod jar 的 assets 提取到临时目录，通过反射 pushPathForType/pushRootPath 加入
     */
    public static void exposeModNamespaces(Object builder) {
        try {
            Method expose = builder.getClass().getMethod("exposeNamespace", String[].class);
            expose.invoke(builder, (Object) modNamespaces.toArray(new String[0]));
            System.out.println("[Mirage] 暴露 mod 命名空间: " + modNamespaces);

            java.lang.reflect.Method pushPath = null, pushRoot = null;
            for (Method m : builder.getClass().getDeclaredMethods()) {
                if (m.getName().equals("pushPathForType")) pushPath = m;
                if (m.getName().equals("pushRootPath")) pushRoot = m;
            }
            if (pushPath != null) pushPath.setAccessible(true);
            if (pushRoot != null) pushRoot.setAccessible(true);

            // 关键：用方法签名中的参数类型，避免类加载器隔离导致的 argument type mismatch
            Object clientResources = null;
            if (pushPath != null) {
                Class<?> packTypeClass = pushPath.getParameterTypes()[0];
                for (Object c : packTypeClass.getEnumConstants()) {
                    if (((Enum<?>) c).name().equals("CLIENT_RESOURCES")) clientResources = c;
                }
                if (clientResources == null) clientResources = packTypeClass.getEnumConstants()[0];
            }
            System.out.println("[Mirage] 资源包类型: " + clientResources);

            for (String modId : modNamespaces) {
                try {
                    java.nio.file.Path modJar = findModJar(modId);
                    if (modJar == null) continue;
                    java.nio.file.Path extractDir = java.nio.file.Files.createTempDirectory("mirage-" + modId);
                    extractAssets(modJar, extractDir);
                    java.nio.file.Path assetRoot = extractDir.resolve("assets");
                    if (java.nio.file.Files.exists(assetRoot)) {
                        if (pushRoot != null) pushRoot.invoke(builder, extractDir);
                        if (pushPath != null) pushPath.invoke(builder, clientResources, assetRoot);
                        System.out.println("[Mirage] 已添加 mod 资源: " + modId + " -> " + extractDir);
                    }
                } catch (Exception e) {
                    System.err.println("[Mirage] 添加失败: " + modId + ": " + e);
                }
            }
        } catch (Exception e) {
            System.err.println("[Mirage] 暴露命名空间失败: " + e);
        }
    }

    private static java.nio.file.Path findModJar(String modId) {
        try {
            java.nio.file.Path modsDir = java.nio.file.Path.of(System.getProperty("user.dir")).resolve("mods");
            if (!java.nio.file.Files.exists(modsDir)) return null;
            try (var stream = java.nio.file.Files.list(modsDir)) {
                for (java.nio.file.Path p : (Iterable<java.nio.file.Path>) stream::iterator) {
                    if (p.getFileName().toString().startsWith(modId + "-")) return p;
                }
            }
        } catch (Exception e) {}
        return null;
    }

    private static void extractAssets(java.nio.file.Path jar, java.nio.file.Path dest) throws Exception {
        try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(jar.toFile())) {
            java.util.Enumeration<? extends java.util.zip.ZipEntry> e = zf.entries();
            while (e.hasMoreElements()) {
                java.util.zip.ZipEntry entry = e.nextElement();
                if (!entry.getName().startsWith("assets/")) continue;
                java.nio.file.Path out = dest.resolve(entry.getName());
                if (entry.isDirectory()) {
                    java.nio.file.Files.createDirectories(out);
                } else {
                    java.nio.file.Files.createDirectories(out.getParent());
                    try (var in = zf.getInputStream(entry)) {
                        java.nio.file.Files.copy(in, out, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
        }
    }

    /** 添加一行日志 */
    public static void log(String line) {
        synchronized (recentLogs) {
            if (recentLogs.size() >= 5) recentLogs.pollFirst();
            recentLogs.addLast(line);
        }
    }

    public static String memLine() {
        long usedMB = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / (1024*1024);
        long maxMB = Runtime.getRuntime().maxMemory() / (1024*1024);
        return "Memory: " + usedMB + "/" + maxMB + " MB (" + (usedMB*100/Math.max(1,maxMB)) + "%)";
    }

    public static String cpuLine() {
        double cpu = osBean.getSystemLoadAverage();
        if (cpu < 0) cpu = 0;
        return String.format("CPU: %.1f%%", cpu * 100);
    }

    public static String stageLine() {
        if (currentMod != null && !currentMod.isEmpty()) {
            return "Stage: " + stage + "  [" + currentMod + "]";
        }
        return "Stage: " + stage;
    }

    public static String modLine() {
        if (modTotal > 0) return "Mods: " + modCount + "/" + modTotal + " loaded";
        return "Mods: " + modCount + " loaded";
    }

    public static String modListLine() {
        if (modList.isEmpty()) return "(none)";
        return modList;
    }

    public static String progressLine() {
        return "Progress: " + progress + "%";
    }

    /** 获取最近日志行（数组，最多 5 行） */
    public static String[] recentLogLines() {
        synchronized (recentLogs) {
            return recentLogs.toArray(new String[0]);
        }
    }

    // ---- 跨类桥接：ASM 织入代码调用，避免直接引用 MC 类（编译期无 MC jar） ----
    private static volatile java.lang.reflect.Method cachedText;
    private static volatile int diagCounter;
    private static volatile boolean fontReportedNonNull;
    private static volatile boolean methodResolved;

    /**
     * 安全地在 GuiGraphicsExtractor 上画一行文字。
     * 不直接 import net.minecraft.client.gui.GuiGraphics/Font（编译期 classpath 没有 MC），
     * 用反射调 gge.text(font, str, x, y, color)。font==null 时跳过，不抛 NPE。
     */
    public static void drawTextSafe(Object gge, Object font, String text, int x, int y, int color) {
        if (gge == null || text == null) return;
        if (font == null) {
            if ((diagCounter++ & 63) == 0) {
                System.out.println("[Mirage] drawTextSafe: font==null, skip \"" + text + "\" (n=" + diagCounter + ")");
            }
            return;
        }
        if (!fontReportedNonNull) {
            fontReportedNonNull = true;
            System.out.println("[Mirage] 绘制文本: 字体已就绪! 字体类=" + font.getClass().getName()
                + " ggeClass=" + gge.getClass().getName() + " text=\"" + text + "\"");
        }
        if (!methodResolved) {
            methodResolved = true;
            for (java.lang.reflect.Method m : gge.getClass().getMethods()) {
                if (m.getName().equals("text") && m.getParameterCount() == 5) {
                    Class<?>[] p = m.getParameterTypes();
                    if (p[1] == String.class && p[2] == int.class && p[3] == int.class && p[4] == int.class) {
                        cachedText = m;
                        break;
                    }
                }
            }
            System.out.println("[Mirage] 绘制文本: 解析到方法 = " + cachedText);
        }
        if (cachedText == null) {
            if ((diagCounter++ & 63) == 0) {
                System.out.println("[Mirage] 绘制文本: 在该类上未找到方法: " + gge.getClass());
            }
            return;
        }
        try {
            cachedText.invoke(gge, font, text, x, y, color);
        } catch (Throwable t) {
            if ((diagCounter++ & 63) == 0) {
                System.out.println("[Mirage] 绘制文本调用失败: " + t);
            }
        }
    }

    private MirageDebug() {}
}
