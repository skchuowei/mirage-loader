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

    // =====================================================================
    //  创造模式物品栏"便签页"（CreativeModeTab）分页切换
    //
    //  原版 TOP / BOTTOM 行各只有 7 个槽位（column 0..6），贴图数组长度固定为 7。
    //  当 Mod 注册的 tab 超过 7 个时，column>6 的 tab 会跑出 GUI 并数组越界。
    //  这里做分页：按 row 分组排序，每行每页显示 7 个，翻页切换。
    // =====================================================================

    /** 当前页（从 0 开始） */
    public static volatile int tabPage = 0;
    /** 每行每页槽位数（与原版贴图数组长度一致） */
    public static final int TABS_PER_PAGE = 7;

    // ---- 反射缓存（编译期无 MC jar，全部走反射） ----
    private static boolean tabReflectReady = false;
    private static Method m_tabs;          // CreativeModeTabs.tabs() : List
    private static Method m_tabColumn;     // CreativeModeTab.column() : int
    private static Method m_tabRow;        // CreativeModeTab.row() : Row
    private static Method m_tabGetType;    // CreativeModeTab.getType() : Type
    private static Object rowTop;           // CreativeModeTab$Row.TOP
    private static Object rowBottom;       // CreativeModeTab$Row.BOTTOM
    private static Object typeSearch;       // CreativeModeTab$Type.SEARCH
    /** 排序后的 TOP 行 tab 列表 */
    private static volatile java.util.List<Object> sortedTop = new java.util.ArrayList<>();
    /** 排序后的 BOTTOM 行 tab 列表 */
    private static volatile java.util.List<Object> sortedBottom = new java.util.ArrayList<>();
    /** 总行数（取 TOP/BOTTOM 中较多的那个算页数） */
    private static volatile int totalPages = 1;

    private static void ensureTabReflect() {
        if (tabReflectReady) return;
        try {
            Class<?> cTabs = Class.forName("net.minecraft.world.item.CreativeModeTabs");
            m_tabs = cTabs.getMethod("tabs");
            m_tabs.setAccessible(true);
            Class<?> cTab = Class.forName("net.minecraft.world.item.CreativeModeTab");
            m_tabColumn = cTab.getMethod("column");
            m_tabColumn.setAccessible(true);
            m_tabRow = cTab.getMethod("row");
            m_tabRow.setAccessible(true);
            m_tabGetType = cTab.getMethod("getType");
            m_tabGetType.setAccessible(true);
            Class<?> cRow = Class.forName("net.minecraft.world.item.CreativeModeTab$Row");
            rowTop = Enum.valueOf(cRow.asSubclass(Enum.class), "TOP");
            rowBottom = Enum.valueOf(cRow.asSubclass(Enum.class), "BOTTOM");
            Class<?> cType = Class.forName("net.minecraft.world.item.CreativeModeTab$Type");
            typeSearch = Enum.valueOf(cType.asSubclass(Enum.class), "SEARCH");
            tabReflectReady = true;
            System.out.println("[Mirage] 标签页反射就绪");
        } catch (Throwable t) {
            System.err.println("[Mirage] 标签页反射初始化失败: " + t);
        }
    }

    /** 计算 totalPages：遍历 tabs() 找最大 column */
    @SuppressWarnings("unchecked")
    private static void rebuildTabPages() {
        ensureTabReflect();
        if (!tabReflectReady) return;
        try {
            java.util.List<Object> all = (java.util.List<Object>) m_tabs.invoke(null);
            int maxCol = 0;
            for (Object tab : all) {
                try {
                    Object type = m_tabGetType.invoke(tab);
                    if (typeSearch != null && typeSearch.equals(type)) continue;
                    int col = (Integer) m_tabColumn.invoke(tab);
                    if (col > maxCol) maxCol = col;
                } catch (Throwable inner) {
                    System.err.println("[Mirage] 标签页检查失败: " + inner);
                }
            }
            totalPages = Math.max(1, (maxCol + TABS_PER_PAGE) / TABS_PER_PAGE);
            System.out.println("[Mirage] 重建标签页: 最大列=" + maxCol + " totalPages=" + totalPages);
        } catch (Throwable t) {
            System.err.println("[Mirage] 重建标签页失败: " + t);
            if (t.getCause() != null) t.getCause().printStackTrace();
            totalPages = 2; // 兜底至少 2 页
        }
    }

    /**
     * 计算 tab 在当前页的显示列号。
     * 直接用 tab.column() - tabPage*7，不依赖排序列表。
     * @return 0..6 表示显示列；-1 表示该 tab 不在当前页（应隐藏/离屏）
     */
    public static int getDisplayColumn(Object tab) {
        if (tab == null) return -1;
        ensureTabReflect();
        if (!tabReflectReady) return 0;
        try {
            int col = (Integer) m_tabColumn.invoke(tab);
            int displayCol = col - tabPage * TABS_PER_PAGE;
            if (displayCol < 0 || displayCol >= TABS_PER_PAGE) return -1;
            return displayCol;
        } catch (Throwable t) {
            return 0;
        }
    }

    /** 当前总页数 */
    public static int getTotalPages() {
        if (sortedTop.isEmpty() && sortedBottom.isEmpty()) rebuildTabPages();
        return totalPages;
    }

    /** extractTabButton 开头调用：是否应该绘制这个 tab */
    public static boolean shouldDrawTab(Object tab) {
        return getDisplayColumn(tab) >= 0;
    }

    /** extractTabButton 里替换 column：返回安全的显示列 0-6 */
    public static int remapColumn(Object tab) {
        int c = getDisplayColumn(tab);
        if (c < 0) c = 0;
        if (c > 6) c = 6;
        return c;
    }

    /** getTabX 里替换返回值：displayCol*27，不在当前页则离屏 */
    public static int remapTabX(Object tab) {
        int c = getDisplayColumn(tab);
        if (c < 0) return -1000;
        // TOP 行 tab 是右对齐的：x = imageWidth - 27*(7-c) - 1
        // BOTTOM 行是左对齐的：x = c*27
        boolean topRow = false;
        try {
            Object row = m_tabRow.invoke(tab);
            topRow = (rowTop != null && rowTop.equals(row));
        } catch (Throwable t) {}
        if (topRow) {
            return currentImageWidth - 27 * (7 - c) - 1;
        }
        return c * 27;
    }

    /** 翻页（dir = -1 上一页，+1 下一页） */
    public static void flipPage(int dir) {
        if (totalPages <= 1) return;
        tabPage = ((tabPage + dir) % totalPages + totalPages) % totalPages;
        System.out.println("[Mirage] 标签页 -> " + tabPage + "/" + totalPages);
    }

    // ---- 翻页按钮屏幕区域（相对 GUI 左上角 leftPos/topPos 的偏移） ----
    private static final int ARROW_W = 16;
    private static final int ARROW_H = 12;
    /** 当前 GUI 宽度（drawTabPageControls 里更新） */
    public static int currentImageWidth = 176;

    /**
     * 在创造模式物品栏上绘制翻页按钮和页码。
     * ASM 在 extractBackground() return 前调用。
     */
    public static void drawTabPageControls(Object gge, int leftPos, int topPos,
                                           int imageWidth, int imageHeight, Object font) {
        if (gge == null) return;
        currentImageWidth = imageWidth;
        if (sortedTop.isEmpty() && sortedBottom.isEmpty()) rebuildTabPages();
        if (totalPages <= 1) return; // 只有一页不画

        try {
            // 箭头垂直位置：GUI 顶部上方（和 TOP tab 同一行）
            int arrowY = topPos - 28;
            // 左箭头：GUI 左边外侧
            int leftX = leftPos - ARROW_W - 2;
            // 右箭头：GUI 右边外侧
            int rightX = leftPos + imageWidth + 2;

            // 用 GuiGraphicsExtractor.fill 画箭头底
            java.lang.reflect.Method fill = gge.getClass().getMethod("fill",
                int.class, int.class, int.class, int.class, int.class);
            // 左箭头（灰色，首页时变暗）
            int leftColor = (tabPage > 0) ? 0xFFAAAAAA : 0xFF555555;
            fill.invoke(gge, leftX, arrowY, leftX + ARROW_W, arrowY + ARROW_H, leftColor);
            // 右箭头（灰色，末页时变暗）
            int rightColor = (tabPage < totalPages - 1) ? 0xFFAAAAAA : 0xFF555555;
            fill.invoke(gge, rightX, arrowY, rightX + ARROW_W, arrowY + ARROW_H, rightColor);

            // 画箭头符号（用文字 < 和 >）
            if (font != null) {
                drawTextSafe(gge, font, "<", leftX + 4, arrowY + 2, 0xFFFFFFFF);
                drawTextSafe(gge, font, ">", rightX + 4, arrowY + 2, 0xFFFFFFFF);
                drawTextSafe(gge, font, (tabPage + 1) + "/" + totalPages,
                    leftX - 2, arrowY + ARROW_H + 2, 0xFFFFFF55);
            }
        } catch (Throwable t) {
            if ((diagCounter++ & 63) == 0) {
                System.err.println("[Mirage] 绘制翻页控件失败: " + t);
            }
        }
    }

    /**
     * 检测翻页按钮点击（鼠标绝对坐标）。
     * ASM 在 mouseClicked / mouseReleased 开头调用。
     * @return true 如果点击被翻页按钮消费
     */
    public static boolean handlePageClick(double absX, double absY, int leftPos, int topPos,
                                          int imageWidth, int imageHeight) {
        if (totalPages <= 1) return false;
        int arrowY = topPos - 28; // 绝对 y
        int leftX = leftPos - ARROW_W - 2;
        int rightX = leftPos + imageWidth + 2;
        // 左箭头
        if (absX >= leftX && absX <= leftX + ARROW_W && absY >= arrowY && absY <= arrowY + ARROW_H) {
            flipPage(-1);
            return true;
        }
        // 右箭头
        if (absX >= rightX && absX <= rightX + ARROW_W && absY >= arrowY && absY <= arrowY + ARROW_H) {
            flipPage(1);
            return true;
        }
        return false;
    }

    private MirageDebug() {}
}
