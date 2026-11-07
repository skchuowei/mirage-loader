package io.mirage.earlydisplay;

import io.mirage.api.log.MirageLog;

import com.sun.management.OperatingSystemMXBean;
import io.mirage.loader.MirageDebug;
import io.mirage.loader.ForgeStyleLayout;

import org.lwjgl.BufferUtils;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWVidMode;
import org.lwjgl.opengl.GL;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import static org.lwjgl.opengl.GL32C.*;
import static org.lwjgl.system.MemoryUtil.NULL;

/**
 * Forge 式早期窗口：在 Minecraft 自己的窗口创建前，先建一个 GLFW + OpenGL
 * 窗口，1:1 复刻 NeoForge 的早期加载界面（红底、内存条、两条进度条、
 * 右下角版本号）。等 MC {@code Window.createGlfwWindow} 被调用时，
 * 停掉渲染线程，把同一个 window handle 交接给 MC。
 *
 * <p>按用户要求：右下角的铁砧动画（forge_anvil.png）已删除，其余元素保持不变。
 * 原生 MC 窗口里的加载界面由 {@code io.mirage.loader.ForgeStyleOverlay} 用同一套
 * 布局绘制，两处观感一致。
 */
public final class MirageEarlyWindow {

    private static final int DESIGN_WIDTH = 854;
    private static final int DESIGN_HEIGHT = 480;
    private static final int BAR_WIDTH = 400;
    private static final int BAR_HEIGHT = 20;

    private static final int COLOUR_WHITE = 0xFFFFFFFF;
    private static final int COLOUR_BACKGROUND = 0xFF3D32EF; // r=239 g=50 b=61

    private static final Object START_LOCK = new Object();
    private static volatile MirageEarlyWindow instance;

    /** 回滚开关：与主窗口同值，{@code -Dmirage.ui.oldStepper=true} 恢复 S1–S7 步骤条旧界面。 */
    private static final boolean OLD_STEPPER = Boolean.getBoolean("mirage.ui.oldStepper");
    /** 仪表盘文本快照间隔（秒表 0.1s 一变，200ms 一采足够）。 */
    private static final long VIEW_SNAPSHOT_MS = 200L;

    private final Semaphore renderLock = new Semaphore(1);
    private final IntBuffer fbWidth = BufferUtils.createIntBuffer(1);
    private final IntBuffer fbHeight = BufferUtils.createIntBuffer(1);

    private long window;
    private int winWidth;
    private int winHeight;
    private String versionLabel = "";

    private EarlyGl gl;
    private EarlyFont font;

    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> renderTask;

    private volatile boolean handedOff;
    private volatile boolean crashed;
    /** 渲染线程是否已绑定 GLCapabilities（LWJGL 的能力集是 ThreadLocal，见 renderTick）。 */
    private boolean renderThreadCaps;
    private int frame;
    private int refreshCounter;

    private String memoryText = "Memory: 0/0MB (0.0%)";
    private String cpuText = "CPU: 0.0%";
    private volatile io.mirage.loader.LoadProgress.View uiView;
    private volatile long uiViewAt;

    private MirageEarlyWindow() {
    }

