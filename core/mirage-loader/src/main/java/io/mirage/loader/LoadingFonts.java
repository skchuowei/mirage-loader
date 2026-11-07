package io.mirage.loader;

import java.io.File;
import java.util.Locale;

/**
 * 加载界面字体源：两个窗口 1:1 同源的<b>唯一出处</b>。
 *
 * <p>早期窗口 {@code EarlyFont} 用 STB 把 {@link #ASCII_RESOURCE} 打包成图集、
 * 主窗口 {@code ForgeStyleOverlay} 用 AWT 把同一份资源光栅化成矩形块；中文字体由
 * {@link #findCjkFont()} 给出同一个文件的绝对路径。两条渲染管线不同（GL 纹理 quad
 * vs AWT + fill），但字形必须来自同一份字体文件，否则窗口交接时会出现「字突然变了」
 * 的观感跳跃。把常量集中在这里，任一窗口想换字体都会被另一边拉回同一处。
 *
 * <p>本类纯 JDK，不碰 AWT / GL / LWJGL，可脱离 MC 单独编译，供离线回归直接引用。
 */
public final class LoadingFonts {

    /** 打包在 fat jar 根的等宽字体；早期窗口与主窗口都读这一份。 */
    public static final String ASCII_RESOURCE = "/Monocraft.ttf";

    /**
     * 中文字体候选，顺序与早期窗口历史实现一致（优先微软雅黑）。
     * 早期窗口用 STB 读文件、主窗口用 AWT 的 {@code Font.createFont} 读同一路径。
     */
    private static final String[] CJK_CANDIDATES = {
            "C:/Windows/Fonts/msyh.ttc",
            "C:/Windows/Fonts/msyhbd.ttc",
            "C:/Windows/Fonts/simsun.ttc",
            "C:/Windows/Fonts/simhei.ttf",
            "C:/Windows/Fonts/msjh.ttc",
            "/System/Library/Fonts/PingFang.ttc",
            "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc"
    };

    private LoadingFonts() {
    }

    /**
     * 返回第一个存在的中文字体文件绝对路径；都找不到时返回 {@code null}。
     * 返回 null 时两个窗口一起降级（早期窗口只画 ASCII、主窗口回退系统家族名），
     * 不会出现「一边有中文一边没有」的半套状态。
     */
    public static String findCjkFont() {
        for (String p : CJK_CANDIDATES) {
            if (new File(p).isFile()) return p;
        }
        File dir = new File("C:/Windows/Fonts");
        if (dir.isDirectory()) {
            String[] names = dir.list();
            if (names != null) {
                for (String name : names) {
                    String lower = name.toLowerCase(Locale.ROOT);
                    if (lower.endsWith(".ttc") || lower.endsWith(".ttf") || lower.endsWith(".otf")) {
                        if (lower.contains("msyh") || lower.contains("sim") || lower.contains("yahei")
                                || lower.contains("noto") || lower.contains("cjk") || lower.contains("hei")
                                || lower.contains("song") || lower.contains("pingfang")
                                || lower.contains("wenquan") || lower.contains("source")) {
                            return new File(dir, name).getAbsolutePath();
                        }
                    }
                }
            }
        }
        return null;
    }
}
