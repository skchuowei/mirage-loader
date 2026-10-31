package io.mirage.launch;

import io.mirage.api.log.MirageLog;

/**
 * Mirage Loader 入口点。
 *
 * <p><b>phase 2（LS 迁移完成后）：thin wrapper。</b>进程级准备工作仍在本地——
 * 日志 tee（旁路喂 HUD 日志节选）、banner、Forge 式早期窗口——随后
 * <b>单向委托</b> {@link io.mirage.ls.LifecycleStarter#main(String[])}：
 * 全部生命周期逻辑（S1–S7 阶段、C1–C4 四周期、ParseFailPolicy）已迁入
 * {@code io.mirage.ls} / {@code io.mirage.ls.stage}（见 LS-Interfaces.md §7/§9）。
 * 本类不再持有任何启动流程状态；拆分前的方法（detectDist / parseGameDir /
 * constructModInstance / postNeoLifecycle 等）已按保序原则逐个迁入 LS 阶段类。
 *
 * <p>依赖方向（LS-Interfaces §9 反递归约束）：Launcher → lifecycle-starter 单向，
 * lifecycle-starter 不得反向依赖 launcher；Gradle 侧由本模块声明
 * {@code implementation(project(":lifecycle-starter"))}，fat jar 经 runtimeClasspath
 * 自动 shade。启动脚本 / Main-Class 不变。
 */
public final class Launcher {

    public static final String VERSION = "0.4.0";

    public static void main(String[] args) {
        installLogTee(gameDirOf(args));
        banner();
        // 加载界面的像素字要 AWT 光栅化（重载期不能用 gge.text，见 ForgeStyleOverlay 注释）。
        // 这里在主线程上只是**登记**一次后台任务：AWT/Java2D 初始化与系统字体枚举
        // 动辄几十到几百毫秒，必须在渲染线程之外先付掉，否则 LoadingOverlay 首帧
        // 会卡在字体枚举上，GLFW 消息泵停摆 → Windows 判定「未响应」。
        io.mirage.loader.ForgeStyleOverlay.prewarm();
        // Forge 式早期窗口：在 Minecraft 创建自己的窗口之前，先用 GLFW + OpenGL
        // 渲染 NeoForge 风格的加载界面；MC Window.createGlfwWindow 时由
        // MirageEarlyWindow.createOrBorrow 把窗口和 GL 上下文交接给 MC。
        io.mirage.earlydisplay.MirageEarlyWindow.start("26.2", VERSION,
                parseIntArg(args, "--width", 854), parseIntArg(args, "--height", 480));

        // 单向委托 LS（LS-Interfaces §9 允许的唯一方向）：此后一切阶段推进、
        // 周期决策、失败策略均发生在 io.mirage.ls 内，本线程即 LS 线程。
        io.mirage.ls.LifecycleStarter.main(args);
    }

    /** 解析 `--width 854` 这类整数参数；缺失或非法时用默认值。 */
    private static int parseIntArg(String[] args, String name, int defaultValue) {
        for (int i = 0; i < args.length - 1; i++) {
            if (name.equals(args[i])) {
                try {
                    return Integer.parseInt(args[i + 1]);
                } catch (NumberFormatException ignored) {
                    return defaultValue;
                }
            }
        }
        return defaultValue;
    }

    private static void banner() {
        System.out.println("==================================================");
        System.out.println("  Mirage Loader 幻像加载器 v" + VERSION);
        System.out.println("  独立 · 轻量 · 现代化");
        System.out.println("==================================================");
        MirageLog.MIRAGE.raw("[Mirage] 来自 Mirage Launch " + VERSION);
    }

    /** 解析 `--gameDir xxx`；缺失时返回 null（日志只进控制台，不落盘）。 */
    private static java.nio.file.Path gameDirOf(String[] args) {
        for (int i = 0; i < args.length - 1; i++) {
            if ("--gameDir".equals(args[i])) {
                try {
                    return java.nio.file.Path.of(args[i + 1]);
                } catch (Exception ignore) {
                    return null;
                }
            }
        }
        return null;
    }

