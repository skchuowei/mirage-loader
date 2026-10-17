package io.mirage.launch;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.RoundRectangle2D;
import java.io.PrintStream;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Deque;

/**
 * Mirage Loader 启动进度界面 —— 深色现代主题。
 * 无边框置顶，MC 主菜单出现后自动关闭。
 *
 * 改进：
 *  - 自动轮询 MirageDebug.stage/progress，无需 Launcher 显式同步
 *  - 重定向 System.out/err，所有 [Mirage] 输出实时进日志区
 *  - 平滑进度条插值动画，避免跳变
 *  - 圆角窗口、可拖拽、关闭按钮、渐变进度条
 *  - 时间戳、已耗时、内存、CPU、mod 计数
 */
public final class LoadingScreen extends JFrame {

    private static LoadingScreen instance;

    // ---------- 配色 ----------
    private static final Color BG       = new Color(0x0f, 0x11, 0x1a);
    private static final Color PANEL    = new Color(0x1a, 0x1e, 0x2e);
    private static final Color CARD     = new Color(0x23, 0x28, 0x3d);
    private static final Color ACCENT   = new Color(0x64, 0xff, 0xda);
    private static final Color ACCENT2  = new Color(0x4a, 0x9d, 0xff);
    private static final Color TEXT     = new Color(0xe6, 0xe6, 0xf0);
    private static final Color DIM      = new Color(0x8a, 0x8f, 0xb0);
    private static final Color DANGER   = new Color(0xff, 0x6b, 0x6b);
    private static final Color BORDER   = new Color(0x2e, 0x34, 0x50);

    // 中文字体（Windows 自带微软雅黑，含 CJK 字形）
    private static final String FONT_UI = "Microsoft YaHei";
    private static final String FONT_MONO = "Microsoft YaHei";

    // ---------- 组件 ----------
    private final JLabel titleLabel  = new JLabel("MIRAGE LOADER", SwingConstants.LEFT);
    private final JLabel verLabel    = new JLabel("", SwingConstants.RIGHT);
    private final JLabel stageLabel  = new JLabel("Starting ...", SwingConstants.CENTER);
    private final JLabel detailLabel = new JLabel("", SwingConstants.CENTER);
    private final JLabel pctLabel    = new JLabel("0%", SwingConstants.RIGHT);
    private final JLabel memLabel    = new JLabel();
    private final JLabel cpuLabel    = new JLabel();
    private final JLabel timeLabel   = new JLabel();
    private final JTextArea logArea  = new JTextArea(7, 60);
    private final GradientBar progressBar = new GradientBar();
    private final JButton closeBtn   = new JButton("×");

    // ---------- 状态 ----------
    private final Deque<String> logLines = new ArrayDeque<>();
    private final SimpleDateFormat tsFmt = new SimpleDateFormat("HH:mm:ss");
    private final long startTime = System.currentTimeMillis();
    private final Runtime rt = Runtime.getRuntime();

    // 平滑进度动画
    private volatile int targetProgress = 0;
    private volatile int displayedProgress = 0;
    private volatile String targetStage = "";
    private volatile String targetDetail = "";

    // 窗口拖拽
    private Point dragOffset = null;

    // ---------- 公共 API（保持兼容） ----------
    public static synchronized void open() {
        SwingUtilities.invokeLater(() -> {
            if (instance != null) return;
            instance = new LoadingScreen();
            instance.setVisible(true);
        });
    }

    public static synchronized void releaseNow() {
        SwingUtilities.invokeLater(() -> {
            if (instance != null) { instance.dispose(); instance = null; }
        });
    }

    public static synchronized void release() {
        if (instance == null) return;
        LoadingScreen self = instance;
        Thread watcher = new Thread(() -> {
            try {
                // MC 用 GLFW 窗口（不是 AWT Frame），Window.getWindows() 找不到
                // 改为轮询 MirageDebug.loaded 标志（TitleScreen.init() 织入时设置）
                for (int i = 0; i < 600; i++) {  // 最多等 120 秒
                    Thread.sleep(200);
                    try {
                        Class<?> md = Class.forName("io.mirage.loader.MirageDebug", false,
                            LoadingScreen.class.getClassLoader());
                        boolean loaded = md.getField("loaded").getBoolean(null);
                        if (loaded) {
                            Thread.sleep(1500);  // 等主菜单渲染稳定
                            break;
                        }
                    } catch (Throwable ignored) {}
                }
            } catch (InterruptedException ignored) {}
            SwingUtilities.invokeLater(() -> {
                if (instance != null) { instance.dispose(); instance = null; }
            });
        }, "mirage-loading-watcher");
        watcher.setDaemon(true);
        watcher.start();
    }

