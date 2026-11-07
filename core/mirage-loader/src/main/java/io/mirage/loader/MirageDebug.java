package io.mirage.loader;

import io.mirage.api.log.MirageLog;

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
    /** 右下角版本号（早期窗口与原生 MC 窗口共用同一串，早期窗口启动时写入） */
    public static volatile String versionLabel = "26.2-0.4.0";
    public static volatile String stage = "Starting ...";
    public static volatile String detail = "";
    public static volatile int progress = 0;
    public static volatile int modCount = 0;
    public static volatile int modTotal = 0;
    public static volatile String currentMod = "";
    public static volatile String modList = "";
    /** 标题界面是否展开 Mod 列表 */
    public static volatile boolean showMods = false;
    /** 标题界面是否展开详细调试面板 */
    public static volatile boolean showPanel = false;
    /** TitleScreen 出现后设为 true，加载遮罩上的调试卡片据此停止绘制 */
    public static volatile boolean loaded = false;

    /** Mod 命名空间列表（Launcher 发现 mod 时填充），用于 ASM 织入资源包暴露 */
    public static volatile java.util.List<String> modNamespaces = new java.util.ArrayList<>();

    /** HUD 日志节选（最近 8 行） */
    private static final int LOG_CAPACITY = 8;
    private static final Deque<String> recentLogs = new ArrayDeque<>(LOG_CAPACITY);
    private static final OperatingSystemMXBean osBean = ManagementFactory.getOperatingSystemMXBean();
    private static volatile String lastLogLine = "";

    /** 加载界面底部滚动日志节选（最近 32 行，与事件流环形缓冲同容量）。 */
    private static final int LOADING_LOG_CAPACITY = 32;
    private static final Deque<String> loadingLog = new ArrayDeque<>(LOADING_LOG_CAPACITY);

    /**
     * 加载界面要显示的汉字全集（阶段名 / 子任务名 / 面板标题 / 常见报错词 / 必要标点）。
     * 主窗口（ForgeStyleOverlay）用 AWT 逐字光栅化这些字；早期窗口（EarlyFont）把它们
     * 打包进 STB 图集。两窗口共用这一份，避免两边汉字不一致。
     */
    public static final String LOADING_CJK_SOURCE =
            "发现解析清单字节码变换合并注册表事件总线初始化启动客户端"
            + "创建类加载器扫描目录检测版本解冻修补冻结加载脚本已"
            + "总进度当前阶段日志实时失败错误异常缺无法资源方法字段警告"
            + "找不到跳过忽略冲突信息模式完成进行待处理·—：（）"
            // 2026-11-07 真实进度仪表盘新增词（早期窗口 STB 图集缺字是静默留白，必须显式补）
            + "事件缓冲条候选挂载织入收集配置装配项秒游标独立不定计数根耗"
            + "后端原版注册项已完成进行中待处理数段格";

    /**
     * 日志面板要显示的中文全集：从全仓库字符串字面量扫描得到（594 字，含标点）。
     *
     * <p>为什么需要它：早期窗口用 STB 图集，<b>没打包的字根本画不出来</b>（按字宽留空），
     * 而 Mirage 自己的日志几乎全是中文（{@code [Mirage] 早期窗口已显示…}）——只打包 UI 用字
     * 会让日志面板变成一片空白。主窗口（AWT 版）不需要它，那边由
     * {@link #ensureRasterized} 按需补字。
     *
     * <p>重新生成：扫描 {@code core/ lifecyle/ launcher/ api/ modules/ compat/} 下所有
     * {@code .java} 字符串字面量里的非 ASCII 字符，去重排序后按 40 字一行拼接。
     */
    public static final String LOADING_CJK_LOG_SOURCE =
            "·—←→≠、。「」一上下不与丢严个中串为主久么义之也了事二于互些交产人什仅仍从仓"
            + "他代以件任份会伪但位体何余作你使例供依便保信修值停像允元先兜入全公共关其典兼内册"
            + "再写冲冻准出函分列则创初别到制前副力功加务动包化匹区匿半单占印即历原去参又及双反"
            + "发取受变叠口句另只可台右号各合同名后向吗否含听启告周命和品响哪器回因图在场坐块型"
            + "基堆填境声处复外多大太失头如始委子字存它守安完定实客容对射将小少尔尚就尾局层属左"
            + "已布常并幻序库应底度延建开异弃式引强归当录形影径待循心必志忽态性总恢息想意戏成我"
            + "或截戳户所扑打执扫批找抛报拒拓拦择括拼拿持挂指按捕换据掉排探接控推掺描提插摘支收"
            + "改放效数整文断新方无日旧早时明映是显普景暂暴更替最有服望期未末本束条来板构析果柄"
            + "某染查标栈栏根格桥桩检模次止正此步死残段毁每比永求汇沙没法注活派测消涉深混添清渲"
            + "游源满滤点版物状独玩环现理生用由画界留略登的监盒盖盘目直相省看真知码硬确示禁离私"
            + "种秒移程空突窗立端符第等策签简算管箱类系素紧级纹线组织经绑结绘给络绝统继绪续缀缓"
            + "编缩缺网罩置翻者而聚背能脚脱自至致节花若获落行补表衬被装要覆见视角解触言警订认让"
            + "记许设访识诊译试询该语误请读谁调象败费资赋赖走起超越足跑跟跨路跳转轴轻载较辑输边"
            + "达过运还这进远违迟述追退送选透逐递通造逻遍道遮那部都配采释里重量钮链销错键长门闭"
            + "问间阅阶阻附际降限除隔集需露静非面页顺须预题额首骤默鼠齐（），：；？";

    // ---- S1–S7 主阶段模型（供加载界面步骤条与阶段摘要使用） ----
    /** 当前所处主阶段索引 0..6（S1..S7）。由各阶段在入口处 {@link #setPhase} 标注。 */
    public static volatile int phase = 0;
    public static final int STAGE_COUNT = LoadProgress.STAGE_COUNT;
    /** 阶段名统一由 LoadProgress 持有（仪表盘面包屑与主条标签要用同一份）。 */
    public static final String[] PHASE_NAMES = LoadProgress.STAGE_NAMES;

    public static void setStage(String s) {
        stage = s;
        LoadProgress.detail(s);
    }
    public static void setProgress(int p) { progress = p; }
    public static void setModCount(int n) { modCount = n; }
    public static void setModTotal(int n) { modTotal = n; }
    public static void setCurrentMod(String id) { currentMod = id; }
    public static void setModList(String s) { modList = s; }
    public static void setLoaded() { loaded = true; }

    /** 标注当前主阶段（S1..S7 对应 0..6），同时推进分段总进度并发阶段事件。 */
    public static void setPhase(int p) {
        if (p >= 0 && p < STAGE_COUNT) {
            phase = p;
            LoadProgress.stage(p);
        }
    }

    /** 各阶段状态：2=已完成 / 1=进行中 / 0=待处理。 */
    public static int[] phaseStates() {
        int[] r = new int[STAGE_COUNT];
        for (int i = 0; i < STAGE_COUNT; i++) {
            r[i] = i < phase ? 2 : (i == phase ? 1 : 0);
        }
        return r;
    }

    /** 当前阶段摘要，例如 "S4 合并注册表 · 解冻注册表 (42%)"。 */
    public static String stageSummary() {
        int p = phase;
        if (p < 0) p = 0;
        if (p >= STAGE_COUNT) p = STAGE_COUNT - 1;
        String sub = stage == null ? "" : stage;
        return "S" + (p + 1) + " " + PHASE_NAMES[p] + " · " + sub + " (" + progress + "%)";
    }

    /** 加载界面底部滚动日志（最近最多 32 行，旧→新）；OLD_STEPPER 旧界面仍读它。 */
    public static String[] loadingLogLines() {
        synchronized (recentLogs) {
            return loadingLog.toArray(new String[0]);
        }
    }

    /**
     * 日志出现新汉字时，触发主窗口像素字缓存的按需光栅化。
     * 仅对 >126 的码位触发，且每个码位只光栅化一次；渲染线程不会走到这里（日志来自
     * Launcher tee 与 MC worker 线程），故 AWT 调用安全。
     */
    private static final java.util.Set<Integer> rasterizedCjk =
            java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());
    public static void ensureRasterized(String s) {
        if (s == null) return;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c <= 126) continue;
            int cp = c;
            if (!rasterizedCjk.add(cp)) continue;
            io.mirage.loader.ForgeStyleOverlay.rasterizeChar(c);
        }
    }

    /**
     * ASM 织入 VanillaPackResourcesBuilder.build() 时调用。
     * 1) 暴露 mod 命名空间
     * 2) 把 mod jar 的 assets 提取到临时目录，通过反射 pushPathForType/pushRootPath 加入
     */
    public static void exposeModNamespaces(Object builder) {
        try {
            Method expose = builder.getClass().getMethod("exposeNamespace", String[].class);
            expose.invoke(builder, (Object) modNamespaces.toArray(new String[0]));
            MirageLog.MIRAGE.debug("暴露 mod 命名空间: " + modNamespaces);

            java.lang.reflect.Method pushPath = null, pushRoot = null;
            for (Method m : builder.getClass().getDeclaredMethods()) {
                if (m.getName().equals("pushPathForType")) pushPath = m;
                if (m.getName().equals("pushRootPath")) pushRoot = m;
            }
            if (pushPath != null) pushPath.setAccessible(true);
            if (pushRoot != null) pushRoot.setAccessible(true);

            // 关键：用方法签名中的参数类型，避免类加载器隔离导致的 argument type mismatch
            Object clientResources = null, serverData = null;
            if (pushPath != null) {
                Class<?> packTypeClass = pushPath.getParameterTypes()[0];
                for (Object c : packTypeClass.getEnumConstants()) {
                    String name = ((Enum<?>) c).name();
                    if (name.equals("CLIENT_RESOURCES")) clientResources = c;
                    else if (name.equals("SERVER_DATA")) serverData = c;
                }
                if (clientResources == null) clientResources = packTypeClass.getEnumConstants()[0];
            }
            // P1-5：同时解析 SERVER_DATA 包类型，用于注册 mod 的 data/（配方/战利品/标签）
            MirageLog.MIRAGE.debug("资源包类型: clientResources=" + clientResources + ", serverData=" + serverData);

            for (String modId : modNamespaces) {
                try {
                    java.nio.file.Path modJar = findModJar(modId);
                    if (modJar == null) {
                        MirageLog.MIRAGE.debug("跳过 mod 资源（mods 目录里没有该命名空间的 jar）: " + modId);
                        continue;
                    }
                    java.nio.file.Path extractDir = java.nio.file.Files.createTempDirectory("mirage-" + modId);
                    extractAssets(modJar, extractDir);
                    java.nio.file.Path assetRoot = extractDir.resolve("assets");
                    java.nio.file.Path dataRoot = extractDir.resolve("data");
                    boolean hasAssets = java.nio.file.Files.exists(assetRoot);
                    boolean hasData = java.nio.file.Files.exists(dataRoot);
                    if (!hasAssets && !hasData) {
                        MirageLog.MIRAGE.debug("跳过 mod 资源（jar 内没有 assets/data 目录）: " + modId);
                        continue;
                    }
                    // P1-5：整目录作为根，使 assets/ 与 data/ 同时可见；再按类型显式 push
                    if (pushRoot != null) pushRoot.invoke(builder, extractDir);
                    if (hasAssets && pushPath != null && clientResources != null) {
                        pushPath.invoke(builder, clientResources, assetRoot);
                    }
                    if (hasData && pushPath != null && serverData != null) {
                        // data/ 作为 SERVER_DATA 注册：配方/战利品表/标签/advancements 等进入数据包管线
                        pushPath.invoke(builder, serverData, dataRoot);
                    }
                    MirageLog.MIRAGE.debug("已添加 mod 资源: " + modId
                            + " assets=" + hasAssets + " data=" + hasData + " -> " + extractDir);
                } catch (Exception e) {
                    MirageLog.MIRAGE.warn("添加 mod 资源失败: " + modId, e);
                }
            }
        } catch (Exception e) {
            MirageLog.MIRAGE.warn("暴露 mod 命名空间失败（mod 资源可能不入包）", e);
        }
    }

    /**
     * assets 命名空间 -> mod jar 的映射。
     * 按 jar 内实际存在的 assets/&lt;命名空间&gt;/ 反查，不依赖 jar 文件名
     * （旧实现用大小写敏感的文件名前缀匹配，导致 ExpOre-*.jar 这类文件名
     * 与 mod id "expore" 对不上，资源被静默跳过、方块/物品无模型贴图）。
     */
    private static final java.util.Map<String, java.nio.file.Path> ASSET_PACKS
            = new java.util.concurrent.ConcurrentHashMap<>();

    private static java.nio.file.Path findModJar(String modId) {
        scanAssetNamespaces();
        java.nio.file.Path p = ASSET_PACKS.get(modId);
        if (p != null && java.nio.file.Files.exists(p)) return p;
        return null;
    }

    /** 扫描 mods 目录下所有 jar，登记「assets/&lt;命名空间&gt; -> jar」，只需扫一次。 */
    private static void scanAssetNamespaces() {
        if (!ASSET_PACKS.isEmpty()) return;
        try {
            java.nio.file.Path modsDir = java.nio.file.Path.of(System.getProperty("user.dir")).resolve("mods");
            if (!java.nio.file.Files.exists(modsDir)) return;
            try (var stream = java.nio.file.Files.list(modsDir)) {
                for (java.nio.file.Path p : (Iterable<java.nio.file.Path>) stream::iterator) {
                    if (!p.getFileName().toString().endsWith(".jar")) continue;
                    try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(p.toFile())) {
                        java.util.Enumeration<? extends java.util.zip.ZipEntry> entries = zf.entries();
                        while (entries.hasMoreElements()) {
                            String name = entries.nextElement().getName();
                            if (!name.startsWith("assets/")) continue;
                            int slash = name.indexOf('/', "assets/".length());
                            if (slash < 0) continue;
                            String ns = name.substring("assets/".length(), slash);
                            if (!ns.isEmpty()) ASSET_PACKS.putIfAbsent(ns, p);
                        }
                    }
                }
            }
            MirageLog.MIRAGE.debug("扫描 assets 命名空间: " + ASSET_PACKS.keySet());
        } catch (Exception e) {
            MirageLog.MIRAGE.warn("扫描 assets 命名空间失败（mod 资源可能不入包）", e);
        }
    }

    private static void extractAssets(java.nio.file.Path jar, java.nio.file.Path dest) throws Exception {
        try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(jar.toFile())) {
            java.util.Enumeration<? extends java.util.zip.ZipEntry> e = zf.entries();
            while (e.hasMoreElements()) {
                java.util.zip.ZipEntry entry = e.nextElement();
                String name = entry.getName();
                // P1-5：同时解压 assets/ 与 data/。data/ 稍后作为 SERVER_DATA 注册进数据包管线
                if (!name.startsWith("assets/") && !name.startsWith("data/")) continue;
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

    /**
     * 添加一行日志（节选：只留 Mirage 自身与错误/告警）。
     * Launcher 的 stdout tee 与 HUD 共用，保持最近 8 行。
     */
    public static void log(String line) {
        if (line == null) return;
        String s = line.strip();
        if (s.isEmpty() || s.length() > 200) {
            if (s.length() > 200) s = s.substring(0, 200);
            else return;
        }
        if (!isImportantLog(s)) return;
        synchronized (recentLogs) {
            if (s.equals(lastLogLine)) return;
            lastLogLine = s;
            if (recentLogs.size() >= LOG_CAPACITY) recentLogs.pollFirst();
            recentLogs.addLast(s);
            if (loadingLog.size() >= LOADING_LOG_CAPACITY) loadingLog.pollFirst();
            loadingLog.addLast(s);
        }
        // 事件流（真实日志 + 阶段/meter 事件共用同一个环形缓冲）；通道决定行的颜色。
        LoadProgress.event(LoadProgress.channelOf(s), cleanLog(s));
        if (s.contains("WARN") || s.contains("警告")) LoadProgress.increment("warn");
        // 主窗口像素字缓存按需光栅化汉字（早期窗口走 STB 图集，无须此路径）。
        // 只在调用线程上做 AWT 光栅化，不在渲染线程，安全。
        ensureRasterized(s);
    }

    /** 去掉 log4j 的 "[14:54:14] [main/INFO]: " 外壳，事件流只留正文。 */
    private static String cleanLog(String s) {
        int i = s.indexOf("]: ");
        return (s.startsWith("[") && i >= 0) ? s.substring(i + 3) : s;
    }

    /**
     * HUD / 事件流节选的准入判断。
     *
     * <p>行首可能带 log4j 前缀（{@code [14:54:14] [main/INFO]: [STDOUT]: }），所以只能用
     * {@code contains} 而不是 {@code startsWith}。2026-11-07 起从「5 个 Mirage 自家前缀」
     * 放宽到通道表：mod 自己的日志、Fabric/NeoForge 兼容层、错误分级通道都要能进事件流，
     * 否则 S4/S7 长等待期间面板完全静止。DEBUG 仅在显式打开 debug 级别时进 UI。
     */
    private static final String[] KNOWN_PREFIXES = {
            "[Mirage", "[LS]", "[Loader]", "[MidCache]", "[MID]",
            "[Fabric]", "[NeoForge]", "[MG-FATAL]", "[MG-ERROR]", "[MG-WARN]", "[MG-IGNORE]"
    };
    private static final boolean UI_DEBUG =
            "debug".equalsIgnoreCase(System.getProperty("mirage.log.level", ""));

    private static boolean isImportantLog(String s) {
        if (!UI_DEBUG && (s.contains("DEBUG") || s.contains("/DEBUG"))) return false;
        for (String p : KNOWN_PREFIXES) {
            if (s.contains(p)) return true;
        }
        return s.contains("ERROR") || s.contains("WARN")
                || s.contains("Exception") || s.contains("Caused by")
                || s.contains("失败") || s.contains("错误");
    }

    /** 进程 CPU 占用（0~100），取不到时退化为系统负载 */
    public static int cpuPercent() {
        try {
            if (osBean instanceof com.sun.management.OperatingSystemMXBean sun) {
                double v = sun.getProcessCpuLoad();
                if (v < 0) v = sun.getCpuLoad();
                if (v >= 0) return (int) Math.round(Math.min(1.0, v) * 100);
            }
        } catch (Throwable ignored) {
        }
        double avg = osBean.getSystemLoadAverage();
        if (avg < 0) return 0;
        return (int) Math.round(Math.min(1.0, avg / Math.max(1, osBean.getAvailableProcessors())) * 100);
    }

    public static long memUsedMB() {
        return (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / (1024 * 1024);
    }

    public static long memMaxMB() {
        return Runtime.getRuntime().maxMemory() / (1024 * 1024);
    }

    public static String memLine() {
        return "mem " + memUsedMB() + "/" + memMaxMB() + " MB";
    }

    public static String cpuLine() {
        return "cpu " + cpuPercent() + "%";
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
                MirageLog.MIRAGE.trace("drawTextSafe: font==null, skip \"" + text + "\"");
            }
            return;
        }
        if (!fontReportedNonNull) {
            fontReportedNonNull = true;
            MirageLog.MIRAGE.debug("绘制文本: 字体已就绪 字体类=" + font.getClass().getName()
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
            MirageLog.MIRAGE.debug("绘制文本: 解析到方法 = " + cachedText);
        }
        if (cachedText == null) {
            if ((diagCounter++ & 63) == 0) {
                MirageLog.MIRAGE.debug("绘制文本: 在该类上未找到方法: " + gge.getClass());
            }
            return;
        }
        try {
            cachedText.invoke(gge, font, text, x, y, color);
        } catch (Throwable t) {
            if ((diagCounter++ & 63) == 0) {
                MirageLog.MIRAGE.warn("绘制文本调用失败", t);
            }
        }
    }

    // =====================================================================
    //  Fabric 式启动 GUI：不建任何独立窗口，全部画在 MC 原版 GUI 上
    //
    //  · LoadingOverlay（资源重载期）：左上角调试卡片 + 进度条。
    //    绝对禁止调用 gge.text()——会提前触发 gui_text 管线，26.2 重载期
    //    core/text shader 尚未就绪，会导致管线永久失败。这里改为：
    //    AWT 把文字光栅化成像素块，再用 gge.fill() 逐段绘制，只依赖 gui 管线。
    //  · TitleScreen：左上角 Mirage 徽标 + Mods 按钮，点击开合详情面板/Mod 列表。
    // =====================================================================

    /** 标题界面出现时由 ASM 织入的 TitleScreen.init() 调用 */
    public static void onTitleScreenShown() {
        loaded = true;
        // TitleScreen 是加载期的真正终点：收掉所有长驻 meter（尤其是 S7 的 MC 主类不定条），
        // 分段总条填满，事件流留一条 "加载完成 · 总耗时"。
        if (!LoadProgress.isFinished()) LoadProgress.finish();
        // 收尾溶解的起点：标题画面出现的那一刻。是否真的溶解由 ForgeStyleOverlay 判断
        // （遮罩满不透明消失才补，已被 MC 正常淡出的不补，否则反过来制造跳变）。
        ForgeStyleOverlay.beginExitDissolve();
        // 性能锚点：TitleScreen 就绪 = 用户真正能操作游戏的时刻。
        // S7 Loaded done 只是「mod 管线结束」，MC 自身的资源重载还没走完——
        // 没有这个锚点就答不出「点开游戏到能玩要多久」（docs/perf-baseline.md §5 的缺口 3）。
        MiragePerf.mark("titlescreen");
    }

    private static volatile Method cachedFill;
    private static volatile Method cachedGuiWidth;
    private static volatile Method cachedGuiHeight;

    /** 解析 GuiGraphicsExtractor.fill(IIIII)V（编译期没有 MC jar，只能反射） */
    static Method fillMethod(Object gge) throws NoSuchMethodException {
        Method m = cachedFill;
        if (m == null || !m.getDeclaringClass().isAssignableFrom(gge.getClass())) {
            m = gge.getClass().getMethod("fill", int.class, int.class, int.class, int.class, int.class);
            cachedFill = m;
        }
        return m;
    }

    static int guiSize(Object gge, boolean width) {
        try {
            Method m = width ? cachedGuiWidth : cachedGuiHeight;
            if (m == null) {
                m = gge.getClass().getMethod(width ? "guiWidth" : "guiHeight");
                if (width) cachedGuiWidth = m;
                else cachedGuiHeight = m;
            }
            Object v = m.invoke(gge);
            if (v instanceof Integer i) return i;
        } catch (Throwable ignored) {
        }
        return width ? 320 : 240;
    }

    static void fillRect(Object gge, Method fill, int x1, int y1, int x2, int y2, int color) {
        try {
            fill.invoke(gge, x1, y1, x2, y2, color);
        } catch (Throwable t) {
            if ((diagCounter++ & 255) == 0) {
                MirageLog.MIRAGE.trace("fill 调用失败", t);
            }
        }
    }

    // ---- 像素字：AWT 光栅化 + fill 绘制，绕开 Font/gui_text 管线 ----
    private static final java.util.Map<String, int[]> PIXEL_TEXT_CACHE = new java.util.concurrent.ConcurrentHashMap<>();
    private static volatile boolean pixelTextUnavailable;

    /** HUD 用的像素字体（12px 微软雅黑）；Forge 风格界面用 Monocraft，见 ForgeStyleOverlay */
    private static final java.awt.Font HUD_FONT =
            new java.awt.Font("Microsoft YaHei", java.awt.Font.PLAIN, 12);

    /** 返回 [宽, 高, x, y, w, h, ...]，每个四元组是一段可合并的像素矩形 */
    static int[] rasterizePixelText(String text, java.awt.Font font) {
        java.awt.image.BufferedImage probe =
                new java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D pg = probe.createGraphics();
        pg.setFont(font);
        java.awt.FontMetrics fm = pg.getFontMetrics();
        int width = Math.max(1, fm.stringWidth(text));
        int height = Math.max(1, fm.getAscent() + fm.getDescent());
        pg.dispose();

        java.awt.image.BufferedImage img =
                new java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = img.createGraphics();
        g.setFont(font);
        g.setColor(java.awt.Color.WHITE);
        g.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING,
                java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
        g.drawString(text, 0, fm.getAscent());
        g.dispose();

        java.util.List<int[]> quads = new java.util.ArrayList<>();
        java.util.List<Integer> prevRow = new java.util.ArrayList<>();
        for (int yy = 0; yy < height; yy++) {
            java.util.List<Integer> curRow = new java.util.ArrayList<>();
            int start = -1;
            for (int xx = 0; xx <= width; xx++) {
                boolean on = xx < width && ((img.getRGB(xx, yy) >>> 24) >= 80);
                if (on && start < 0) {
                    start = xx;
                } else if (!on && start >= 0) {
                    int len = xx - start;
                    boolean merged = false;
                    for (int idx : prevRow) {
                        int[] q = quads.get(idx);
                        if (q[0] == start && q[2] == len) {
                            q[3]++;
                            curRow.add(idx);
                            merged = true;
                            break;
                        }
                    }
                    if (!merged) {
                        quads.add(new int[]{start, yy, len, 1});
                        curRow.add(quads.size() - 1);
                    }
                    start = -1;
                }
            }
            prevRow = curRow;
        }

        int[] out = new int[2 + quads.size() * 4];
        out[0] = width;
        out[1] = height;
        int i = 2;
        for (int[] q : quads) {
            out[i++] = q[0];
            out[i++] = q[1];
            out[i++] = q[2];
            out[i++] = q[3];
        }
        return out;
    }

    /**
     * 抗锯齿灰度字形：返回 {@code [宽, 高, (x, y, w, h, coverage)×n]}。
     *
     * <p>与 {@link #rasterizePixelText} 的区别（2026-11-07，用户否决主加载界面的点阵字）：
     * <ol>
     *   <li>{@code VALUE_TEXT_ANTIALIAS_ON} + {@code VALUE_RENDER_QUALITY}，字形边缘是灰度过渡，
     *       不是 {@code alpha>=80} 一刀切的二值点阵；</li>
     *   <li>每个像素的覆盖率按 {@code ceil(alpha/64)} 量化到 1..4 档（{@code alpha<16} 的噪声丢弃），
     *       跟随矩形一起返回，绘制端按档与背景混色；</li>
     *   <li>同档相邻像素合成水平 run，上下两行同 x/宽/档的 run 再合成竖向块 —— 档位量化
     *       正是为了把 fill 次数压在与原二值方案同一量级，避免渲染线程被反射调用拖垮。</li>
     * </ol>
     *
     * <p>只在 {@code ForgeStyleOverlay.prewarm()} / {@code rasterizeChar()}（非渲染线程）里调用。
     * HUD 的 {@link #pixelText} 仍走旧的 {@link #rasterizePixelText}，不受影响。
     */
    static int[] rasterizeSmoothText(String text, java.awt.Font font) {
        java.awt.image.BufferedImage probe =
                new java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D pg = probe.createGraphics();
        pg.setFont(font);
        java.awt.FontMetrics fm = pg.getFontMetrics();
        int width = Math.max(1, fm.stringWidth(text));
        int height = Math.max(1, fm.getAscent() + fm.getDescent());
        pg.dispose();

        java.awt.image.BufferedImage img =
                new java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = img.createGraphics();
        g.setFont(font);
        g.setColor(java.awt.Color.WHITE);
        g.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING,
                java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(java.awt.RenderingHints.KEY_RENDERING,
                java.awt.RenderingHints.VALUE_RENDER_QUALITY);
        g.drawString(text, 0, fm.getAscent());
        g.dispose();

        java.util.List<int[]> quads = new java.util.ArrayList<>();
        java.util.List<Integer> prevRow = new java.util.ArrayList<>();
        for (int yy = 0; yy < height; yy++) {
            java.util.List<Integer> curRow = new java.util.ArrayList<>();
            int xx = 0;
            while (xx < width) {
                int a = (img.getRGB(xx, yy) >>> 24) & 0xFF;
                int level = a < 16 ? 0 : Math.min(4, (a + 63) / 64);
                if (level == 0) {
                    xx++;
                    continue;
                }
                int start = xx;
                while (xx < width) {
                    int an = (img.getRGB(xx, yy) >>> 24) & 0xFF;
                    int q = an < 16 ? 0 : Math.min(4, (an + 63) / 64);
                    if (q != level) break;
                    xx++;
                }
                int len = xx - start;
                boolean merged = false;
                for (int idx : prevRow) {
                    int[] quad = quads.get(idx);
                    if (quad[0] == start && quad[2] == len && quad[4] == level) {
                        quad[3]++;
                        curRow.add(idx);
                        merged = true;
                        break;
                    }
                }
                if (!merged) {
                    quads.add(new int[]{start, yy, len, 1, level});
                    curRow.add(quads.size() - 1);
                }
            }
            prevRow = curRow;
        }

        int[] out = new int[2 + quads.size() * 5];
        out[0] = width;
        out[1] = height;
        int i = 2;
        for (int[] q : quads) {
            out[i++] = q[0];
            out[i++] = q[1];
            out[i++] = q[2];
            out[i++] = q[3];
            out[i++] = q[4];
        }
        return out;
    }

    private static int[] pixelMask(String text) {
        int[] mask = PIXEL_TEXT_CACHE.get(text);
        if (mask == null) {
            if (PIXEL_TEXT_CACHE.size() > 128) PIXEL_TEXT_CACHE.clear();
            mask = rasterizePixelText(text, HUD_FONT);
            PIXEL_TEXT_CACHE.put(text, mask);
        }
        return mask;
    }

    private static void pixelText(Object gge, Method fill, String text, int x, int y, int color, int maxWidth) {
        if (text == null || text.isEmpty() || pixelTextUnavailable) return;
        try {
            int[] mask = pixelMask(text);
            int limit = Math.min(mask[0], maxWidth);
            for (int i = 2; i + 3 < mask.length; i += 4) {
                int rx = mask[i];
                if (rx >= limit) continue;
                int ry = mask[i + 1];
                int rw = mask[i + 2];
                int rh = mask[i + 3];
                if (rx + rw > limit) rw = limit - rx;
                fillRect(gge, fill, x + rx, y + ry, x + rx + rw, y + ry + rh, color);
            }
        } catch (Throwable t) {
            pixelTextUnavailable = true;
            MirageLog.MIRAGE.warn("像素字不可用，后续只画进度条", t);
        }
    }

    // HUD 调色板：贴近原版 F3 调试屏，不用装饰性配色
    private static final int HUD_BG = 0xB4000000;
    private static final int HUD_LINE = 0xFF3C3C3C;
    private static final int HUD_WHITE = 0xFFFFFFFF;
    private static final int HUD_GRAY = 0xFFAAAAAA;
    private static final int HUD_DIM = 0xFF777777;
    private static final int HUD_GREEN = 0xFF55FF55;
    private static final int HUD_YELLOW = 0xFFFFFF55;
    private static final int HUD_RED = 0xFFFF5555;

    private static int logColor(String line) {
        if (line.contains("ERROR") || line.contains("Exception")
                || line.contains("失败") || line.contains("错误")) return HUD_RED;
        if (line.contains("WARN")) return HUD_YELLOW;
        return HUD_DIM;
    }

    /**
     * ASM 在 LoadingOverlay.extractRenderState 每个 RETURN 前调用。
     *
     * <p>原生 MC 窗口的加载界面改为「Forge 式」——与早期窗口同一套布局
     * （红底 / 内存条 / 两条进度条 / 右下版本号，无铁砧动画），
     * 由 {@link ForgeStyleOverlay} 绘制。
     */
    public static void drawLoadingOverlay(Object overlay, Object gge) {
        ForgeStyleOverlay.draw(overlay, gge);
    }

    /** ASM 在 TitleScreen.extractRenderState 每个 RETURN 前调用 */
    public static void drawTitleOverlay(Object gge, Object font) {
        if (gge == null) return;
        // 收尾交叉溶解（第一层）：加载遮罩若「满不透明消失」就是硬切，这里先整屏盖住
        // 再 600ms 线性淡出，把仪表盘溶解成 MC 自己的 Logo / 标题画面。
        // alpha 归零后本调用即空转，不影响后续任何标题画面绘制。
        int dissolve = ForgeStyleOverlay.exitDissolveAlpha();
        if (dissolve > 0) ForgeStyleOverlay.drawExitFrame(gge, dissolve);
        try {
            Method fill = fillMethod(gge);
            // 左上角徽标（朴素 HUD 条，无装饰线）
            fillRect(gge, fill, 5, 5, 196, 19, HUD_BG);
            drawTextSafe(gge, font, version + "  //  " + modCount + " mods", 9, 8, HUD_GREEN);
            // Mods 按钮
            fillRect(gge, fill, 199, 5, 236, 19, showMods ? 0xFF2A4A2A : 0xFF2A2A2A);
            drawTextSafe(gge, font, "Mods", 204, 8, showMods ? HUD_GREEN : HUD_WHITE);

            if (showPanel) {
                int gw = guiSize(gge, true);
                int w = Math.min(360, Math.max(220, gw - 10));
                fillRect(gge, fill, 5, 23, 5 + w, 173, HUD_BG);
                drawTextSafe(gge, font, version + "  //  " + mcVersion, 9, 28, HUD_GREEN);
                drawTextSafe(gge, font, stageLine(), 9, 42, HUD_WHITE);
                drawTextSafe(gge, font, modLine() + "   " + progressLine(), 9, 56, HUD_GRAY);
                drawTextSafe(gge, font, cpuLine() + "   " + memLine(), 9, 70, HUD_GRAY);
                fillRect(gge, fill, 9, 84, 9 + w - 8, 86, 0xFF2A2A2A);
                int bw = (int) Math.round((w - 16) * Math.max(0, Math.min(100, progress)) / 100.0);
                if (bw > 0) fillRect(gge, fill, 9, 84, 9 + bw, 86, HUD_GREEN);
                fillRect(gge, fill, 9, 90, 9 + w - 8, 91, HUD_LINE);
                String[] logs = recentLogLines();
                int start = Math.max(0, logs.length - 5);
                for (int i = start; i < logs.length; i++) {
                    drawTextSafe(gge, font, logs[i], 9, 95 + (i - start) * 12, logColor(logs[i]));
                }
                drawTextSafe(gge, font, "click to close", 9, 158, HUD_DIM);
            }
            if (showMods) {
                int gw = guiSize(gge, true);
                int gh = guiSize(gge, false);
                fillRect(gge, fill, 0, 0, gw, gh, 0xE0000000);
                drawTextSafe(gge, font, "MIRAGE // MODS", 20, 20, HUD_GREEN);
                drawTextSafe(gge, font, modCount + " loaded   " + progress + "%", 20, 34, HUD_GRAY);
                String[] ids = modListLine().split(", ");
                for (int i = 0; i < ids.length && i < 12; i++) {
                    drawTextSafe(gge, font, "- " + ids[i], 20, 52 + i * 12, HUD_WHITE);
                }
                drawTextSafe(gge, font, version + "  //  " + mcVersion, 20, gh - 32, HUD_GRAY);
                drawTextSafe(gge, font, "click to close", 20, gh - 18, HUD_DIM);
            }
        } catch (Throwable t) {
            if ((diagCounter++ & 63) == 0) {
                MirageLog.MIRAGE.warn("绘制标题界面 HUD 失败", t);
            }
        }
    }

    /**
     * ASM 在 TitleScreen.mouseClicked(MouseButtonEvent, boolean) 开头调用。
     * 返回 true 表示这次点击已被 Mirage 消费。
     */
    public static boolean handleTitleClick(double mouseX, double mouseY) {
        if (showPanel || showMods) {
            showPanel = false;
            showMods = false;
            return true;
        }
        if (mouseX >= 5 && mouseX <= 196 && mouseY >= 5 && mouseY <= 19) {
            showPanel = true;
            return true;
        }
        if (mouseX >= 199 && mouseX <= 236 && mouseY >= 5 && mouseY <= 19) {
            showMods = true;
            return true;
        }
        return false;
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
    private static java.lang.reflect.Field f_tabColumn;     // CreativeModeTab.column : int（直接读字段，绕开getter的bootstrap检查）
    private static java.lang.reflect.Field f_tabRow;        // CreativeModeTab.row : Row
    private static java.lang.reflect.Field f_tabType;       // CreativeModeTab.type : Type
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
            ClassLoader cl = MirageDebug.class.getClassLoader();
            // 关键：Class.forName第二个参数initialize=false，只加载类元数据，不触发静态初始化链，避免提前触发BuiltInRegistries的bootstrap检查
            Class<?> cTabs = Class.forName("net.minecraft.world.item.CreativeModeTabs", false, cl);
            m_tabs = cTabs.getMethod("tabs");
            m_tabs.setAccessible(true);
            Class<?> cTab = Class.forName("net.minecraft.world.item.CreativeModeTab", false, cl);
            // 直接读字段，不调用getter方法
            f_tabColumn = cTab.getDeclaredField("column");
            f_tabColumn.setAccessible(true);
            f_tabRow = cTab.getDeclaredField("row");
            f_tabRow.setAccessible(true);
            f_tabType = cTab.getDeclaredField("type");
            f_tabType.setAccessible(true);
            // 枚举初始化延后到真正需要的时候再做，避免提前触发静态块
            tabReflectReady = true;
            MirageLog.MIRAGE.debug("标签页反射就绪（延迟初始化，不触发静态块）");
        } catch (Throwable t) {
            MirageLog.MIRAGE.warn("标签页反射初始化失败，回退单页（不启用分页）", t);
            tabReflectReady = false;
            totalPages = 1; // 反射失败时彻底关闭分页，不画翻页按钮，不干预原版逻辑
        }
    }

    /** 计算 totalPages：遍历 tabs() 找最大 column */
    @SuppressWarnings("unchecked")
    private static void rebuildTabPages() {
        ensureTabReflect();
        if (!tabReflectReady) return;
        try {
            // 懒加载枚举值
            if (rowTop == null) {
                ClassLoader cl = MirageDebug.class.getClassLoader();
                Class<?> cRow = Class.forName("net.minecraft.world.item.CreativeModeTab$Row", false, cl);
                rowTop = Enum.valueOf(cRow.asSubclass(Enum.class), "TOP");
                rowBottom = Enum.valueOf(cRow.asSubclass(Enum.class), "BOTTOM");
                Class<?> cType = Class.forName("net.minecraft.world.item.CreativeModeTab$Type", false, cl);
                typeSearch = Enum.valueOf(cType.asSubclass(Enum.class), "SEARCH");
            }
            java.util.List<Object> all = (java.util.List<Object>) m_tabs.invoke(null);
            int maxCol = 0;
            for (Object tab : all) {
                try {
                    Object type = f_tabType.get(tab);
                    if (typeSearch != null && typeSearch.equals(type)) continue;
                    int col = f_tabColumn.getInt(tab);
                    if (col > maxCol) maxCol = col;
                } catch (Throwable inner) {
                    MirageLog.MIRAGE.debug("标签页检查失败", inner);
                }
            }
            totalPages = Math.max(1, (maxCol + TABS_PER_PAGE) / TABS_PER_PAGE);
            MirageLog.MIRAGE.debug("重建标签页: 最大列=" + maxCol + " totalPages=" + totalPages);
        } catch (Throwable t) {
            MirageLog.MIRAGE.warn("重建标签页失败，回退单页（不启用分页）", t);
            totalPages = 1; // 失败时彻底关闭分页，不要强制2页
        }
    }

    /**
     * 计算 tab 在当前页的显示列号。
     * 直接用 tab.column() - tabPage*7，不依赖排序列表。
     * @return 0..6 表示显示列；-1 表示该 tab 不在当前页（应隐藏/离屏）
     */
    public static int getDisplayColumn(Object tab) {
        if (tab == null || totalPages <= 1) return 0; // 单页模式下完全不干预原版逻辑
        ensureTabReflect();
        if (!tabReflectReady) return 0;
        try {
            int col = f_tabColumn.getInt(tab);
            int displayCol = col - tabPage * TABS_PER_PAGE;
            if (displayCol < 0 || displayCol >= TABS_PER_PAGE) return -1;
            return displayCol;
        } catch (Throwable t) {
            return 0; // 单个tab反射失败时默认在当前页正常显示，不隐藏
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
        if (c < 0) return 0; // 修复：不可见tab直接返回0兜底，不再强制把所有负列号c=0
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
            Object row = f_tabRow.get(tab);
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
        MirageLog.MIRAGE.debug("标签页 -> " + tabPage + "/" + totalPages);
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
                MirageLog.MIRAGE.warn("绘制翻页控件失败", t);
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