    /**
     * stdout/stderr 旁路：控制台照旧输出，同时把整行喂给 HUD 日志节选，
     * 并写一份到 {@code <gameDir>/logs/mirage-launch.log}。
     *
     * <p><b>为什么需要自己的文件</b>：log4j 直到 Minecraft 启动后才接管并写
     * {@code latest.log}，在那之前的 S1–S3（mod 发现、MID 缓存、沙盒录制）与
     * banner/早期窗口全是裸 stdout —— 只进 bat 控制台窗口，关了窗口就没了。
     * 历史上多次实机排查看不到 S1–S3 就是这个原因。
     */
    private static void installLogTee(java.nio.file.Path gameDir) {
        try {
            java.io.PrintStream out = System.out;
            java.io.PrintStream err = System.err;
            java.io.PrintWriter fileLog = openLaunchLog(gameDir);
            java.nio.charset.Charset utf8 = java.nio.charset.StandardCharsets.UTF_8;
            System.setOut(new java.io.PrintStream(new TeeOutputStream(out, fileLog), true, utf8));
            System.setErr(new java.io.PrintStream(new TeeOutputStream(err, fileLog), true, utf8));
        } catch (Throwable t) {
            MirageLog.MIRAGE.warn("日志 tee 安装失败（HUD 日志节选与落盘不可用，控制台输出不受影响）", t);
        }
    }

    /** 打开 {@code logs/mirage-launch.log}（append）。失败时返回 null（退化为只走控制台）。 */
    private static java.io.PrintWriter openLaunchLog(java.nio.file.Path gameDir) {
        if (gameDir == null) return null;
        try {
            java.nio.file.Path dir = gameDir.resolve("logs");
            java.nio.file.Files.createDirectories(dir);
            java.nio.file.Path f = dir.resolve("mirage-launch.log");
            // 简单轮转：单文件超过 8 MB 就换成 .prev，避免长期累积成几百 MB
            if (java.nio.file.Files.isRegularFile(f) && java.nio.file.Files.size(f) > 8L * 1024 * 1024) {
                java.nio.file.Files.move(f, dir.resolve("mirage-launch.log.prev"),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            java.io.PrintWriter w = new java.io.PrintWriter(java.nio.file.Files.newBufferedWriter(
                    f, java.nio.charset.StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND), true);
            // 每次启动一条分隔头：同一次会话的日志边界一目了然
            w.println("===== Mirage Launch " + VERSION + " @ "
                    + java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")) + " =====");
            return w;
        } catch (Throwable t) {
            return null;
        }
    }

    private static final class TeeOutputStream extends java.io.OutputStream {
        private final java.io.OutputStream target;
        /** 落盘目标（可为 null）。两路 tee 共用一个，PrintWriter 自身同步。 */
        private final java.io.PrintWriter fileLog;
        private final java.io.ByteArrayOutputStream line = new java.io.ByteArrayOutputStream(256);

        private TeeOutputStream(java.io.OutputStream target, java.io.PrintWriter fileLog) {
            this.target = target;
            this.fileLog = fileLog;
        }

        @Override
        public void write(int b) throws java.io.IOException {
            target.write(b);
            synchronized (line) {
                if (b == '\n') emitLine();
                else if (b != '\r' && line.size() < 4096) line.write(b);
            }
        }

        @Override
        public void write(byte[] b, int off, int len) throws java.io.IOException {
            target.write(b, off, len);
            synchronized (line) {
                for (int i = off; i < off + len; i++) {
                    int v = b[i] & 0xFF;
                    if (v == '\n') emitLine();
                    else if (v != '\r' && line.size() < 4096) line.write(v);
                }
            }
        }

        @Override
        public void flush() throws java.io.IOException {
            target.flush();
        }

        private void emitLine() {
            String s = new String(line.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
            line.reset();
            if (fileLog != null) {
                // autoFlush=true：崩溃现场也能在文件里看到前因（这里多一次 flush 值得）
                fileLog.println(s);
            }
            io.mirage.loader.MirageDebug.log(s);
        }
    }

    private Launcher() {}
}
