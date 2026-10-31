package io.mirage.earlydisplay;

import io.mirage.api.log.MirageLog;

import com.sun.management.OperatingSystemMXBean;
import io.mirage.loader.MirageDebug;

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
        font = new EarlyFont("/Monocraft.ttf", 0);

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
        drawProgressBars(sx, sy);
        drawVersion(sx, sy);
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
        gl.begin();
        font.drawText(gl, text, x, 10 + 18, COLOUR_WHITE, sx, sy);
        gl.draw(EarlyGl.TYPE_FONT, 0);
    }

    private void drawProgressBars(float sx, float sy) {
        int barSpacing = font.lineSpacing() - font.descent() + 20;
        String[] labels = {"Loading mods", "Config loading"};
        float progress = clamp01(MirageDebug.progress / 100f);
        int x0 = (DESIGN_WIDTH - BAR_WIDTH) / 2;

        for (int i = 0; i < labels.length; i++) {
            int alpha = 0xFF >> i;
            int colour = (alpha << 24) | 0x00FFFFFF;

            float p0;
            float p1;
            if (i == 0) {
                p0 = clamp01((frame % 100 - 2) / 100f);
                p1 = clamp01((frame % 100 + 2) / 100f);
            } else {
                p0 = 0f;
                p1 = progress;
            }

            float y = 250 + i * barSpacing;
            float by0 = y + font.lineSpacing() - font.descent();
            float by1 = by0 + BAR_HEIGHT;

            gl.begin();
            // 外框
            gl.quad(x0 * sx, (x0 + BAR_WIDTH + 8) * sx, by0 * sy, by1 * sy, 0, 0, 0, 0, colour);
            // 内衬
            gl.quad((x0 + 2) * sx, (x0 + BAR_WIDTH + 6) * sx, (by0 + 2) * sy, (by1 - 2) * sy,
                    0, 0, 0, 0, (alpha << 24) | 0x003D32EF);
            // 填充
            gl.quad((x0 + 4) * sx, (x0 + 4 + (p1 - p0) * BAR_WIDTH) * sx,
                    (by0 + 4) * sy, (by1 - 4) * sy, 0, 0, 0, 0, colour);
            gl.draw(EarlyGl.TYPE_BAR, 0);

            gl.begin();
            font.drawText(gl, labels[i], x0, (int) y, colour, sx, sy);
            gl.draw(EarlyGl.TYPE_FONT, 0);
        }
    }

    private void drawVersion(float sx, float sy) {
        int x = DESIGN_WIDTH - font.stringWidth(versionLabel) - 10;
        int y = DESIGN_HEIGHT - font.lineSpacing() + font.descent() - 10;
        gl.begin();
        font.drawText(gl, versionLabel, x, y, COLOUR_WHITE, sx, sy);
        gl.draw(EarlyGl.TYPE_FONT, 0);
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
        try {
            locked = renderLock.tryAcquire(3, TimeUnit.SECONDS);
            if (renderTask != null) renderTask.cancel(false);
            if (scheduler != null) {
                scheduler.shutdownNow();
                try {
                    scheduler.awaitTermination(2, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
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
}