    /**
     * 在 Launcher 最早阶段调用：创建并显示早期窗口。
     *
     * @param mcVersion     MC 版本（标题与右下角版本号）
     * @param loaderVersion Mirage 版本
     * @param width         窗口宽（设计分辨率仍为 854x480，按比例拉伸）
     * @param height        窗口高
     */
    public static void start(String mcVersion, String loaderVersion, int width, int height) {
        synchronized (START_LOCK) {
            if (instance != null) return;
            // 必须先于任何 GLFW 调用：决定两份 GLFW 类绑定到同一个 native 模块。
            shareLwjglNatives();
            MirageEarlyWindow w = new MirageEarlyWindow();
            w.versionLabel = mcVersion + "-" + loaderVersion;
            // 原生 MC 窗口里的 Forge 式加载界面要显示同一串版本号
            MirageDebug.versionLabel = w.versionLabel;
            if (!w.initWindow(mcVersion, width, height)) {
                MirageLog.MIRAGE.warn("早期窗口启动失败，回退到原版窗口创建");
                return;
            }
            instance = w;
            w.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "Mirage-EarlyDisplay");
                t.setDaemon(true);
                return t;
            });
            w.renderTask = w.scheduler.scheduleAtFixedRate(w::renderTick, 50L, 50L, TimeUnit.MILLISECONDS);
            MirageLog.MIRAGE.info("早期窗口已显示（渲染线程 20fps）");
        }
    }

    /**
     * 替换 {@code GLFW.glfwCreateWindow}：尽量把早期窗口交接给 Minecraft；
     * 未启动/已交接/出错时回退到原版创建。
     */
    public static long createOrBorrow(int width, int height, CharSequence title,
                                      long monitor, long share) {
        MirageEarlyWindow w = instance;
        if (w == null || w.crashed || w.window == NULL || w.handedOff) {
            return GLFW.glfwCreateWindow(width, height, title, monitor, share);
        }
        return w.handoff(width, height, title, monitor, share);
    }

    /**
     * 由「创建方 GLFW 实例」销毁窗口（供 MC 的 {@code Window.close()} 织入调用）。
     *
     * <p>同一个进程里加载了两份 glfw.dll：Launcher/早期窗口用的
     * {@code natives\lwjgl\glfw.dll}，与 MC 自己绑定的
     * {@code natives\lwjgl\3.4.1-snapshot\x64\glfw.dll}。两份各自持有独立的
     * {@code _glfw.windowListHead}。早期窗口是在前者的链表里登记的，若退出时由后者执行
     * {@code glfwDestroyWindow}，GLFW 摘链表时会把空 head 当节点遍历 →
     * {@code glfw.dll+0x77a0 mov rax,[rcx]} 空指针 → 原生崩溃（点「退出游戏」必崩）。
     *
     * <p>因此这里始终用创建时那套 GLFW 实例来销毁；非早期窗口句柄走原版销毁。
     */
    public static void destroyWindow(long handle) {
        if (handle == NULL) return;
        MirageEarlyWindow w = instance;
        if (w != null && handle == w.window) {
            // 先置空：重复关闭（含 Minecraft.close 的异常清理路径）不会二次销毁
            w.window = NULL;
            w.handedOff = true;
            try {
                GLFW.glfwDestroyWindow(handle);
                MirageLog.MIRAGE.info("早期窗口由创建方 GLFW 实例销毁 (handle=0x"
                        + Long.toHexString(handle) + ")");
            } catch (Throwable t) {
                MirageLog.MIRAGE.warn("早期窗口销毁失败（退出时可能残留窗口句柄）", t);
            }
            return;
        }
        GLFW.glfwDestroyWindow(handle);
    }

    /**
     * 让早期窗口与 Minecraft 共用同一份 lwjgl native。
     *
     * <p>Windows 的 LoadLibrary 按<b>完整路径</b>区分模块：同一个 glfw.dll 放在两个目录里
     * 会被加载成两个模块，各自持有一份 {@code _glfw} 全局状态
     * （{@code windowListHead}、{@code disabledCursorWindow} …）。
     * 早期窗口在 Launcher 侧创建窗口，而 Minecraft 是在自己的 classloader 里
     * 加载另一份 GLFW 类去 grab 光标：{@code GLFW_CURSOR_DISABLED} 只写进
     * 「执行这次调用的那份模块」的 {@code disabledCursorWindow}；真正处理
     * {@code WM_MOUSEMOVE} 的 WndProc 属于创建窗口的那份模块，它看到
     * {@code disabledCursorWindow == NULL} 就把事件丢掉。
     *
     * <p>症状因此非常迷惑：光标被隐藏（SetInputMode 里隐藏光标的代码生效）、
     * 键盘和鼠标按键都正常（{@code WM_KEYDOWN} / {@code WM_LBUTTONDOWN} 不查这个标记）、
     * 但<b>视角怎么都转不动</b>（cursor pos 回调一次都不派发）。
     *
     * <p>把 {@code org.lwjgl.librarypath} 统一指向同一个 native 目录后，
     * 两份 GLFW 类最终绑定到同一个模块，全局状态共享，输入恢复正常。
     */
    private static void shareLwjglNatives() {
        String existing = System.getProperty("org.lwjgl.librarypath");
        if (existing != null && new File(existing, "glfw.dll").isFile()) return;

        String libPath = System.getProperty("java.library.path", "");
        for (String entry : libPath.split(File.pathSeparator)) {
            if (entry.isEmpty()) continue;
            File nativesRoot = new File(entry).getParentFile();
            if (nativesRoot == null) continue;
            File lwjglDir = new File(nativesRoot, "lwjgl");
            if (!lwjglDir.isDirectory()) continue;
            File best = richestNativeDir(lwjglDir);
            if (best != null) {
                System.setProperty("org.lwjgl.librarypath", best.getAbsolutePath());
                MirageLog.MIRAGE.info("lwjgl native 统一路径 -> " + best.getAbsolutePath()
                        + "（与 Minecraft 共用同一份 glfw.dll）");
                return;
            }
        }
        MirageLog.MIRAGE.warn("未找到完整的 lwjgl native 目录；早期窗口与 MC 可能各加载一份 "
                + "glfw.dll，鼠标视角可能失效");
    }

    /** 在 {@code lwjgl/<version>/x64} 里挑 dll 最全的那个目录（必须含 glfw.dll）。 */
    private static File richestNativeDir(File lwjglDir) {
        File best = null;
        int bestCount = 0;
        File[] subs = lwjglDir.listFiles();
        if (subs == null) return null;
        for (File sub : subs) {
            File x64 = new File(sub, "x64");
            if (!new File(x64, "glfw.dll").isFile()) continue;
            File[] dlls = x64.listFiles((d, n) -> n.toLowerCase(Locale.ROOT).endsWith(".dll"));
            int count = dlls == null ? 0 : dlls.length;
            if (count > bestCount) {
                bestCount = count;
                best = x64;
            }
        }
        return best;
    }

    private boolean initWindow(String mcVersion, int width, int height) {
        this.winWidth = width > 0 ? width : DESIGN_WIDTH;
        this.winHeight = height > 0 ? height : DESIGN_HEIGHT;

        if (!GLFW.glfwInit()) {
            MirageLog.MIRAGE.error("glfwInit 失败，早期窗口不可用（不影响游戏本体启动）");
            return false;
        }
        String title = "Minecraft Mirage* " + mcVersion;

        int[][] versions = {{4, 6}, {4, 5}, {4, 4}, {4, 3}, {4, 2}, {4, 1}, {4, 0}, {3, 3}};
        for (int[] v : versions) {
            GLFW.glfwDefaultWindowHints();
            GLFW.glfwWindowHint(GLFW.GLFW_CLIENT_API, GLFW.GLFW_OPENGL_API);
            GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_CREATION_API, GLFW.GLFW_NATIVE_CONTEXT_API);
            GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, v[0]);
            GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, v[1]);
            GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);
            GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_FORWARD_COMPAT, GLFW.GLFW_TRUE);
            GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
            window = GLFW.glfwCreateWindow(winWidth, winHeight, title, NULL, NULL);
            if (window != NULL) break;
        }
        if (window == NULL) {
            MirageLog.MIRAGE.error("无法创建早期 GLFW 窗口（OpenGL 上下文不可用），回退到原版窗口");
            return false;
        }

        GLFWVidMode mode = GLFW.glfwGetVideoMode(GLFW.glfwGetPrimaryMonitor());
        if (mode != null) {
            GLFW.glfwSetWindowPos(window,
                    (mode.width() - winWidth) / 2, (mode.height() - winHeight) / 2);
        }

        GLFW.glfwMakeContextCurrent(window);
        GLFW.glfwSwapInterval(1);
        GL.createCapabilities();
        MirageLog.MIRAGE.info("早期窗口 OpenGL: "
                + glGetString(GL_VERSION) + " / " + glGetString(GL_RENDERER));

        gl = new EarlyGl();
        gl.init();
        // 汉字全集 = UI 用字（两窗口共用）+ 日志用字（日志面板要显示真实中文日志）
        // 字体源与主窗口共用同一份常量/查找逻辑（LoadingFonts），保证两窗口 1:1 同源。
        font = new EarlyFont(io.mirage.loader.LoadingFonts.ASCII_RESOURCE, 0,
                io.mirage.loader.LoadingFonts.findCjkFont(),
                MirageDebug.LOADING_CJK_SOURCE + MirageDebug.LOADING_CJK_LOG_SOURCE);

        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        updatePerformance();

        GLFW.glfwMakeContextCurrent(NULL);
        GLFW.glfwShowWindow(window);
        GLFW.glfwPollEvents();
        return true;
    }

    private void renderTick() {
        if (handedOff || crashed || window == NULL) return;
        if (!renderLock.tryAcquire()) return;
        try {
            GLFW.glfwMakeContextCurrent(window);
            // LWJGL 的 GLCapabilities 存在 GL.capabilitiesTLS（ThreadLocal）里，并且 JNI 侧
            // 是按"当前线程"取函数指针表的。initWindow 里的 createCapabilities() 只作用于
            // 主线程；渲染线程不自己绑一次的话，本线程第一条 GL 调用（glViewport）就会让
            // 原生侧判定 "No context is current" 并 FatalError 直接 abort 整个 JVM
            // （现象：窗口刚显示出来、日志走到一半，游戏进程无声退出）。
            if (!renderThreadCaps) {
                GL.createCapabilities();
                renderThreadCaps = true;
                MirageLog.MIRAGE.debug("早期窗口渲染线程已绑定 GL 能力: "
                        + GL.getCapabilities().OpenGL33);
            }
            GLFW.glfwPollEvents();
            GLFW.glfwGetFramebufferSize(window, fbWidth, fbHeight);
            int w = fbWidth.get(0);
            int h = fbHeight.get(0);
            if (w > 0 && h > 0) {
                renderFrame(w, h);
                GLFW.glfwSwapBuffers(window);
                frame++;
                if (++refreshCounter >= 10) {
                    refreshCounter = 0;
                    updatePerformance();
                }
            }
        } catch (Throwable t) {
            crashed = true;
            MirageLog.MIRAGE.warn("早期窗口渲染异常，后续回退到原版窗口", t);
        } finally {
            GLFW.glfwMakeContextCurrent(NULL);
            renderLock.release();
        }
    }

    private void renderFrame(int fbW, int fbH) {
        float sx = (float) fbW / DESIGN_WIDTH;
        float sy = (float) fbH / DESIGN_HEIGHT;

        glViewport(0, 0, fbW, fbH);
        glClearColor(239f / 255f, 50f / 255f, 61f / 255f, 1f);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        glDisable(GL_DEPTH_TEST);

        gl.activate();
        gl.screenSize(fbW, fbH);

        drawMemoryBar(sx, sy);
        drawMemoryText(sx, sy);
        if (OLD_STEPPER) {
            drawTotalProgress(sx, sy);
            drawStageSummary(sx, sy);
            drawStageStepper(sx, sy);
            drawLogPanel(sx, sy);
        } else {
            io.mirage.loader.LoadProgress.View v = dashboardView();
            drawSegmentedTotal(sx, sy, v);
            drawBreadcrumb(sx, sy, v);
            drawMeters(sx, sy, v);
            drawCounters(sx, sy, v);
            drawEvents(sx, sy, v);
        }
        drawVersion(sx, sy);
    }

    // ---- 真实进度仪表盘（与主窗口 ForgeStyleOverlay 共用 ForgeStyleLayout 常量 + LoadProgress 数据） ----
    //
    // 早期窗口的 y 是「行盒顶」，文字直接用 *_TOP；色块是绝对坐标，不加 EARLY_INK_OFFSET。
    // EarlyGl 的颜色是 ABGR，所有颜色一律经 abgr(0xRRGGBB) 转换，避免照抄 ARGB 变青（经验 C49）。

    private io.mirage.loader.LoadProgress.View dashboardView() {
        long now = System.currentTimeMillis();
        io.mirage.loader.LoadProgress.View v = uiView;
        if (v == null || now - uiViewAt >= VIEW_SNAPSHOT_MS) {
            v = io.mirage.loader.LoadProgress.view(ForgeStyleLayout.METER_COUNT,
                    ForgeStyleLayout.EVENT_VISIBLE_LINES);
            uiView = v;
            uiViewAt = now;
        }
        return v;
    }

    /** 0xRRGGBB → 0xAABBGGRR（EarlyGl 的打包顺序）。 */
    private static int abgr(int rgb) {
        int r = (rgb >>> 16) & 0xFF;
        int g = (rgb >>> 8) & 0xFF;
        int b = rgb & 0xFF;
        return 0xFF000000 | (b << 16) | (g << 8) | r;
    }

    private void drawSegmentedTotal(float sx, float sy, io.mirage.loader.LoadProgress.View v) {
        int top = ForgeStyleLayout.TOTAL_BAR_TOP;
        int bottom = top + ForgeStyleLayout.TOTAL_BAR_HEIGHT;
        int x0 = ForgeStyleLayout.barLeft();
        int x1 = ForgeStyleLayout.barOuterRight();
        int white = abgr(ForgeStyleLayout.COLOUR_ACTIVE);
        int red = abgr(ForgeStyleLayout.FORGE_RED);
        int green = abgr(ForgeStyleLayout.COLOUR_OK);
        float[] segs = v.segments();
        int stage = io.mirage.loader.LoadProgress.stageIndex();
        gl.begin();
        gl.quad(x0 * sx, x1 * sx, top * sy, bottom * sy, 0, 0, 0, 0, white);
        gl.quad((x0 + 2) * sx, (x1 - 2) * sx, (top + 2) * sy, (bottom - 2) * sy,
                0, 0, 0, 0, red);
        for (int i = 0; i < ForgeStyleLayout.STAGE_COUNT; i++) {
            int a = ForgeStyleLayout.segmentLeft(i) + 2;
            int b = ForgeStyleLayout.segmentRight(i) - 2;
            if (b <= a) continue;
            float f = segs[i];
            int colour = i < stage ? green : white;
            if (f > 0f) {
                int right = Math.round(a + (b - a) * Math.min(1f, f));
                gl.quad(a * sx, right * sx, (top + 4) * sy, (bottom - 4) * sy,
                        0, 0, 0, 0, colour);
            } else if (f < 0f) {
                int w = Math.round((b - a) * io.mirage.loader.LoadProgress.CURSOR_FRACTION);
                int left = Math.round(a + (b - a - w)
                        * (io.mirage.loader.LoadProgress.cursorLeft(frame)
                        / (1f - io.mirage.loader.LoadProgress.CURSOR_FRACTION)));
                gl.quad(left * sx, (left + w) * sx, (top + 4) * sy, (bottom - 4) * sy,
                        0, 0, 0, 0, white);
            }
            if (i > 0) {
                int sep = ForgeStyleLayout.segmentLeft(i);
                gl.quad(sep * sx, (sep + 1) * sx, (top + 1) * sy, (bottom - 1) * sy,
                        0, 0, 0, 0, red);
            }
        }
        gl.draw(EarlyGl.TYPE_BAR, 0);
        font.drawText(gl, truncate(v.totalLabel(), ForgeStyleLayout.BAR_WIDTH), x0,
                ForgeStyleLayout.TOTAL_TEXT_TOP, white, sx, sy);
    }

    private void drawBreadcrumb(float sx, float sy, io.mirage.loader.LoadProgress.View v) {
        int x0 = ForgeStyleLayout.barLeft();
        int x1 = ForgeStyleLayout.barOuterRight();
        gl.begin();
        gl.quad(x0 * sx, x1 * sx, ForgeStyleLayout.DIVIDER_1_TOP * sy,
                (ForgeStyleLayout.DIVIDER_1_TOP + 1) * sy, 0, 0, 0, 0,
                abgr(ForgeStyleLayout.COLOUR_LINE));
        gl.draw(EarlyGl.TYPE_BAR, 0);
        font.drawText(gl, v.breadcrumb(), x0, ForgeStyleLayout.BREADCRUMB_TOP,
                abgr(ForgeStyleLayout.COLOUR_DIM), sx, sy);
    }

    private void drawMeters(float sx, float sy, io.mirage.loader.LoadProgress.View v) {
        java.util.List<io.mirage.loader.LoadProgress.Snapshot> meters = v.meters();
        int white = abgr(ForgeStyleLayout.COLOUR_ACTIVE);
        int red = abgr(ForgeStyleLayout.FORGE_RED);
        int green = abgr(ForgeStyleLayout.COLOUR_OK);
        int x0 = ForgeStyleLayout.barLeft();
        int x1 = ForgeStyleLayout.barOuterRight();
        for (int i = 0; i < ForgeStyleLayout.METER_COUNT; i++) {
            int top = ForgeStyleLayout.meterBarTop(i);
            int bottom = top + ForgeStyleLayout.METER_BAR_HEIGHT;
            gl.begin();
            gl.quad(x0 * sx, x1 * sx, top * sy, bottom * sy, 0, 0, 0, 0, white);
            gl.quad((x0 + 2) * sx, (x1 - 2) * sx, (top + 2) * sy, (bottom - 2) * sy,
                    0, 0, 0, 0, red);
            if (i < meters.size()) {
                io.mirage.loader.LoadProgress.Snapshot s = meters.get(i);
                if (s.indeterminate()) {
                    int a = ForgeStyleLayout.barFillLeft();
                    int w = Math.round(ForgeStyleLayout.BAR_WIDTH
                            * io.mirage.loader.LoadProgress.CURSOR_FRACTION);
                    int left = a + Math.round(ForgeStyleLayout.BAR_WIDTH
                            * io.mirage.loader.LoadProgress.cursorLeft(frame));
                    gl.quad(left * sx, (left + w) * sx, (top + 4) * sy, (bottom - 4) * sy,
                            0, 0, 0, 0, white);
                } else if (s.fraction() > 0f) {
                    int right = ForgeStyleLayout.barFillRight(s.fraction());
                    gl.quad(ForgeStyleLayout.barFillLeft() * sx, right * sx,
                            (top + 4) * sy, (bottom - 4) * sy, 0, 0, 0, 0, white);
                }
            }
            gl.draw(EarlyGl.TYPE_BAR, 0);
            if (i >= meters.size()) continue;
            io.mirage.loader.LoadProgress.Snapshot s = meters.get(i);
            String head = io.mirage.loader.LoadProgress.meterHead(s);
            String tail = io.mirage.loader.LoadProgress.meterTail(s);
            int labelTop = ForgeStyleLayout.meterLabelTop(i);
            font.drawText(gl, truncate(head, ForgeStyleLayout.BAR_WIDTH / 2), x0,
                    labelTop, white, sx, sy);
            font.drawText(gl, tail, x1 - font.stringWidth(tail),
                    labelTop, green, sx, sy);
        }
    }

    private void drawCounters(float sx, float sy, io.mirage.loader.LoadProgress.View v) {
        int x0 = ForgeStyleLayout.barLeft();
        int x1 = ForgeStyleLayout.barOuterRight();
        gl.begin();
        gl.quad(x0 * sx, x1 * sx, ForgeStyleLayout.DIVIDER_2_TOP * sy,
                (ForgeStyleLayout.DIVIDER_2_TOP + 1) * sy, 0, 0, 0, 0,
                abgr(ForgeStyleLayout.COLOUR_LINE));
        gl.draw(EarlyGl.TYPE_BAR, 0);
        font.drawText(gl, v.counter1(), x0, ForgeStyleLayout.COUNTER_TOP,
                abgr(ForgeStyleLayout.COLOUR_ACTIVE), sx, sy);
        font.drawText(gl, v.counter2(), x0,
                ForgeStyleLayout.COUNTER_TOP + ForgeStyleLayout.COUNTER_ROW_H,
                abgr(ForgeStyleLayout.COLOUR_DIM), sx, sy);
    }

    private void drawEvents(float sx, float sy, io.mirage.loader.LoadProgress.View v) {
        int panelTop = ForgeStyleLayout.EVENT_PANEL_TOP;
        gl.begin();
        gl.quad(8 * sx, (DESIGN_WIDTH - 8) * sx, panelTop * sy, DESIGN_HEIGHT * sy,
                0, 0, 0, 0, abgr(ForgeStyleLayout.COLOUR_PANEL));
        gl.draw(EarlyGl.TYPE_BAR, 0);
        font.drawText(gl, "事件 · " + io.mirage.loader.LoadProgress.EVENT_CAPACITY + " 缓冲",
                12, ForgeStyleLayout.EVENT_HEADER_TOP, abgr(ForgeStyleLayout.COLOUR_DIM), sx, sy);
        java.util.List<String> lines = v.eventLines();
        int maxTextW = DESIGN_WIDTH - 2 * 8 - 8;
        for (int i = 0; i < ForgeStyleLayout.EVENT_VISIBLE_LINES && i < lines.size(); i++) {
            String raw = lines.get(i);
            int colour = raw.startsWith("[ERROR]") ? ForgeStyleLayout.COLOUR_FAIL
                    : raw.startsWith("[WARN]") ? ForgeStyleLayout.COLOUR_WARN
                    : raw.startsWith("[STAGE]") ? ForgeStyleLayout.COLOUR_OK
                    : ForgeStyleLayout.COLOUR_DIM;
            font.drawText(gl, truncate(raw, maxTextW), 12,
                    ForgeStyleLayout.EVENT_LINE_TOP + i * ForgeStyleLayout.EVENT_LINE_H,
                    abgr(colour), sx, sy);
        }
    }

    private void drawMemoryBar(float sx, float sy) {
        float ratio = memoryRatio();
        float y0 = 10;
        float y1 = y0 + BAR_HEIGHT;
        float x0 = (DESIGN_WIDTH - BAR_WIDTH) / 2f;
        float x1 = x0 + BAR_WIDTH + 8;

        int barColour = hsvToPacked((1f - (float) Math.pow(ratio, 1.5d)) / 3f, 1f, 0.5f);

        gl.begin();
        gl.quad(x0 * sx, x1 * sx, y0 * sy, y1 * sy, 0, 0, 0, 0, COLOUR_WHITE);
        gl.quad((x0 + 2) * sx, (x1 - 2) * sx, (y0 + 2) * sy, (y1 - 2) * sy,
                0, 0, 0, 0, COLOUR_BACKGROUND);
        gl.quad((x0 + 4) * sx, (x0 + 4 + ratio * BAR_WIDTH) * sx,
                (y0 + 4) * sy, (y1 - 4) * sy, 0, 0, 0, 0, barColour);
        gl.draw(EarlyGl.TYPE_BAR, 0);
    }

    private void drawMemoryText(float sx, float sy) {
        String text = memoryText + "  " + cpuText;
        int x = DESIGN_WIDTH / 2 - font.stringWidth(text) / 2;
        // 早期窗口的 y 是「行盒顶」，必须用原始 TOP 常量；ForgeStyleLayout.*Y() 是 MC 锚点（+8）。
        font.drawText(gl, text, x, ForgeStyleLayout.MEMORY_TEXT_TOP, COLOUR_WHITE, sx, sy);
    }

    // ---- 总进度条（内存条下方，显示整体加载百分比） ----

    private void drawTotalProgress(float sx, float sy) {
        float p = clamp01(MirageDebug.progress / 100f);
        int top = ForgeStyleLayout.TOTAL_BAR_TOP;
        int barH = ForgeStyleLayout.TOTAL_BAR_HEIGHT;
        int x0 = ForgeStyleLayout.barLeft();
        int x1 = ForgeStyleLayout.barOuterRight();
        int r = (int) (255 * (1f - p));
        int g = (int) (255 * p);
        int barColour = 0xFF000000 | (0x40 << 16) | (g << 8) | r;
        gl.begin();
        gl.quad(x0 * sx, x1 * sx, top * sy, (top + barH) * sy, 0, 0, 0, 0, COLOUR_WHITE);
        gl.quad((x0 + 2) * sx, (x1 - 2) * sx, (top + 2) * sy, (top + barH - 2) * sy,
                0, 0, 0, 0, COLOUR_BACKGROUND);
        gl.quad((x0 + 4) * sx, (x0 + 4 + p * ForgeStyleLayout.BAR_WIDTH) * sx,
                (top + 4) * sy, (top + barH - 4) * sy, 0, 0, 0, 0, barColour);
        gl.draw(EarlyGl.TYPE_BAR, 0);
        font.drawText(gl, "总进度 " + MirageDebug.progress + "%",
                x0, ForgeStyleLayout.TOTAL_TEXT_TOP, COLOUR_WHITE, sx, sy);
    }

    // ---- 当前阶段摘要（总进度条下方，概括当前底层子任务） ----

    private void drawStageSummary(float sx, float sy) {
        font.drawText(gl, MirageDebug.stageSummary(),
                ForgeStyleLayout.barLeft(), ForgeStyleLayout.STAGE_SUMMARY_TOP, COLOUR_WHITE, sx, sy);
    }

    // ---- S1–S7 步骤条（中部，已完成打勾/进行中高亮/待处理灰显） ----

    private void drawStageStepper(float sx, float sy) {
        int[] states = MirageDebug.phaseStates();
        int listLeft = ForgeStyleLayout.barLeft();
        int sqSize = 14;
        for (int k = 0; k < states.length; k++) {
            int sqX = listLeft;
            int rowTop = ForgeStyleLayout.stageRowTop(k);
            // 方块与文字墨迹对齐：早期窗口墨迹从行盒顶 +9 才开始
            int sqY = rowTop + ForgeStyleLayout.EARLY_INK_OFFSET;
            int sqColor;
            int textColor;
            switch (states[k]) {
                case 2: // 已完成：绿底
                    sqColor = 0xFF000000 | (0x55 << 16) | (0xFF << 8) | 0x55;
                    textColor = 0xFF000000 | (0xCF << 16) | (0xCF << 8) | 0xCF;
                    break;
                case 1: // 进行中：黄底高亮（ABGR：B=55 G=FF R=FF）
                    sqColor = 0xFF000000 | (0x55 << 16) | (0xFF << 8) | 0xFF;
                    textColor = 0xFFFFFFFF;
                    break;
                default: // 待处理：暗灰
                    sqColor = 0xFF000000 | (0x55 << 16) | (0x55 << 8) | 0x55;
                    textColor = 0xFF000000 | (0x88 << 16) | (0x88 << 8) | 0x88;
                    break;
            }
            gl.begin();
            gl.quad(sqX * sx, (sqX + sqSize) * sx, sqY * sy, (sqY + sqSize) * sy, 0, 0, 0, 0, sqColor);
            gl.draw(EarlyGl.TYPE_BAR, 0);
            font.drawText(gl, "S" + (k + 1) + " " + MirageDebug.PHASE_NAMES[k],
                    sqX + sqSize + 6, rowTop, textColor, sx, sy);
        }
    }

    // ---- 底部滚动日志面板（占屏幕约 1/3，从下到上滚动，节选真实加载日志） ----

    private void drawLogPanel(float sx, float sy) {
        int margin = 8;
        int panelTop = ForgeStyleLayout.logPanelTop();
        int panelBottom = ForgeStyleLayout.DESIGN_HEIGHT;
        gl.begin();
        gl.quad(margin * sx, (DESIGN_WIDTH - margin) * sx, panelTop * sy, panelBottom * sy,
                0, 0, 0, 0, 0xFF141414);
        gl.draw(EarlyGl.TYPE_BAR, 0);

        font.drawText(gl, "日志（实时）", margin + 4, panelTop + 4, 0xFFAAAAAA, sx, sy);

        // 24px 字墨迹高 17px（y+9..y+26），行距取 22 才有 5px 间隙；行数由「首行~底边」预算决定
        int lineH = ForgeStyleLayout.LOG_LINE_H;
        int firstTop = ForgeStyleLayout.logFirstLineTop();
        int bottomTop = panelBottom - ForgeStyleLayout.LOG_BOTTOM_MARGIN - lineH;
        int maxLines = Math.max(1, (bottomTop - firstTop) / lineH + 1);
        String[] lines = MirageDebug.loadingLogLines();
        int start = Math.max(0, lines.length - maxLines);
        int y = bottomTop;
        int maxTextW = DESIGN_WIDTH - 2 * margin - 8;
        for (int k = lines.length - 1; k >= start; k--) {
            String raw = lines[k];
            font.drawText(gl, truncate(raw, maxTextW), margin + 4, y, logColor(raw), sx, sy);
            y -= lineH;
        }
    }

    private void drawVersion(float sx, float sy) {
        int x = DESIGN_WIDTH - font.stringWidth(versionLabel) - ForgeStyleLayout.VERSION_MARGIN;
        font.drawText(gl, versionLabel, x, ForgeStyleLayout.VERSION_TOP_RIGHT, COLOUR_WHITE, sx, sy);
    }

    private void updatePerformance() {
        try {
            MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
            long usedMb = heap.getUsed() / (1024L * 1024L);
            long maxMb = heap.getMax() > 0 && heap.getMax() != Long.MAX_VALUE
                    ? heap.getMax() / (1024L * 1024L) : 0;
            float pct = maxMb > 0 ? usedMb * 100f / maxMb : 0f;
            memoryText = String.format(Locale.ROOT, "Memory: %d/%dMB (%.1f%%)", usedMb, maxMb, pct);

            OperatingSystemMXBean os = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
            double load = os.getProcessCpuLoad();
            if (load < 0) {
                load = os.getCpuLoad();
                cpuText = String.format(Locale.ROOT, "*CPU: %.1f%%", Math.max(load, 0) * 100);
            } else {
                cpuText = String.format(Locale.ROOT, "CPU: %.1f%%", load * 100);
            }
        } catch (Throwable ignored) {
            // 性能信息只是装饰，失败不影响加载
        }
    }

    private long handoff(int width, int height, CharSequence title, long monitor, long share) {
        handedOff = true;
        boolean locked = false;
        // 性能埋点：交接含两次「等」——renderLock 与渲染线程终止，分开量才知道等的是哪一段。
        //
        // 旧实现是 tryAcquire(3s) + awaitTermination(2s) 两段纯阻塞，合计 5s；而 handedOff
        // 置位后渲染任务随即被 cancel、不再调用 glfwPollEvents()，交接线程自己又卡在阻塞里
        // 不抽窗口消息 —— 正好踩中 Windows 判定「未响应」的阈值（实机反馈：交接时卡住）。
        // 现改走 HandoffPump.await：每 10ms 由本线程（创建窗口的主线程）抽一次消息，
        // 并把两段等待上限压到 800ms / 500ms（一帧渲染约 50ms，足够宽裕）。
        io.mirage.loader.MiragePerf.mark("early.handoff.begin");
        try {
            if (renderTask != null) renderTask.cancel(false);
            locked = HandoffPump.await(800, renderLock::tryAcquire, GLFW::glfwPollEvents);
            io.mirage.loader.MiragePerf.mark("early.handoff.lock");
            if (!locked) {
                MirageLog.MIRAGE.warn("早期窗口交接：800ms 未取得 renderLock（继续交接，GL 上下文可能竞态）");
            }
            if (scheduler != null) {
                scheduler.shutdownNow();
                boolean terminated = HandoffPump.await(500, scheduler::isTerminated,
                        GLFW::glfwPollEvents);
                if (!terminated) {
                    MirageLog.MIRAGE.warn("早期窗口交接：渲染线程 500ms 内未退出（继续交接）");
                }
            }
            io.mirage.loader.MiragePerf.mark("early.handoff.thread");
            GLFW.glfwMakeContextCurrent(window);
            if (title != null) GLFW.glfwSetWindowTitle(window, title.toString());
            if (width > 0 && height > 0
                    && (width != winWidth || height != winHeight)) {
                GLFW.glfwSetWindowSize(window, width, height);
            }
            winWidth = width;
            winHeight = height;
            GLFW.glfwSwapInterval(0);
            GLFW.glfwShowWindow(window);
            MirageLog.MIRAGE.info("早期窗口已交接给 Minecraft (handle=0x"
                    + Long.toHexString(window) + ")");
            io.mirage.loader.MiragePerf.mark("early.handoff.done");
            return window;
        } catch (Throwable t) {
            MirageLog.MIRAGE.warn("早期窗口交接失败（将回退到原版窗口创建）", t);
            crashed = true;
            try {
                GLFW.glfwDestroyWindow(window);
            } catch (Throwable ignored) {
            }
            window = NULL;
            return GLFW.glfwCreateWindow(width, height, title, monitor, share);
        } finally {
            if (locked) renderLock.release();
        }
    }

    private float memoryRatio() {
        try {
            MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
            long max = heap.getMax();
            if (max <= 0 || max == Long.MAX_VALUE) return 0f;
            return clamp01((float) ((double) heap.getUsed() / max));
        } catch (Throwable t) {
            return 0f;
        }
    }

    private static int hsvToPacked(float hue, float saturation, float value) {
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
        // 与 Forge ColourScheme#packedint 一致：0xAABBGGRR
        return 0xFF000000
                | ((int) (b * 255) << 16)
                | ((int) (g * 255) << 8)
                | (int) (r * 255);
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    private static int logColor(String line) {
        if (line.contains("ERROR") || line.contains("Exception")
                || line.contains("失败") || line.contains("错误")) {
            return 0xFF7777FF;
        }
        if (line.contains("WARN") || line.contains("警告")) {
            return 0xFF77FFFF;
        }
        return 0xFFBBBBBB;
    }

    private String truncate(String s, int maxW) {
        if (s == null) return "";
        if (font.stringWidth(s) <= maxW) return s;
        String t = s;
        while (t.length() > 1 && font.stringWidth(t + "...") > maxW) {
            t = t.substring(0, t.length() - 1);
        }
        return t + "...";
    }

    /**
     * 与 {@link io.mirage.loader.LoadingFonts} 共用候选表：主窗口 AWT 与早期窗口 STB
     * 必须读到同一个中文字体文件，否则两边字形会不同（见 LoadingFonts 类注释）。
     */
    private static String findCjkFont() {
        return io.mirage.loader.LoadingFonts.findCjkFont();
    }


}
