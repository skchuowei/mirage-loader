package io.mirage.loader;

import io.mirage.api.log.MirageLog;

import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 原生 MC 窗口里的「Forge 式」加载界面，<b>样式严格对齐早期窗口</b>。
 *
 * <p>早期窗口 {@code io.mirage.earlydisplay.MirageEarlyWindow} 是用 GL 直绘的，
 * 交接给 Minecraft 之后由本类在 {@code GuiGraphicsExtractor} 上把同一套界面重画一遍：
 * <ul>
 *   <li>红底 {@code #EF323D}</li>
 *   <li>顶部内存条 + {@code Memory/CPU} 文本（居中）</li>
 *   <li>{@code Loading mods}（不定进度）/ {@code Config loading}（实际进度）两条进度条</li>
 *   <li>右下角版本号</li>
 *   <li>按用户要求不画铁砧动画</li>
 * </ul>
 * 几何全部取自 {@link ForgeStyleLayout}，与早期窗口逐像素对应。
 *
 * <h2>文字为什么必须是「AWT 光栅化 + fill」而不是 {@code gge.text()}</h2>
 * 2026-10-31 实测：改用 {@code gge.text(Minecraft.font, ...)} 之后，
 * 红底、内存条、两条进度条全都正常，<b>一条文字都不出现，日志里也没有任何异常</b>。
 * 原因不在调用姿势，而在时机：
 * <ol>
 *   <li>{@code gge.text()} 本身只做一件事——把 {@code GuiTextRenderState}
 *       塞进 {@code GuiRenderState.addText()}，<b>不画任何像素、也不可能抛异常</b>；
 *       所以「调用成功」这件事毫无验证价值，失败必然表现为静默无字。</li>
 *   <li>真正出图时要用 {@code Minecraft.font} 的字形图集。而 LoadingOverlay 显示期
 *       正好是<b>首次资源重载进行中</b>（overlay 的存在意义就是等这次重载），
 *       字形提供器还没装载 → 字形表为空 → 每个字符解析不出字形 → 什么都不画。</li>
 *   <li>原版 {@code LoadingOverlay.extractRenderState} 自己一个 {@code text()} 都不调，
 *       只做 fill + logo blit，正是这个原因（javap 26.2 核实过）。</li>
 * </ol>
 * 所以本阶段<b>唯一可用</b>的文字通道是把字光栅化成矩形块再用 {@code fill} 画——
 * fill 与 blit 在重载期是好的（进度条就是这么画出来的）。
 *
 * <h2>2026-11-07：不再是像素字（抗锯齿灰度字形）</h2>
 * 原实现 {@code MirageDebug.rasterizePixelText} 用 {@code VALUE_TEXT_ANTIALIAS_OFF} +
 * {@code alpha>=80} 二值阈值，把 AWT 字拆成 1px 实心矩形 —— 用户实机截图反馈是
 * 「像素风格文字」：硬边点阵、小字号下笔画粘连。现改为 {@link MirageDebug#rasterizeSmoothText}：
 * 抗锯齿开、逐像素覆盖率量化成 4 档，{@code drawText} 按档把前景色与所在背景<b>预混</b>后再 fill。
 * <b>不用 alpha 混合表达字形灰度</b>，因为 fill 是否走 blend 取决于 MC 当时的渲染状态；
 * 把灰度烘进 RGB 就不依赖 blend，而淡入淡出的整体 alpha 仍照旧由 {@code pack(alpha, rgb)} 承担。
 * 三条约束一个没松：禁用 {@code gge.text()}、渲染线程零 AWT、字符级预渲染只在 {@link #prewarm()}。
 *
 * <h2>2026-11-07 三修：与早期窗口 1:1 同源字形</h2>
 * 上一修把 ASCII 字体换成了系统 UI 无衬线（Segoe UI / 雅黑 UI），实机两张截图显示
 * <b>两个窗口风格不一致</b>：早期窗口是 {@code EarlyFont} 用 STB 打包的
 * {@code Monocraft.ttf} 细等宽字形 + {@code msyh.ttc} 中文，本窗口却成了加粗圆润的无衬线。
 * 现把字体源集中到 {@link LoadingFonts}：ASCII 读同一个 {@code /Monocraft.ttf}
 * （AWT 光栅化，仍走上面的抗锯齿灰度管线）、CJK 读 {@code LoadingFonts.findCjkFont()}
 * 给的同一个文件（TTC 第 0 个，与 STB 的 {@code cjkFontIndex=0} 一致）。
 * 两条渲染管线不同（GL 纹理 quad vs AWT + fill），但字形同源；<b>布局常量一字未动</b>。
 *
 * <h2>2026-10-31 二次修复：渲染线程绝不允许碰 AWT（字符级预渲染）</h2>
 * 第一版把光栅化直接放在 {@code draw()} 里同步做，结果窗口「未响应」+ 日志被抢写——
 * 根因是<b>渲染线程每帧都在跑 Java2D 字体管线</b>（每帧重新光栅化、反射 fill 几百次），
 * GLFW 消息泵被占住超过 5 秒，Windows 就把窗口标成「未响应」。
 * 第二版改成「整串异步光栅化 + 渲染线程查整串缓存」，但仍是软肋：
 * 首帧必然无字、低优先级守护线程在加载繁忙时可能被饿死。
 * 现在采用<b>字符级预渲染</b>，彻底解决：
 * <ul>
 *   <li>{@link #prewarm()} 在<b>启动早期、主线程</b>（Launcher.main，远在渲染循环之前）
 *       用 AWT 一次性把所有 ASCII 可见字符光栅化成单字符字形，存进 {@link #GLYPH_CACHE}；
 *       字体枚举这种几十到几百毫秒的活只付一次，且不在渲染线程上。</li>
 *   <li>渲染线程的 {@link #draw(Object, Object)} 只做「查表拼装 + 反射 fill」，
 *       <b>零 AWT</b>——任意字符串（含每帧在变的 {@code Memory/CPU} 行）都能即时拼装出字，
 *       首帧即有字，不会再卡顿，也不依赖任何后台线程的调度时机。</li>
 *   <li>等宽字体优先（Consolas → Cascadia Mono → … → MONOSPACED 兜底），按字符等宽步进拼装，
 *       拼出来的行与 MC 自带位图字体的观感一致。</li>
 * </ul>
 *
 * <h2>历史：像素字怎么才不糊（字号结论仍适用于灰度字）</h2>
 * 糊成马赛克的真因是<b>光栅化尺寸没跟目标尺寸对齐</b>：固定按 12px 光栅化，
 * 再在 GUI 空间里缩放绘制，相邻字形像素被压进同一 GUI 像素、另一列被跳过，边缘必糊。
 * 现在<b>在 GUI 空间里取固定可读字号</b>（{@link #GUI_TEXT_PX}，不跟着设计空间 854×480 缩放），
 * 与 MC 自带字体在 guiScale&gt;1 时的放大方式一致，不会再糊。
 *
 * <h2>为什么不用 Window.getGuiScale()</h2>
 * 早期版本据此换算坐标，而 guiScale 在加载初期还是 0，整段绘制被
 * {@code if (guiScale <= 0) return null} 直接跳过——加载界面因此<b>整块消失</b>。
 * 现在只用 {@code gge.guiWidth()/guiHeight()}：它本身就是缩放后的尺寸，
 * 设计坐标按比例映射即可，不依赖任何可能尚未就绪的状态。
 */
public final class ForgeStyleOverlay {

    /** GUI 空间里的实际字号（像素字必须 ≥9px 才不糊，见 {@link #textPixelSize}）。 */
    private static final int GUI_TEXT_PX = 10;
    /** 光栅化字号下限 / 上限，防止极端缩放下画出不可读或过大的字。 */
    private static final int MIN_TEXT_PX = 9;
    private static final int MAX_TEXT_PX = 16;

    /** 动态文本（内存/CPU）的采样间隔：动态值没必要每帧重拼装一次。 */
    private static final long TEXT_SNAPSHOT_MS = 250L;

    /**
     * 回滚开关：{@code -Dmirage.ui.oldStepper=true} 恢复 81ead56 的「S1–S7 步骤条 + 单条假百分比」界面。
     * 默认走新仪表盘。旧分支同时被 {@code scripts/verify_forgestyle_text.ps1} 用来验证旧形态没被打破。
     */
    private static final boolean OLD_STEPPER = Boolean.getBoolean("mirage.ui.oldStepper");
    /** 进度仪表盘的文本快照间隔：秒表 0.1s 一变，200ms 一采足够。 */
    private static final long VIEW_SNAPSHOT_MS = 200L;
    /** 不定进度动画的帧计数（与主窗口 draw 同频）。 */
    private static final AtomicInteger FRAME = new AtomicInteger();
    private static volatile LoadProgress.View uiView;
    private static volatile long uiViewAt;
    /**
     * 当前帧画布尺寸（GUI 像素）。{@code draw()} 每帧开头写、{@link #drawText} 读，
     * 都在同一个渲染线程；用来把画布外的字形块裁掉，而不是去改布局常量。
     */
    private static volatile int canvasW;
    private static volatile int canvasH;

    /**
     * 标题画面首次出现时的收尾溶解（遮罩满不透明消失 → 硬切 的兜底）。
     *
     * <p>时长 600ms：比加载遮罩自身的淡出（MC 是 1s）短，避免和 MC 的淡出叠成「拖沓」；
     * 又足够长到人眼能看出是渐变而不是跳变。
     */
    private static final ExitDissolve EXIT_DISSOLVE = new ExitDissolve(600L);
    /** 加载遮罩是否画过（没画过就不该在标题画面上盖红屏）。 */
    private static volatile boolean overlayDrawn;
    /** 遮罩最后一次画出来的不透明度；满不透明消失 = 需要补溶解。 */
    private static volatile int lastDrawnAlpha = 255;
    /** 收尾溶解是否已打过结束锚点（只打一次，避免刷日志）。 */
    private static volatile boolean exitDissolveLogged;
    /**
     * 遮罩最后一帧用过的视图快照。
     *
     * <p>收尾溶解必须和遮罩最后一帧<b>逐像素一致</b>，否则接缝处会「少几条 meter」
     * 反而更突兀。标题画面出现时 {@link LoadProgress#finish()} 已经把 meter 清空，
     * 所以这里留一份快照给溶解用，不去复用现场数据。
     */
    private static volatile LoadProgress.View lastDrawnView;

    /**
     * 字符字形缓存：key = 字号 + '\u0000' + 字符；
     * value = {@code [advance, glyphW, glyphH, (x,y,w,h,coverage)×n]}，
     * 矩形相对字符自身原点，{@code coverage} ∈ 1..4 是抗锯齿覆盖率档位。
     * 全部在 {@link #prewarm()} 里一次性用 AWT 光栅化（启动早期、主线程），
     * 渲染线程只<b>查表拼装</b>——绝不在渲染线程碰 AWT（否则 GLFW 消息泵停摆 → 未响应）。
     */
    private static final Map<String, int[]> GLYPH_CACHE = new ConcurrentHashMap<>();
    /** 各字号下空格的步进宽（比例字体下空格与缺字都按它留白）。 */
    private static final Map<Integer, Integer> ADVANCE = new ConcurrentHashMap<>();
    /** 字符表是否就绪：未就绪时 draw 跳过文字（下一帧就有），绝不在渲染线程阻塞或光栅化。 */
    private static volatile boolean glyphReady;

    /** 光栅化用字体家族；只在 {@link #prewarm()} 里初始化（枚举系统字体很慢）。 */
    private static volatile Font baseFont;

    /** AWT 不可用 / 光栅化抛错后置位，之后只画条不再重试。 */
    private static volatile boolean textUnavailable;
    /** 首次成功出字后打一行 INFO，证明文字通道真的通了。 */
    private static volatile boolean textDrawnLogged;

    /** 不定进度动画的帧计数。 */
    private static final AtomicInteger DIAG = new AtomicInteger();

    // ---- 反射字段缓存：上一版每帧 getDeclaredField 三次，纯属浪费 ----
    private static volatile Field fadeOutStartField;
    private static volatile Field fadeInField;
    private static volatile Field fadeInStartField;

    // ---- 动态文本快照（250 ms 一采） ----
    private static volatile String perfLine = "";
    private static volatile long perfLineAt;

    private ForgeStyleOverlay() {
    }

    /**
     * 预热：在<b>主线程、渲染循环之前</b>用 AWT 把 ASCII 可见字符全部光栅化成字形缓存。
     *
     * <p>由启动器 {@code Launcher.main} 在进入 MC 主循环之前同步调用。这一步是唯一允许
     * 接触 AWT 的地方，且发生在渲染线程启动之前——字体枚举（几十~几百毫秒）与逐字符光栅化
     * 的代价只付一次，既不卡渲染线程，也不让加载界面首帧无字。
     * 异步版曾把光栅化丢给守护线程，但首帧必然 miss、低优先级线程还可能被加载繁忙饿死，
     * 故改为此处同步预渲染。
     */
    public static void prewarm() {
        if (glyphReady) return;
        try {
            for (int px : new int[]{GUI_TEXT_PX, GUI_TEXT_PX - 1}) {
                Font f = font(px);
                Font cf = cjkFont(px);
                java.awt.image.BufferedImage probe =
                        new java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_ARGB);
                java.awt.Graphics2D pg = probe.createGraphics();
                pg.setFont(f);
                // 比例字体下空格/缺字按真实空格宽留白，不能再拿等宽 '0' 顶替
                ADVANCE.put(px, Math.max(1, pg.getFontMetrics().charWidth(' ')));
                pg.dispose();
                for (char ch = 32; ch <= 126; ch++) {
                    int[] g = MirageDebug.rasterizeSmoothText(String.valueOf(ch), f);
                    // g = [w, h, (x,y,w,h,c)×n] → [advance=w, w, h, (x,y,w,h,c)×n]
                    int[] store = new int[3 + (g.length - 2)];
                    // 单字符的 stringWidth 就是该字体下的真实 advance；比例字体也能逐字对齐
                    store[0] = g[0];
                    store[1] = g[0];
                    store[2] = g[1];
                    System.arraycopy(g, 2, store, 3, g.length - 2);
                    GLYPH_CACHE.put(px + "\u0000" + ch, store);
                }
                // 汉字：ASCII 的 UI 字体不含汉字，必须用 CJK 字体光栅化
                for (int i = 0; i < MirageDebug.LOADING_CJK_SOURCE.length(); i++) {
                    char ch = MirageDebug.LOADING_CJK_SOURCE.charAt(i);
                    if (ch <= 126) continue;
                    int[] g = MirageDebug.rasterizeSmoothText(String.valueOf(ch), cf);
                    int[] store = new int[3 + (g.length - 2)];
                    store[0] = g[0];
                    store[1] = g[0];
                    store[2] = g[1];
                    System.arraycopy(g, 2, store, 3, g.length - 2);
                    GLYPH_CACHE.put(px + "\u0000" + ch, store);
                }
            }
            glyphReady = true;
            MirageLog.MIRAGE.debug("加载界面字形预渲染完成（与早期窗口同源 "
                    + LoadingFonts.ASCII_RESOURCE + "）：" + GLYPH_CACHE.size()
                    + " 个字符字形（GUI " + GUI_TEXT_PX + "px / " + (GUI_TEXT_PX - 1) + "px）");
        } catch (Throwable t) {
            textUnavailable = true;
            MirageLog.MIRAGE.warn("加载界面文字不可用，后续只画进度条", t);
        }
    }

    /**
     * 仅供离线回归脚本：字符级预渲染是同步的，调用 {@link #prewarm()} 后即已就绪，
     * 本方法恒返回 true。生产代码没有任何调用点。
     */
    public static boolean awaitIdle(long timeoutMs) {
        return glyphReady;
    }

    /**
     * 由 ASM 织入 {@code LoadingOverlay.extractRenderState()} 末尾调用。
     *
     * <p><b>本方法运行在渲染线程，禁止任何 AWT 调用</b>（见类注释）。它只查 {@link #GLYPH_CACHE}
     * 拼装字形、再用反射 fill 画矩形——字体管线永远在 {@link #prewarm()} 里跑过。
     *
     * @param overlay LoadingOverlay 实例（读淡入淡出状态与资源重载进度），可为 null
     * @param gge     GuiGraphicsExtractor 实例
     */
    public static void draw(Object overlay, Object gge) {
        if (gge == null) return;

        Method fill;
        int gw;
        int gh;
        try {
            fill = MirageDebug.fillMethod(gge);
            gw = MirageDebug.guiSize(gge, true);
            gh = MirageDebug.guiSize(gge, false);
        } catch (Throwable t) {
            warn("加载界面：取绘制上下文失败", t);
            return;
        }
        if (gw <= 0 || gh <= 0) return;
        canvasW = gw;
        canvasH = gh;

        float sx = gw / (float) ForgeStyleLayout.DESIGN_WIDTH;
        float sy = gh / (float) ForgeStyleLayout.DESIGN_HEIGHT;
        int alpha = overlayAlpha(overlay);
        // 记下遮罩「最后一次画出来」的不透明度：标题画面据此判断收尾要不要补一次溶解
        // （遮罩是满不透明消失的 = 硬切；已经被 MC 正常淡出到接近 0 的就不该再盖回去）。
        lastDrawnAlpha = alpha;
        overlayDrawn = true;
        lastDrawnView = view();

        // 每一块单独兜底：任何一块失败都不会连累其它块
        step("背景", () -> MirageDebug.fillRect(gge, fill, 0, 0, gw, gh,
                pack(alpha, ForgeStyleLayout.FORGE_RED)));
        step("内存条", () -> drawMemoryBar(gge, fill, sx, sy, alpha));
        step("内存文字", () -> drawMemoryText(gge, fill, sx, sy, alpha));
        if (OLD_STEPPER) {
            step("总进度条", () -> drawTotalProgress(gge, fill, sx, sy, alpha));
            step("阶段摘要", () -> drawStageSummary(gge, fill, sx, sy, alpha));
            step("阶段步骤条", () -> drawStageStepper(gge, fill, sx, sy, alpha));
            step("日志面板", () -> drawLogPanel(gge, fill, sx, sy, alpha));
        } else {
            drawDashboard(gge, fill, sx, sy, alpha, FRAME.getAndIncrement());
        }
        step("版本号", () -> drawVersion(gge, fill, sx, sy, alpha));
    }

    // ---- 标题画面收尾溶解（遮罩「满不透明消失」的兜底） ----

    /**
     * 标题画面出现时调用：如果加载遮罩是<b>满不透明</b>消失的（= 硬切），启动一次收尾溶解。
     *
     * <p>门槛 {@code lastDrawnAlpha >= 200}：MC 自己把遮罩正常淡出到接近 0 时不该再盖红屏，
     * 否则会反过来制造一次「Logo → 仪表盘」的跳变。
     */
    public static void beginExitDissolve() {
        if (!overlayDrawn) return;
        if (lastDrawnAlpha < 200) return;
        if (EXIT_DISSOLVE.begin(System.currentTimeMillis())) {
            MiragePerf.mark("ui.exit.dissolve.begin");
            MirageLog.MIRAGE.info("加载界面收尾：遮罩满不透明消失（alpha=" + lastDrawnAlpha
                    + "），标题画面补 600ms 交叉溶解");
        }
    }

    /**
     * 当前收尾溶解的剩余不透明度 0..255；0 表示「不要画」。
     * 顺便在归零那一刻打一次结束锚点。
     */
    public static int exitDissolveAlpha() {
        int a = EXIT_DISSOLVE.alpha255(System.currentTimeMillis());
        if (a == 0 && EXIT_DISSOLVE.started() && !exitDissolveLogged) {
            exitDissolveLogged = true;
            MiragePerf.mark("ui.exit.dissolve.done");
        }
        return a;
    }

    /**
     * 把仪表盘整体按 {@code alpha} 叠在屏幕上（标题画面的第一层）。
     *
     * <p>与 {@link #draw} 的区别：不读 MC 遮罩状态、不吃 {@code overlay} 对象，
     * 只按给定 alpha 画同一套内容，所以和遮罩最后一帧逐像素一致，肉眼看不到接缝。
     */
    public static void drawExitFrame(Object gge, int alpha) {
        if (gge == null || alpha <= 0) return;
        Method fill;
        int gw;
        int gh;
        try {
            fill = MirageDebug.fillMethod(gge);
            gw = MirageDebug.guiSize(gge, true);
            gh = MirageDebug.guiSize(gge, false);
        } catch (Throwable t) {
            return;
        }
        if (gw <= 0 || gh <= 0) return;

        final float sx = gw / (float) ForgeStyleLayout.DESIGN_WIDTH;
        final float sy = gh / (float) ForgeStyleLayout.DESIGN_HEIGHT;
        final int a = Math.min(255, Math.max(0, alpha));
        LoadProgress.View snapshot = lastDrawnView;
        final LoadProgress.View v = snapshot != null ? snapshot : view();
        final int frame = FRAME.getAndIncrement();

        step("溶解背景", () -> MirageDebug.fillRect(gge, fill, 0, 0, gw, gh,
                pack(a, ForgeStyleLayout.FORGE_RED)));
        step("溶解分段总进度", () -> drawSegmentedTotal(gge, fill, sx, sy, a, v, frame));
        step("溶解面包屑", () -> drawBreadcrumb(gge, fill, sx, sy, a, v));
        step("溶解独立进度条", () -> drawMeters(gge, fill, sx, sy, a, v, frame));
        step("溶解计数行", () -> drawCounters(gge, fill, sx, sy, a, v));
        step("溶解事件流", () -> drawEvents(gge, fill, sx, sy, a, v));
    }

    // ---- 真实进度仪表盘：分段总条 + 独立 meter + 计数 + 事件流 ----
    //
    // 数据全部来自 LoadProgress（meter 栈 / 真实计数器 / 事件环），这里只做「按设计坐标画矩形」。
    // 文本快照 200ms 一采；游标每帧动，所以 S4/S7 这种拿不到分母的黑盒区间屏幕上不会静止。

    private static void drawDashboard(Object gge, Method fill, float sx, float sy,
                                      int alpha, int frame) {
        LoadProgress.View v = view();
        step("分段总进度", () -> drawSegmentedTotal(gge, fill, sx, sy, alpha, v, frame));
        step("面包屑", () -> drawBreadcrumb(gge, fill, sx, sy, alpha, v));
        step("独立进度条", () -> drawMeters(gge, fill, sx, sy, alpha, v, frame));
        step("计数行", () -> drawCounters(gge, fill, sx, sy, alpha, v));
        step("事件流", () -> drawEvents(gge, fill, sx, sy, alpha, v));
    }

    private static LoadProgress.View view() {
        long now = System.currentTimeMillis();
        LoadProgress.View v = uiView;
        if (v == null || now - uiViewAt >= VIEW_SNAPSHOT_MS) {
            v = LoadProgress.view(ForgeStyleLayout.METER_COUNT, ForgeStyleLayout.EVENT_VISIBLE_LINES);
            uiView = v;
            uiViewAt = now;
        }
        return v;
    }

    /** 7 段等宽分段条：已完成段填绿、当前段填真实比例或不定游标、未开始段留空。 */
    private static void drawSegmentedTotal(Object gge, Method fill, float sx, float sy,
                                           int alpha, LoadProgress.View v, int frame) {
        int top = ForgeStyleLayout.TOTAL_BAR_TOP;
        int bottom = top + ForgeStyleLayout.TOTAL_BAR_HEIGHT;
        int x0 = gx(ForgeStyleLayout.barLeft(), sx);
        int x1 = gx(ForgeStyleLayout.barOuterRight(), sx);
        MirageDebug.fillRect(gge, fill, x0, gy(top, sy), x1, gy(bottom, sy),
                pack(alpha, ForgeStyleLayout.COLOUR_ACTIVE));
        MirageDebug.fillRect(gge, fill, gx(ForgeStyleLayout.barInnerLeft(), sx),
                gy(top + ForgeStyleLayout.INNER_INSET, sy),
                gx(ForgeStyleLayout.barInnerRight(), sx),
                gy(bottom - ForgeStyleLayout.INNER_INSET, sy),
                pack(alpha, ForgeStyleLayout.FORGE_RED));

        float[] segs = v.segments();
        int yTop = gy(top + ForgeStyleLayout.FILL_INSET, sy);
        int yBot = gy(bottom - ForgeStyleLayout.FILL_INSET, sy);
        int stage = LoadProgress.stageIndex();
        for (int i = 0; i < ForgeStyleLayout.STAGE_COUNT; i++) {
            int sx0 = gx(ForgeStyleLayout.segmentLeft(i) + 2, sx);
            int sx1 = gx(ForgeStyleLayout.segmentRight(i) - 2, sx);
            if (sx1 <= sx0) continue;
            float f = segs[i];
            int colour = i < stage ? ForgeStyleLayout.COLOUR_OK : ForgeStyleLayout.COLOUR_ACTIVE;
            if (f > 0f) {
                int right = Math.round(sx0 + (sx1 - sx0) * Math.min(1f, f));
                MirageDebug.fillRect(gge, fill, sx0, yTop, right, yBot, pack(alpha, colour));
            } else if (f < 0f) {
                int w = Math.round((sx1 - sx0) * LoadProgress.CURSOR_FRACTION);
                int left = Math.round(sx0 + (sx1 - sx0 - w) * (LoadProgress.cursorLeft(frame)
                        / (1f - LoadProgress.CURSOR_FRACTION)));
                MirageDebug.fillRect(gge, fill, left, yTop, left + w, yBot,
                        pack(alpha, ForgeStyleLayout.COLOUR_ACTIVE));
            }
            // 段间 1px 分隔（红底竖线），让「7 格」看得出来
            if (i > 0) {
                int sep = gx(ForgeStyleLayout.segmentLeft(i), sx);
                MirageDebug.fillRect(gge, fill, sep, gy(top + 1, sy),
                        Math.max(sep + 1, sep + Math.round(sx)), gy(bottom - 1, sy),
                        pack(alpha, ForgeStyleLayout.FORGE_RED));
            }
        }
        drawText(gge, fill, truncate(v.totalLabel(), gx(ForgeStyleLayout.BAR_WIDTH, sx), sx),
                gx(ForgeStyleLayout.barLeft(), sx), gy(ForgeStyleLayout.totalTextY(), sy), sx,
                pack(alpha, ForgeStyleLayout.COLOUR_ACTIVE));
    }

    private static void drawBreadcrumb(Object gge, Method fill, float sx, float sy,
                                       int alpha, LoadProgress.View v) {
        MirageDebug.fillRect(gge, fill, gx(ForgeStyleLayout.barLeft(), sx),
                gy(ForgeStyleLayout.DIVIDER_1_TOP, sy),
                gx(ForgeStyleLayout.barOuterRight(), sx),
                gy(ForgeStyleLayout.DIVIDER_1_TOP + 1, sy),
                pack(alpha, ForgeStyleLayout.COLOUR_LINE));
        drawText(gge, fill, v.breadcrumb(), gx(ForgeStyleLayout.barLeft(), sx),
                gy(ForgeStyleLayout.breadcrumbY(), sy), sx, pack(alpha, ForgeStyleLayout.COLOUR_DIM));
    }

    /** 最多 3 根彼此独立的 meter：确定条填真实比例，不定条画来回滑动的游标。 */
    private static void drawMeters(Object gge, Method fill, float sx, float sy,
                                   int alpha, LoadProgress.View v, int frame) {
        java.util.List<LoadProgress.Snapshot> meters = v.meters();
        for (int i = 0; i < ForgeStyleLayout.METER_COUNT; i++) {
            int top = ForgeStyleLayout.meterBarTop(i);
            int bottom = top + ForgeStyleLayout.METER_BAR_HEIGHT;
            int x0 = gx(ForgeStyleLayout.barLeft(), sx);
            int x1 = gx(ForgeStyleLayout.barOuterRight(), sx);
            // 空槽也画外框/内衬：屏幕上始终能看出「这里有几根独立条」
            MirageDebug.fillRect(gge, fill, x0, gy(top, sy), x1, gy(bottom, sy),
                    pack(alpha, ForgeStyleLayout.COLOUR_ACTIVE));
            MirageDebug.fillRect(gge, fill, gx(ForgeStyleLayout.barInnerLeft(), sx),
                    gy(top + ForgeStyleLayout.INNER_INSET, sy),
                    gx(ForgeStyleLayout.barInnerRight(), sx),
                    gy(bottom - ForgeStyleLayout.INNER_INSET, sy),
                    pack(alpha, ForgeStyleLayout.FORGE_RED));
            if (i >= meters.size()) continue;
            LoadProgress.Snapshot s = meters.get(i);
            int yTop = gy(top + ForgeStyleLayout.FILL_INSET, sy);
            int yBot = gy(bottom - ForgeStyleLayout.FILL_INSET, sy);
            if (s.indeterminate()) {
                int fx0 = gx(ForgeStyleLayout.barFillLeft(), sx);
                int w = Math.round(ForgeStyleLayout.BAR_WIDTH * LoadProgress.CURSOR_FRACTION * sx);
                int left = fx0 + Math.round(ForgeStyleLayout.BAR_WIDTH * LoadProgress.cursorLeft(frame) * sx);
                MirageDebug.fillRect(gge, fill, left, yTop, left + w, yBot,
                        pack(alpha, ForgeStyleLayout.COLOUR_ACTIVE));
            } else {
                int fx0 = gx(ForgeStyleLayout.barFillLeft(), sx);
                int fx1 = gx(ForgeStyleLayout.barFillRight(s.fraction()), sx);
                if (fx1 > fx0) {
                    MirageDebug.fillRect(gge, fill, fx0, yTop, fx1, yBot,
                            pack(alpha, ForgeStyleLayout.COLOUR_ACTIVE));
                }
            }
            String head = LoadProgress.meterHead(s);
            String tail = LoadProgress.meterTail(s);
            int labelY = gy(ForgeStyleLayout.meterLabelY(i), sy);
            drawText(gge, fill, truncate(head, gx(ForgeStyleLayout.BAR_WIDTH, sx) / 2, sx),
                    x0, labelY, sx, pack(alpha, ForgeStyleLayout.COLOUR_ACTIVE));
            drawText(gge, fill, tail, x1 - textWidth(tail, sx), labelY, sx,
                    pack(alpha, ForgeStyleLayout.COLOUR_OK));
        }
    }

    private static void drawCounters(Object gge, Method fill, float sx, float sy,
                                     int alpha, LoadProgress.View v) {
        MirageDebug.fillRect(gge, fill, gx(ForgeStyleLayout.barLeft(), sx),
                gy(ForgeStyleLayout.DIVIDER_2_TOP, sy),
                gx(ForgeStyleLayout.barOuterRight(), sx),
                gy(ForgeStyleLayout.DIVIDER_2_TOP + 1, sy),
                pack(alpha, ForgeStyleLayout.COLOUR_LINE));
        drawText(gge, fill, v.counter1(), gx(ForgeStyleLayout.barLeft(), sx),
                gy(ForgeStyleLayout.counterY(0), sy), sx, pack(alpha, ForgeStyleLayout.COLOUR_ACTIVE));
        drawText(gge, fill, v.counter2(), gx(ForgeStyleLayout.barLeft(), sx),
                gy(ForgeStyleLayout.counterY(1), sy), sx, pack(alpha, ForgeStyleLayout.COLOUR_DIM));
    }

    private static void drawEvents(Object gge, Method fill, float sx, float sy,
                                   int alpha, LoadProgress.View v) {
        int gwPx = Math.round(ForgeStyleLayout.DESIGN_WIDTH * sx);
        int ghPx = Math.round(ForgeStyleLayout.DESIGN_HEIGHT * sy);
        int margin = Math.round(8 * sx);
        MirageDebug.fillRect(gge, fill, margin, gy(ForgeStyleLayout.EVENT_PANEL_TOP, sy),
                gwPx - margin, ghPx, pack(alpha, ForgeStyleLayout.COLOUR_PANEL));
        drawText(gge, fill, "事件 · " + LoadProgress.EVENT_CAPACITY + " 缓冲",
                margin + Math.round(4 * sx), gy(ForgeStyleLayout.eventHeaderY(), sy), sx,
                pack(alpha, ForgeStyleLayout.COLOUR_DIM), ForgeStyleLayout.COLOUR_PANEL);
        java.util.List<String> lines = v.eventLines();
        int maxTextW = gwPx - 2 * margin - Math.round(8 * sx);
        for (int i = 0; i < ForgeStyleLayout.EVENT_VISIBLE_LINES && i < lines.size(); i++) {
            String raw = lines.get(i);
            drawText(gge, fill, truncate(raw, maxTextW, sx), margin + Math.round(4 * sx),
                    gy(ForgeStyleLayout.eventLineY(i), sy), sx,
                    pack(alpha, eventColor(raw)), ForgeStyleLayout.COLOUR_PANEL);
        }
    }

    /** 事件行按通道着色：一眼分辨阶段 / meter / 告警 / MC 原版。 */
    private static int eventColor(String line) {
        if (line.startsWith("[ERROR]")) return ForgeStyleLayout.COLOUR_FAIL;
        if (line.startsWith("[WARN]")) return ForgeStyleLayout.COLOUR_WARN;
        if (line.startsWith("[STAGE]")) return ForgeStyleLayout.COLOUR_OK;
        if (line.startsWith("[METER]")) return 0x88FFFF;
        return ForgeStyleLayout.COLOUR_DIM;
    }

    // ---- 顶部内存条 ----

    private static void drawMemoryBar(Object gge, Method fill, float sx, float sy, int alpha) {
        float ratio = memoryRatio();
        int barColour = hsvToArgb((1f - (float) Math.pow(ratio, 1.5d)) / 3f, 1f, 0.5f);

        int top = ForgeStyleLayout.MEMORY_BAR_TOP;

        // 外框（白）
        MirageDebug.fillRect(gge, fill,
                gx(ForgeStyleLayout.barLeft(), sx), gy(top, sy),
                gx(ForgeStyleLayout.barOuterRight(), sx), gy(top + ForgeStyleLayout.BAR_HEIGHT, sy),
                pack(alpha, ForgeStyleLayout.WHITE));
        // 内衬（红，即早期窗口里"透明内衬"透出的底色）
        MirageDebug.fillRect(gge, fill,
                gx(ForgeStyleLayout.barInnerLeft(), sx), gy(top + ForgeStyleLayout.INNER_INSET, sy),
                gx(ForgeStyleLayout.barInnerRight(), sx),
                gy(top + ForgeStyleLayout.BAR_HEIGHT - ForgeStyleLayout.INNER_INSET, sy),
                pack(alpha, ForgeStyleLayout.FORGE_RED));
        // 填充
        int fx1 = gx(ForgeStyleLayout.barFillRight(ratio), sx);
        int fx0 = gx(ForgeStyleLayout.barFillLeft(), sx);
        if (fx1 > fx0) {
            MirageDebug.fillRect(gge, fill, fx0, gy(top + ForgeStyleLayout.FILL_INSET, sy),
                    fx1, gy(top + ForgeStyleLayout.BAR_HEIGHT - ForgeStyleLayout.FILL_INSET, sy),
                    pack(alpha, barColour));
        }
    }

    private static void drawMemoryText(Object gge, Method fill, float sx, float sy, int alpha) {
        String text = perfLine();
        int w = textWidth(text, sx);
        int x = w > 0 ? (Math.round(ForgeStyleLayout.DESIGN_WIDTH * sx) - w) / 2 : 0;
        drawText(gge, fill, text, x, gy(ForgeStyleLayout.memoryTextY(), sy), sx,
                pack(alpha, ForgeStyleLayout.WHITE));
    }

    // ---- 总进度条（内存条下方，显示整体加载百分比） ----

    private static void drawTotalProgress(Object gge, Method fill, float sx, float sy, int alpha) {
        float p = clamp01(MirageDebug.progress / 100f);
        int top = ForgeStyleLayout.TOTAL_BAR_TOP;
        // 0%→红，100%→绿（与内存条反向：进度高=好）
        int r = (int) (255 * (1f - p));
        int g = (int) (255 * p);
        int barColour = (r << 16) | (g << 8) | 0x40;

        // 外框（白）
        MirageDebug.fillRect(gge, fill,
                gx(ForgeStyleLayout.barLeft(), sx), gy(top, sy),
                gx(ForgeStyleLayout.barOuterRight(), sx), gy(top + ForgeStyleLayout.TOTAL_BAR_HEIGHT, sy),
                pack(alpha, ForgeStyleLayout.WHITE));
        // 内衬（红）
        MirageDebug.fillRect(gge, fill,
                gx(ForgeStyleLayout.barInnerLeft(), sx), gy(top + ForgeStyleLayout.INNER_INSET, sy),
                gx(ForgeStyleLayout.barInnerRight(), sx),
                gy(top + ForgeStyleLayout.TOTAL_BAR_HEIGHT - ForgeStyleLayout.INNER_INSET, sy),
                pack(alpha, ForgeStyleLayout.FORGE_RED));
        // 填充
        int fx0 = gx(ForgeStyleLayout.barFillLeft(), sx);
        int fx1 = gx(ForgeStyleLayout.barFillRight(p), sx);
        if (fx1 > fx0) {
            MirageDebug.fillRect(gge, fill, fx0, gy(top + ForgeStyleLayout.FILL_INSET, sy),
                    fx1, gy(top + ForgeStyleLayout.TOTAL_BAR_HEIGHT - ForgeStyleLayout.FILL_INSET, sy),
                    pack(alpha, barColour));
        }
        // 标签
        String label = "总进度 " + MirageDebug.progress + "%";
        drawText(gge, fill, label, gx(ForgeStyleLayout.barLeft(), sx),
                gy(ForgeStyleLayout.totalTextY(), sy), sx, pack(alpha, ForgeStyleLayout.WHITE));
    }

    // ---- 当前阶段摘要（总进度条下方，概括当前底层子任务） ----

    private static void drawStageSummary(Object gge, Method fill, float sx, float sy, int alpha) {
        drawText(gge, fill, MirageDebug.stageSummary(),
                gx(ForgeStyleLayout.barLeft(), sx),
                gy(ForgeStyleLayout.stageSummaryY(), sy), sx,
                pack(alpha, ForgeStyleLayout.WHITE));
    }

    // ---- S1–S7 步骤条（中部，已完成打勾/进行中高亮/待处理灰显） ----

    private static void drawStageStepper(Object gge, Method fill, float sx, float sy, int alpha) {
        int[] states = MirageDebug.phaseStates();
        int listLeft = ForgeStyleLayout.barLeft();
        int sqPx = GUI_TEXT_PX; // 状态方块与文字等高，保证对齐
        for (int k = 0; k < states.length; k++) {
            int sqX = gx(listLeft, sx);
            int sqY = gy(ForgeStyleLayout.stageRowTextY(k), sy);
            int sqColor;
            int textColor;
            switch (states[k]) {
                case 2: // 已完成：绿底
                    sqColor = pack(alpha, 0x55FF55);
                    textColor = pack(alpha, 0xCFCFCF);
                    break;
                case 1: // 进行中：黄底高亮
                    sqColor = pack(alpha, 0xFFFF55);
                    textColor = pack(alpha, 0xFFFFFF);
                    break;
                default: // 待处理：暗灰
                    sqColor = pack(alpha, 0x555555);
                    textColor = pack(alpha, 0x888888);
                    break;
            }
            // 状态方块
            MirageDebug.fillRect(gge, fill, sqX, sqY, sqX + sqPx, sqY + sqPx, sqColor);
            // 阶段名（S1 发现Mod ...）
            String label = "S" + (k + 1) + " " + MirageDebug.PHASE_NAMES[k];
            int tx = sqX + sqPx + Math.max(4, Math.round(6 * sx));
            drawText(gge, fill, label, tx, sqY, sx, textColor);
        }
    }

    // ---- 底部滚动日志面板（占屏幕约 1/3，从下到上滚动，节选真实加载日志） ----

    private static void drawLogPanel(Object gge, Method fill, float sx, float sy, int alpha) {
        int gwPx = Math.round(ForgeStyleLayout.DESIGN_WIDTH * sx);
        int ghPx = Math.round(ForgeStyleLayout.DESIGN_HEIGHT * sy);
        int margin = Math.round(8 * sx);
        int panelTop = gy(ForgeStyleLayout.logPanelTop(), sy);

        // 面板背景（深底，压住红底，保证日志可读）
        MirageDebug.fillRect(gge, fill, margin, panelTop, gwPx - margin, ghPx,
                pack(alpha, 0x141414));

        // 面板标题
        drawText(gge, fill, "日志（实时）", margin + Math.round(4 * sx),
                panelTop + Math.round(4 * sy), sx, pack(alpha, 0xAAAAAA), 0x141414);

        // 可见行数（标题下方起，到底边止）
        int lineH = GUI_TEXT_PX + 4;
        int headerH = GUI_TEXT_PX + 8;
        int avail = (ghPx - panelTop) - headerH - 4;
        int maxLines = Math.max(1, avail / lineH);

        String[] lines = MirageDebug.loadingLogLines();
        int start = Math.max(0, lines.length - maxLines);
        int y = ghPx - margin - lineH; // 最底行（最新）的顶部
        int maxTextW = gwPx - 2 * margin - Math.round(8 * sx);
        for (int k = lines.length - 1; k >= start; k--) {
            String raw = lines[k];
            drawText(gge, fill, truncate(raw, maxTextW, sx), margin + Math.round(4 * sx), y, sx,
                    logPanelColor(raw, alpha), 0x141414);
            y -= lineH;
        }
    }

    /** 日志面板：按行内容着色（错误红 / 警告黄 / 其它灰）。 */
    private static int logPanelColor(String line, int alpha) {
        if (line.contains("ERROR") || line.contains("Exception")
                || line.contains("失败") || line.contains("错误")) {
            return pack(alpha, 0xFF7777);
        }
        if (line.contains("WARN") || line.contains("警告")) {
            return pack(alpha, 0xFFFF77);
        }
        return pack(alpha, 0xBBBBBB);
    }

    /** 超宽日志截断，末尾补 "..."（ASCII，无需额外字形）。 */
    private static String truncate(String s, int maxW, float sx) {
        if (s == null) return "";
        if (textWidth(s, sx) <= maxW) return s;
        String t = s;
        while (t.length() > 1 && textWidth(t + "...", sx) > maxW) {
            t = t.substring(0, t.length() - 1);
        }
        return t + "...";
    }

    // ---- 右下角版本号 ----

    private static void drawVersion(Object gge, Method fill, float sx, float sy, int alpha) {
        String label = MirageDebug.versionLabel;
        if (label == null || label.isEmpty()) return;
        int w = textWidth(label, sx);
        int x = Math.round(ForgeStyleLayout.DESIGN_WIDTH * sx) - w
                - Math.round(ForgeStyleLayout.VERSION_MARGIN * sx);
        drawText(gge, fill, label, x, gy(ForgeStyleLayout.versionTopRightY(), sy), sx,
                pack(alpha, ForgeStyleLayout.WHITE));
    }

    // ---- 坐标与绘制 ----

    private static int gx(float designX, float sx) {
        return Math.round(designX * sx);
    }

    private static int gy(float designY, float sy) {
        return Math.round(designY * sy);
    }

    /**
     * 光栅化字号：<b>在 GUI 空间里取固定值，绝不跟着设计空间缩放</b>。
     *
     * <p>GUI 空间（{@code gge.guiWidth()}）已经是缩放后尺寸，字号只需在 GUI 像素里取一个
     * 可读值即可——位图字体的可读性由「每个字形像素占一个 GUI 单位」保证，跟设计空间比例无关。
     * 实机日志曾显示 GUI 427×240 下按 854 缩放算出 7px，笔画细到断笔；固定 10px 后墨迹高约 8px，
     * 与 MC 自带字体观感一致。窗口特别窄（GUI 宽 &lt;340）时降到 9px 保可读性。
     */
    private static int textPixelSize(float sx) {
        int gw = Math.round(ForgeStyleLayout.DESIGN_WIDTH * sx);
        int px = gw < 340 ? GUI_TEXT_PX - 1 : GUI_TEXT_PX;
        return Math.max(MIN_TEXT_PX, Math.min(MAX_TEXT_PX, px));
    }

    /** 只在预渲染（{@link #prewarm()}）里调用：字体对象本身也要 AWT 初始化，不能出现在渲染线程。 */
    private static Font font(int px) {
        Font base = baseFont;
        if (base == null) {
            base = pickFont(px);
            baseFont = base;
        }
        return base.getSize() == px ? base : base.deriveFont((float) px);
    }

    /** CJK 字体（独立于等宽 ASCII 字体）：Consolas/DejaVu 等不含汉字，必须用系统中文字体光栅化。 */
    private static volatile Font cjkBaseFont;

    private static Font cjkFont(int px) {
        Font base = cjkBaseFont;
        if (base == null) {
            base = pickCjkFont(px);
            cjkBaseFont = base;
        }
        return base.getSize() == px ? base : base.deriveFont((float) px);
    }

    /**
     * 取中文字体：与早期窗口 1:1 同源，先按 {@link LoadingFonts#findCjkFont()} 的绝对路径
     * 用 {@code Font.createFont} 加载<b>同一个字体文件</b>（TTC 取第 0 个，与 STB 的
     * {@code cjkFontIndex=0} 一致）；文件不可用才回退系统家族名。
     */
    private static Font pickCjkFont(int px) {
        String path = LoadingFonts.findCjkFont();
        if (path != null) {
            Font fromFile = fontFromFile(path, px);
            if (fromFile != null) return fromFile;
        }
        String[] cands = {"Microsoft YaHei", "SimSun", "SimHei", "PingFang SC",
                "Noto Sans CJK SC", "Source Han Sans SC", "SansSerif"};
        try {
            Set<String> available = new HashSet<>();
            for (String family : GraphicsEnvironment.getLocalGraphicsEnvironment()
                    .getAvailableFontFamilyNames(Locale.ROOT)) {
                available.add(family);
            }
            for (String family : cands) {
                if (available.contains(family)) {
                    return new Font(family, Font.PLAIN, px);
                }
            }
        } catch (Throwable ignored) {
            // 拿不到字体列表就走 SANS_SERIF 兜底
        }
        return new Font(Font.SANS_SERIF, Font.PLAIN, px);
    }

    /**
     * 只在 {@link #prewarm()}（主线程）里调用：从 classpath 资源加载字体。
     * 生产环境这份资源在 fat jar 根；离线回归把同一份文件放在 classes 根。
     */
    private static Font fontFromResource(String resource, int px) {
        try {
            java.io.InputStream in = ForgeStyleOverlay.class.getResourceAsStream(resource);
            if (in == null) {
                String bare = resource.startsWith("/") ? resource.substring(1) : resource;
                in = ClassLoader.getSystemResourceAsStream(bare);
            }
            if (in == null) return null;
            try (java.io.InputStream stream = in) {
                return Font.createFont(Font.TRUETYPE_FONT, stream).deriveFont((float) px);
            }
        } catch (Throwable t) {
            MirageLog.MIRAGE.warn("加载界面内置字体不可用，回退系统字体: " + resource, t);
            return null;
        }
    }

    /** 只在 {@link #prewarm()}（主线程）里调用：从绝对路径加载中文字体文件。 */
    private static Font fontFromFile(String path, int px) {
        try {
            return Font.createFont(Font.TRUETYPE_FONT, new java.io.File(path))
                    .deriveFont((float) px);
        } catch (Throwable t) {
            MirageLog.MIRAGE.warn("加载界面中文字体不可用，回退系统家族名: " + path, t);
            return null;
        }
    }

    /**
     * 按需光栅化单个字符到 {@link #GLYPH_CACHE}（含汉字）。供 {@link io.mirage.loader.MirageDebug#ensureRasterized}
     * 在日志出现新汉字时调用；渲染线程不会走到这里，故可安全做 AWT 光栅化。
     */
    public static void rasterizeChar(char ch) {
        if (textUnavailable) return;
        int[] pxList = {GUI_TEXT_PX, GUI_TEXT_PX - 1};
        for (int px : pxList) {
            String key = px + "\u0000" + ch;
            if (GLYPH_CACHE.containsKey(key)) continue;
            Font f = (ch <= 126) ? font(px) : cjkFont(px);
            int[] g = MirageDebug.rasterizeSmoothText(String.valueOf(ch), f);
            int[] store = new int[3 + (g.length - 2)];
            store[0] = g[0];
            store[1] = g[0];
            store[2] = g[1];
            System.arraycopy(g, 2, store, 3, g.length - 2);
            GLYPH_CACHE.put(key, store);
        }
    }

    /**
     * 取像素掩码（行）：<b>永不阻塞、永不触碰 AWT</b>。
     *
     * <p>直接查 {@link #GLYPH_CACHE} 把每个字符的字形矩形按等宽步进拼装成整行；
     * 缓存未就绪或某字符缺失时跳过该字符（缺字按 advance 留空位），绝不回退到光栅化。
     * 因为预渲染在 {@link #prewarm()} 里已完成，渲染线程拿到的总是现成字形。
     */
    private static int[] mask(String text, float sx) {
        if (text == null || text.isEmpty() || textUnavailable || !glyphReady) return null;
        int px = textPixelSize(sx);
        Integer adv = ADVANCE.get(px);
        if (adv == null) return null;
        int cursor = 0;
        int maxH = 0;
        List<int[]> rects = new ArrayList<>();
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == ' ') {
                cursor += adv;
                continue;
            }
            int[] g = GLYPH_CACHE.get(px + "\u0000" + ch);
            if (g == null) {
                cursor += adv;
                continue;
            }
            int advance = g[0];
            int glyphH = g[2];
            for (int k = 3; k + 4 < g.length; k += 5) {
                rects.add(new int[]{cursor + g[k], g[k + 1], g[k + 2], g[k + 3], g[k + 4]});
            }
            cursor += advance;
            if (glyphH > maxH) maxH = glyphH;
        }
        // out = [总宽, 最大高, (x,y,w,h,coverage)×n]
        int[] out = new int[2 + rects.size() * 5];
        out[0] = cursor;
        out[1] = maxH;
        int o = 2;
        for (int[] r : rects) {
            out[o++] = r[0];
            out[o++] = r[1];
            out[o++] = r[2];
            out[o++] = r[3];
            out[o++] = r[4];
        }
        return out;
    }

    private static int textWidth(String text, float sx) {
        int[] mask = mask(text, sx);
        return mask == null ? 0 : Math.max(0, mask[0]);
    }

    /** 用 fill 逐块画一行抗锯齿字：mask = [宽, 高, (x, y, w, h, coverage)×n]。 */
    private static void drawText(Object gge, Method fill, String text, int x, int y,
                                 float sx, int colour) {
        drawText(gge, fill, text, x, y, sx, colour, ForgeStyleLayout.FORGE_RED);
    }

    /**
     * @param backdrop 这一行所在的底色（红底 / 深色事件面板）。低覆盖率的前景必须先与它预混，
     *                 否则「半透明 fill 是否被 MC 混合」是个未知量，字会退回硬边点阵。
     */
    private static void drawText(Object gge, Method fill, String text, int x, int y,
                                 float sx, int colour, int backdrop) {
        int[] mask = mask(text, sx);
        if (mask == null) return;
        if (!textDrawnLogged) {
            textDrawnLogged = true;
            MirageLog.MIRAGE.info("加载界面文字已出图：AWT 抗锯齿灰度字（同源 "
                    + LoadingFonts.ASCII_RESOURCE + " + 字符级预渲染 + 按背景预混 + fill 逐块），"
                    + "字号 " + textPixelSize(sx) + "px，字形缓存 " + GLYPH_CACHE.size() + " 个");
        }
        int alpha = (colour >>> 24) & 0xFF;
        int fg = colour & 0xFFFFFF;
        for (int i = 2; i + 4 < mask.length; i += 5) {
            int rx = mask[i];
            int ry = mask[i + 1];
            int rw = mask[i + 2];
            int rh = mask[i + 3];
            int coverage = mask[i + 4];
            int rgb = coverage >= 4 ? fg : blend(backdrop, fg, coverage);
            int px0 = x + rx;
            int py0 = y + ry;
            int px1 = px0 + rw;
            int py1 = py0 + rh;
            // 抗锯齿字形比二值点阵高 1~2px，guiScale>1 时事件流末行会顶出画布底边 1px。
            // 按当前帧画布裁剪即可；不要为此改布局常量（断言会跟着常量移动，成为假绿）。
            if (px0 >= canvasW || py0 >= canvasH || px1 <= 0 || py1 <= 0) continue;
            if (px0 < 0) px0 = 0;
            if (py0 < 0) py0 = 0;
            if (px1 > canvasW) px1 = canvasW;
            if (py1 > canvasH) py1 = canvasH;
            MirageDebug.fillRect(gge, fill, px0, py0, px1, py1, pack(alpha, rgb));
        }
    }

    /** 前景按覆盖率档位烘进背景色：1..3 档 → 32/96/160 / 255，4 档直接用前景色。 */
    private static int blend(int backdrop, int fg, int coverage) {
        int t = coverage * 64 - 32;
        int br = (backdrop >>> 16) & 0xFF;
        int bg = (backdrop >>> 8) & 0xFF;
        int bb = backdrop & 0xFF;
        int fr = (fg >>> 16) & 0xFF;
        int fgc = (fg >>> 8) & 0xFF;
        int fb = fg & 0xFF;
        int r = br + (fr - br) * t / 255;
        int g = bg + (fgc - bg) * t / 255;
        int b = bb + (fb - bb) * t / 255;
        return (r << 16) | (g << 8) | b;
    }

    /**
     * 与早期窗口 1:1 同源：ASCII 直接读 {@link LoadingFonts#ASCII_RESOURCE}
     * （{@code EarlyFont} 用 STB 打包的同一份 {@code Monocraft.ttf}）。
     * 抗锯齿仍由 {@link MirageDebug#rasterizeSmoothText} 负责，所以字形同源但不退回二值点阵。
     * 资源缺失时回退系统等宽字体，保证界面仍出字。
     */
    private static Font pickFont(int px) {
        Font embedded = fontFromResource(LoadingFonts.ASCII_RESOURCE, px);
        if (embedded != null) return embedded;
        String[] fallback = {"Consolas", "Cascadia Mono", "Lucida Console", "DejaVu Sans Mono"};
        try {
            Set<String> available = new HashSet<>();
            for (String family : GraphicsEnvironment.getLocalGraphicsEnvironment()
                    .getAvailableFontFamilyNames(Locale.ROOT)) {
                available.add(family);
            }
            for (String family : fallback) {
                if (available.contains(family)) {
                    return new Font(family, Font.PLAIN, px);
                }
            }
        } catch (Throwable ignored) {
            // 拿不到字体列表就走 MONOSPACED 兜底
        }
        return new Font(Font.MONOSPACED, Font.PLAIN, px);
    }

    /** 每块绘制的兜底：单个失败只丢自己，不影响其它块。 */
    private interface Step {
        void run();
    }

    private static void step(String what, Step step) {
        try {
            step.run();
        } catch (Throwable t) {
            if ((DIAG.incrementAndGet() & 255) == 0) {
                MirageLog.MIRAGE.warn("绘制加载界面「" + what + "」失败", t);
            }
        }
    }

    private static void warn(String msg, Throwable t) {
        if ((DIAG.incrementAndGet() & 255) == 0) {
            MirageLog.MIRAGE.warn(msg, t);
        }
    }

    // ---- 淡入淡出：与原版 brand background 的 alpha 同步 ----

    private static int overlayAlpha(Object overlay) {
        if (overlay == null) return 255;
        try {
            long now = System.currentTimeMillis();
            long fadeOutStart = longField(overlay, "fadeOutStart");
            if (fadeOutStart > 0) {
                float fadeOut = (now - fadeOutStart) / 1000f;
                if (fadeOut >= 1f) {
                    float f = 1f - clamp01(fadeOut - 1f);
                    return (int) Math.ceil(f * 255f);
                }
                return 255;
            }
            if (boolField(overlay, "fadeIn")) {
                long fadeInStart = longField(overlay, "fadeInStart");
                if (fadeInStart > 0) {
                    float t = (now - fadeInStart) / 1000f;
                    return (int) Math.ceil(clamp(t, 0.15f, 1f) * 255f);
                }
            }
        } catch (Throwable ignored) {
            // 读不到字段就按不透明处理
        }
        return 255;
    }

    private static Field field(Object target, String name, Field cached) throws NoSuchFieldException {
        if (cached != null) return cached;
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }

    private static long longField(Object target, String name) throws Exception {
        Field f = switch (name) {
            case "fadeOutStart" -> fadeOutStartField = field(target, name, fadeOutStartField);
            case "fadeInStart" -> fadeInStartField = field(target, name, fadeInStartField);
            default -> field(target, name, null);
        };
        return f.getLong(target);
    }

    private static boolean boolField(Object target, String name) throws Exception {
        Field f = "fadeIn".equals(name)
                ? (fadeInField = field(target, name, fadeInField))
                : field(target, name, null);
        return f.getBoolean(target);
    }

    // ---- 性能信息（与早期窗口同源同格式） ----

    /** 动态文本快照：250 ms 一次，避免每帧都重拼装同一串（虽已很轻）。 */
    private static String perfLine() {
        long now = System.currentTimeMillis();
        String s = perfLine;
        if (s.isEmpty() || now - perfLineAt >= TEXT_SNAPSHOT_MS) {
            s = memoryText() + "  " + cpuText();
            perfLine = s;
            perfLineAt = now;
        }
        return s;
    }

    private static float memoryRatio() {
        try {
            MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
            long max = heap.getMax();
            if (max <= 0 || max == Long.MAX_VALUE) return 0f;
            return clamp01((float) ((double) heap.getUsed() / max));
        } catch (Throwable t) {
            return 0f;
        }
    }

    private static String memoryText() {
        try {
            MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
            long usedMb = heap.getUsed() / (1024L * 1024L);
            long maxMb = heap.getMax() > 0 && heap.getMax() != Long.MAX_VALUE
                    ? heap.getMax() / (1024L * 1024L) : 0;
            float pct = maxMb > 0 ? usedMb * 100f / maxMb : 0f;
            return String.format(Locale.ROOT, "Memory: %d/%dMB (%.1f%%)", usedMb, maxMb, pct);
        } catch (Throwable t) {
            return "Memory: 0/0MB (0.0%)";
        }
    }

    private static String cpuText() {
        try {
            com.sun.management.OperatingSystemMXBean os =
                    (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
            double load = os.getProcessCpuLoad();
            if (load < 0) {
                load = os.getCpuLoad();
                return String.format(Locale.ROOT, "*CPU: %.1f%%", Math.max(load, 0) * 100);
            }
            return String.format(Locale.ROOT, "CPU: %.1f%%", load * 100);
        } catch (Throwable t) {
            return "CPU: 0.0%";
        }
    }

    /** ARGB 打包：alpha 与 RGB 分开给，避免把 alpha 混进颜色里。 */
    private static int pack(int alpha, int rgb) {
        return ((alpha & 0xFF) << 24) | (rgb & 0xFFFFFF);
    }

    /** 与早期窗口同一套 HSV→RGB（Forge {@code ColourScheme#packedint} 的分量算法）。 */
    private static int hsvToArgb(float hue, float saturation, float value) {
        int h = (int) (hue * 6) % 6;
        float f = hue * 6 - h;
        float p = value * (1 - saturation);
        float q = value * (1 - f * saturation);
        float t = value * (1 - (1 - f) * saturation);
        float r;
        float g;
        float b;
        switch (h) {
            case 0 -> { r = value; g = t; b = p; }
            case 1 -> { r = q; g = value; b = p; }
            case 2 -> { r = p; g = value; b = t; }
            case 3 -> { r = p; g = q; b = value; }
            case 4 -> { r = t; g = p; b = value; }
            default -> { r = value; g = p; b = q; }
        }
        return 0xFF000000
                | ((int) (r * 255) << 16)
                | ((int) (g * 255) << 8)
                | (int) (b * 255);
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    private static float clamp(float v, float min, float max) {
        return v < min ? min : (v > max ? max : v);
    }
}