    public static void setStage(String text) {
        if (instance == null) return;
        instance.targetStage = text == null ? "" : text;
        log("STAGE: " + text);
    }

    public static void setProgress(int pct) {
        if (instance == null) return;
        instance.targetProgress = Math.max(0, Math.min(100, pct));
    }

    public static void log(String line) {
        if (line == null) return;
        String ts = new SimpleDateFormat("HH:mm:ss").format(new Date());
        String entry = "[" + ts + "] " + line;
        SwingUtilities.invokeLater(() -> {
            if (instance == null) return;
            instance.logLines.addLast(entry);
            while (instance.logLines.size() > 200) instance.logLines.removeFirst();
            instance.refreshLogArea();
        });
    }

    public static void setModList(java.util.List<String> mods) {
        if (instance == null) return;
        int n = mods == null ? 0 : mods.size();
        SwingUtilities.invokeLater(() -> {
            if (instance == null) return;
            instance.verLabel.setText("Mirage " + Launcher.VERSION + " · MC 26.2 · " + n + " mod(s)");
        });
    }

    // ---------- 构造 ----------
    private LoadingScreen() {
        setUndecorated(true);
        setAlwaysOnTop(true);
        setSize(760, 480);
        setLocationRelativeTo(null);
        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        setBackground(BG);

        // 圆角窗口（在显示后再设置，确保尺寸已生效）
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowOpened(java.awt.event.WindowEvent e) {
                setShape(new RoundRectangle2D.Float(0, 0, getWidth(), getHeight(), 18, 18));
            }
        });

        JPanel root = new JPanel(new GridBagLayout());
        root.setBackground(BG);
        root.setBorder(new EmptyBorder(18, 22, 16, 22));
        setContentPane(root);

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(0, 0, 8, 0);
        gbc.gridx = 0; gbc.gridy = 0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1;

        // ===== 顶栏：标题 + 版本 + 关闭 =====
        JPanel topBar = new JPanel(new BorderLayout(12, 0));
        topBar.setOpaque(false);

        titleLabel.setForeground(ACCENT);
        titleLabel.setFont(new Font(FONT_UI, Font.BOLD, 18));

        verLabel.setForeground(DIM);
        verLabel.setFont(new Font(FONT_MONO, Font.PLAIN, 12));
        verLabel.setText("Mirage " + Launcher.VERSION + " · MC 26.2");

        closeBtn.setForeground(DIM);
        closeBtn.setFont(new Font(FONT_UI, Font.BOLD, 16));
        closeBtn.setOpaque(false);
        closeBtn.setBorderPainted(false);
        closeBtn.setContentAreaFilled(false);
        closeBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        closeBtn.addActionListener(e -> releaseNow());
        closeBtn.addMouseListener(new java.awt.event.MouseAdapter() {
            public void mouseEntered(java.awt.event.MouseEvent e) { closeBtn.setForeground(DANGER); }
            public void mouseExited(java.awt.event.MouseEvent e)   { closeBtn.setForeground(DIM); }
        });

        JPanel topRight = new JPanel(new BorderLayout(10, 0));
        topRight.setOpaque(false);
        topRight.add(verLabel, BorderLayout.CENTER);
        topRight.add(closeBtn, BorderLayout.EAST);

        topBar.add(titleLabel, BorderLayout.WEST);
        topBar.add(topRight, BorderLayout.EAST);
        root.add(topBar, gbc);

        // 分隔线
        gbc.gridy = 1; gbc.insets = new Insets(2, 0, 14, 0);
        JSeparator sep = new JSeparator();
        sep.setForeground(BORDER);
        root.add(sep, gbc);

        // ===== 阶段文字 =====
        gbc.gridy = 2; gbc.insets = new Insets(0, 0, 4, 0);
        stageLabel.setForeground(TEXT);
        stageLabel.setFont(new Font(FONT_UI, Font.BOLD, 20));
        root.add(stageLabel, gbc);

        gbc.gridy = 3; gbc.insets = new Insets(0, 0, 14, 0);
        detailLabel.setForeground(DIM);
        detailLabel.setFont(new Font(FONT_UI, Font.PLAIN, 13));
        root.add(detailLabel, gbc);

        // ===== 进度条 + 百分比 =====
        gbc.gridy = 4; gbc.insets = new Insets(0, 0, 6, 0);
        JPanel progPanel = new JPanel(new BorderLayout(12, 0));
        progPanel.setOpaque(false);
        progressBar.setPreferredSize(new Dimension(600, 10));
        pctLabel.setForeground(ACCENT);
        pctLabel.setFont(new Font(FONT_MONO, Font.BOLD, 14));
        pctLabel.setPreferredSize(new Dimension(56, 20));
        progPanel.add(progressBar, BorderLayout.CENTER);
        progPanel.add(pctLabel, BorderLayout.EAST);
        root.add(progPanel, gbc);

        // ===== 底部状态行 =====
        gbc.gridy = 5; gbc.insets = new Insets(10, 0, 10, 0);
        JPanel statusBar = new JPanel(new BorderLayout(20, 0));
        statusBar.setOpaque(false);
        memLabel.setForeground(DIM);
        cpuLabel.setForeground(DIM);
        timeLabel.setForeground(DIM);
        Font mono = new Font(FONT_MONO, Font.PLAIN, 12);
        memLabel.setFont(mono); cpuLabel.setFont(mono); timeLabel.setFont(mono);

        JPanel leftStatus = new JPanel(new FlowLayout(FlowLayout.LEFT, 18, 0));
        leftStatus.setOpaque(false);
        leftStatus.add(memLabel);
        leftStatus.add(cpuLabel);
        JPanel rightStatus = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        rightStatus.setOpaque(false);
        rightStatus.add(timeLabel);

        statusBar.add(leftStatus, BorderLayout.WEST);
        statusBar.add(rightStatus, BorderLayout.EAST);
        root.add(statusBar, gbc);

        // ===== 日志区 =====
        gbc.gridy = 6; gbc.weighty = 1;
        gbc.fill = GridBagConstraints.BOTH;
        gbc.insets = new Insets(4, 0, 0, 0);
        logArea.setOpaque(false);
        logArea.setForeground(new Color(0xb0, 0xb8, 0xd0));
        logArea.setFont(new Font(FONT_MONO, Font.PLAIN, 12));
        logArea.setEditable(false);
        logArea.setLineWrap(false);
        logArea.setBorder(new EmptyBorder(8, 10, 8, 10));
        JScrollPane scroll = new JScrollPane(logArea);
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        scroll.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(BORDER, 1, true),
            new EmptyBorder(2, 2, 2, 2)
        ));
        scroll.getVerticalScrollBar().setUI(new ModernScrollBarUI());
        root.add(scroll, gbc);

        // ===== 窗口拖拽 =====
        MouseDragHandler drag = new MouseDragHandler();
        root.addMouseListener(drag);
        root.addMouseMotionListener(drag);
        topBar.addMouseListener(drag);
        topBar.addMouseMotionListener(drag);
        titleLabel.addMouseListener(drag);
        titleLabel.addMouseMotionListener(drag);

        // ===== 重定向 System.out/err 到日志区 =====
        redirectSystemStreams();

        // ===== 主定时器：平滑进度 + 轮询 MirageDebug + 刷新状态 =====
        new Timer(16, e -> {
            // 平滑插值
            int target = targetProgress;
            int cur = displayedProgress;
            if (cur != target) {
                int diff = target - cur;
                int step = Math.max(1, Math.abs(diff) / 8);
                displayedProgress = diff > 0 ? Math.min(target, cur + step) : Math.max(target, cur - step);
                progressBar.setValue(displayedProgress);
                pctLabel.setText(displayedProgress + "%");
            }
            if (!targetStage.isEmpty() && !targetStage.equals(stageLabel.getText())) {
                stageLabel.setText(targetStage);
            }
        }).start();

        // 100ms 轮询 MirageDebug 字段（Launcher 写，我们读）
        new Timer(100, e -> pollMirageDebug()).start();

        // 500ms 刷新内存/CPU/时间
        new Timer(500, e -> refreshSystemStats()).start();

        refreshSystemStats();
    }

    // ---------- 轮询 MirageDebug ----------
    private void pollMirageDebug() {
        try {
            Class<?> md = Class.forName("io.mirage.loader.MirageDebug", false,
                Thread.currentThread().getContextClassLoader() == null
                    ? LoadingScreen.class.getClassLoader()
                    : Thread.currentThread().getContextClassLoader());
            // 直接用静态字段（同 fat jar，一定可见）
            String stage = (String) md.getField("stage").get(null);
            int prog = md.getField("progress").getInt(null);
            int modCount = md.getField("modCount").getInt(null);
            String modList = (String) md.getField("modList").get(null);

            if (stage != null && !stage.isEmpty()) targetStage = stage;
            if (prog > 0) targetProgress = prog;

            // detail：mod 列表
            if (modList != null && !modList.isEmpty()) {
                String d = modCount + " mod(s) loaded";
                if (!d.equals(detailLabel.getText())) detailLabel.setText(d);
            }
        } catch (Throwable ignored) {
            // MirageDebug 尚未加载或不可见，静默
        }
    }

    // ---------- 系统状态 ----------
    private void refreshSystemStats() {
        long used = rt.totalMemory() - rt.freeMemory();
        long max = rt.maxMemory();
        int pct = (int) (used * 100 / Math.max(1, max));
        memLabel.setText(String.format("MEM %d/%d MB (%d%%)",
            used / (1024 * 1024), max / (1024 * 1024), pct));
        try {
            com.sun.management.OperatingSystemMXBean sun =
                (com.sun.management.OperatingSystemMXBean) java.lang.management.ManagementFactory.getOperatingSystemMXBean();
            double cpu = sun.getProcessCpuLoad() * 100;
            cpuLabel.setText(String.format("CPU %.1f%%", cpu));
        } catch (Exception ex) {
            cpuLabel.setText("CPU n/a");
        }
        long elapsed = (System.currentTimeMillis() - startTime) / 1000;
        timeLabel.setText("elapsed " + elapsed + "s");
    }

    // ---------- 日志 ----------
    private void refreshLogArea() {
        StringBuilder sb = new StringBuilder();
        for (String l : logLines) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(l);
        }
        logArea.setText(sb.toString());
        logArea.setCaretPosition(logArea.getDocument().getLength());
    }

    private void redirectSystemStreams() {
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;

        PrintStream mirOut = new PrintStream(new java.io.OutputStream() {
            @Override public void write(int b) { /* 按行缓冲 */ }
            @Override public void write(byte[] buf, int off, int len) {
                String s = new String(buf, off, len);
                // 同时保留到原控制台
                originalOut.write(buf, off, len);
                // 按行切分送日志区
                for (String line : s.split("\r?\n")) {
                    if (!line.isEmpty()) log(line);
                }
            }
        }, true);

        PrintStream mirErr = new PrintStream(new java.io.OutputStream() {
            @Override public void write(int b) {}
            @Override public void write(byte[] buf, int off, int len) {
                String s = new String(buf, off, len);
                originalErr.write(buf, off, len);
                for (String line : s.split("\r?\n")) {
                    if (!line.isEmpty()) log("[ERR] " + line);
                }
            }
        }, true);

        System.setOut(mirOut);
        System.setErr(mirErr);
    }

    // ---------- 可拖拽 ----------
    private final class MouseDragHandler extends MouseAdapter {
        @Override public void mousePressed(MouseEvent e) {
            dragOffset = new Point(e.getXOnScreen() - getLocation().x,
                                   e.getYOnScreen() - getLocation().y);
        }
        @Override public void mouseDragged(MouseEvent e) {
            if (dragOffset != null) {
                setLocation(e.getXOnScreen() - dragOffset.x,
                            e.getYOnScreen() - dragOffset.y);
            }
        }
    }

    // ---------- 渐变进度条 ----------
    private static final class GradientBar extends JComponent {
        private int value = 0;
        public void setValue(int v) {
            value = Math.max(0, Math.min(100, v));
            repaint();
        }
        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int w = getWidth(), h = getHeight();
            // 背景槽
            g2.setColor(PANEL);
            g2.fillRoundRect(0, 0, w, h, h, h);
            // 渐变填充
            if (value > 0) {
                int fw = Math.max(h, w * value / 100);
                GradientPaint gp = new GradientPaint(0, 0, ACCENT, fw, 0, ACCENT2);
                g2.setPaint(gp);
                g2.fillRoundRect(0, 0, fw, h, h, h);
                // 发光
                g2.setColor(new Color(0x64, 0xff, 0xda, 60));
                g2.fillRoundRect(-2, -2, fw + 4, h + 4, h + 4, h + 4);
            }
            g2.dispose();
        }
    }

    // ---------- 现代滚动条 ----------
    private static final class ModernScrollBarUI extends javax.swing.plaf.basic.BasicScrollBarUI {
        @Override protected void configureScrollBarColors() {
            thumbColor = new Color(0x3a, 0x42, 0x66);
            trackColor = new Color(0x16, 0x1a, 0x2a);
        }
        protected Dimension getThumbSize() {
            return new Dimension(6, 40);
        }
        @Override protected JButton createDecreaseButton(int orientation) {
            return new JButton() { { setPreferredSize(new Dimension(0,0)); setMaximumSize(new Dimension(0,0)); setMinimumSize(new Dimension(0,0)); } };
        }
        @Override protected JButton createIncreaseButton(int orientation) {
            return new JButton() { { setPreferredSize(new Dimension(0,0)); setMaximumSize(new Dimension(0,0)); setMinimumSize(new Dimension(0,0)); } };
        }
    }
}
