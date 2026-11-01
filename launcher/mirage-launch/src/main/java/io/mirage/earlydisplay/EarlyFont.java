package io.mirage.earlydisplay;

import io.mirage.api.log.MirageLog;
import org.lwjgl.BufferUtils;
import org.lwjgl.stb.STBTTAlignedQuad;
import org.lwjgl.stb.STBTTFontinfo;
import org.lwjgl.stb.STBTTPackContext;
import org.lwjgl.stb.STBTTPackRange;
import org.lwjgl.stb.STBTTPackedchar;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.lwjgl.opengl.GL32C.*;
import static org.lwjgl.stb.STBTruetype.*;
import static org.lwjgl.system.MemoryUtil.NULL;

/**
 * SimpleFont 的移植：STB 打包 Monocraft 到图集（ASCII），并额外打包一份系统
 * 中文字体到第二个图集（CJK，texture unit + 1），使早期窗口也能显示中文。
 *
 * <p>两窗口都用 {@code io.mirage.loader.MirageDebug.LOADING_CJK_SOURCE} 作为汉字全集，
 * 保证早期窗口与原生 MC 窗口的字形一致。若系统找不到中文字体则优雅降级：CJK 字符
 * 不绘制（仅按字宽占位），不影响 ASCII 与界面其余部分。
 *
 * <p>绘制分两遍：第一遍只画 ASCII（CJK 按字宽前进但不画），第二遍只画 CJK。
 * 这样两种字形各用各自绑定的纹理单元，避免在一次批处理里切换纹理。
 */
final class EarlyFont {

    private static final int GLYPH_COUNT = 127 - 32;
    private static final int TEXTURE_WIDTH = 256;
    private static final int TEXTURE_HEIGHT = 128;
    /** 24px 是布局基准（ForgeStyleLayout 的墨迹 +9..+26 / lineSpacing=25 均按它实测推导），不得改小。 */
    private static final int FONT_SIZE = 24;
    private static final int CJK_TEX_W = 1024;
    private static final int CJK_TEX_H = 1024;

    private final int lineSpacing;
    private final int descent;
    private final Glyph[] glyphs;
    private final Map<Integer, Glyph> cjk = new java.util.HashMap<>();

    private final int texUnit;
    private final int cjkUnit;
    private final boolean cjkReady;

    private record Glyph(int charWidth, int x0, int y0, int x1, int y1,
                         float u0, float v0, float u1, float v1) {
    }

    EarlyFont(String asciiResource, int textureUnit, String cjkPath, String cjkSource) {
        this.texUnit = textureUnit;
        this.cjkUnit = textureUnit + 1;

        ByteBuffer fontData = readResource(asciiResource);
        STBTTFontinfo info = STBTTFontinfo.create();
        if (!stbtt_InitFont(info, fontData)) {
            throw new IllegalStateException("字体初始化失败: " + asciiResource);
        }

        float[] ascent = new float[1];
        float[] descent = new float[1];
        float[] lineGap = new float[1];
        stbtt_GetScaledFontVMetrics(fontData, 0, FONT_SIZE, ascent, descent, lineGap);
        this.lineSpacing = (int) (ascent[0] - descent[0] + lineGap[0]);
        this.descent = (int) Math.floor(descent[0]);

        int texId = glGenTextures();
        glActiveTexture(GL_TEXTURE0 + texUnit);
        glBindTexture(GL_TEXTURE_2D, texId);
        glyphs = new Glyph[GLYPH_COUNT];
        try (STBTTPackedchar.Buffer packedChars = STBTTPackedchar.malloc(GLYPH_COUNT)) {
            ByteBuffer bitmap = BufferUtils.createByteBuffer(TEXTURE_WIDTH * TEXTURE_HEIGHT);
            try (STBTTPackRange.Buffer packRanges = STBTTPackRange.malloc(1);
                 STBTTPackRange packRange = STBTTPackRange.malloc()) {
                packRanges.put(packRange.set(FONT_SIZE, 32, null, GLYPH_COUNT, packedChars,
                        (byte) 1, (byte) 1));
                packRanges.flip();
                try (STBTTPackContext pc = STBTTPackContext.malloc()) {
                    if (!stbtt_PackBegin(pc, bitmap, TEXTURE_WIDTH, TEXTURE_HEIGHT, 0, 1, NULL)) {
                        throw new IllegalStateException("ASCII 字形图集创建失败");
                    }
                    stbtt_PackSetOversampling(pc, 1, 1);
                    stbtt_PackSetSkipMissingCodepoints(pc, true);
                    stbtt_PackFontRanges(pc, fontData, 0, packRanges);
                    stbtt_PackEnd(pc);
                    glTexImage2D(GL_TEXTURE_2D, 0, GL_RED, TEXTURE_WIDTH, TEXTURE_HEIGHT, 0,
                            GL_RED, GL_UNSIGNED_BYTE, bitmap);
                    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
                    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
                    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
                    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
                }
            }

            try (STBTTAlignedQuad q = STBTTAlignedQuad.malloc()) {
                float[] x = new float[1];
                float[] y = new float[1];
                for (int i = 0; i < GLYPH_COUNT; i++) {
                    x[0] = 0f;
                    y[0] = FONT_SIZE;
                    stbtt_GetPackedQuad(packedChars, TEXTURE_WIDTH, TEXTURE_HEIGHT, i, x, y, q, true);
                    glyphs[i] = new Glyph((int) x[0],
                            (int) q.x0(), (int) q.y0(), (int) q.x1(), (int) q.y1(),
                            q.s0(), q.t0(), q.s1(), q.t1());
                }
            }
        }
        glActiveTexture(GL_TEXTURE0);

        // ---- 中文字体（系统字体，打包进第二个图集） ----
        boolean ok = false;
        if (cjkPath != null && !cjkPath.isEmpty() && cjkSource != null && !cjkSource.isEmpty()) {
            try {
                ByteBuffer cjkData = readFile(cjkPath);
                // 系统中文多是 TTC 集合（msyh.ttc 含 2 个字体），这里取第 0 个。
                //
                // ⚠ stbtt_PackFontRanges 的第三参是 **font_index（集合内索引）**，不是字节偏移：
                // 它内部自己会再调 stbtt_GetFontOffsetForIndex(fontdata, font_index)。
                // 传字节偏移（32）→ 索引 32 ≥ numFonts(2) → GetFontOffsetForIndex 返回 -1
                // → STB 拿 data-1 去解析表头 → EXCEPTION_ACCESS_VIOLATION 崩在 lwjgl_stb.dll
                //（hs_err 里可见 0xFFFFFFFF 与 data+0x100000003 的越界读）。
                // 而 stbtt_InitFont 的第三参**确实是**字节偏移，两个别混。
                final int cjkFontIndex = 0;
                STBTTFontinfo cinfo = STBTTFontinfo.create();
                int offset = stbtt_GetFontOffsetForIndex(cjkData, cjkFontIndex);
                if (offset >= 0 && stbtt_InitFont(cinfo, cjkData, offset)) {
                    // 去重后的码位表：**一次** PackFontRanges 全部打包。
                    // 逐字打包（每字一个 PackRange + Packedchar 缓冲）在 600 字规模下
                    // 要分配 1200 个 native 缓冲、跑 600 次打包，纯属浪费。
                    java.util.List<Integer> cps = new java.util.ArrayList<>();
                    Set<Integer> seen = new HashSet<>();
                    for (int i = 0; i < cjkSource.length(); i++) {
                        int cp = cjkSource.charAt(i);
                        if (cp <= 126 || !seen.add(cp)) continue;
                        cps.add(cp);
                    }
                    if (!cps.isEmpty()) {
                        int n = cps.size();
                        IntBuffer cpBuf = BufferUtils.createIntBuffer(n);
                        for (int k = 0; k < n; k++) cpBuf.put(cps.get(k));
                        cpBuf.flip();

                        int ctex = glGenTextures();
                        glActiveTexture(GL_TEXTURE0 + cjkUnit);
                        glBindTexture(GL_TEXTURE_2D, ctex);
                        ByteBuffer cbitmap = BufferUtils.createByteBuffer(CJK_TEX_W * CJK_TEX_H);
                        boolean packedOk = false;
                        try (STBTTPackedchar.Buffer chars = STBTTPackedchar.malloc(n);
                             STBTTPackRange.Buffer rngs = STBTTPackRange.malloc(1);
                             STBTTPackRange rng = STBTTPackRange.malloc();
                             STBTTPackContext pc = STBTTPackContext.malloc()) {
                            rngs.put(rng.set(FONT_SIZE, 0, cpBuf, n, chars, (byte) 1, (byte) 1));
                            rngs.flip();
                            if (!stbtt_PackBegin(pc, cbitmap, CJK_TEX_W, CJK_TEX_H, 0, 1, NULL)) {
                                throw new IllegalStateException("中文字形图集创建失败");
                            }
                            stbtt_PackSetOversampling(pc, 1, 1);
                            stbtt_PackSetSkipMissingCodepoints(pc, true);
                            packedOk = stbtt_PackFontRanges(pc, cjkData, cjkFontIndex, rngs);
                            stbtt_PackEnd(pc);
                            if (!packedOk) {
                                MirageLog.MIRAGE.warn("中文字体打包失败（font_index=" + cjkFontIndex
                                        + "，字形数 " + n + "），早期窗口中文将显示为空白");
                            }
                            glTexImage2D(GL_TEXTURE_2D, 0, GL_RED, CJK_TEX_W, CJK_TEX_H, 0,
                                    GL_RED, GL_UNSIGNED_BYTE, cbitmap);
                            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
                            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
                            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
                            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);

                            try (STBTTAlignedQuad q = STBTTAlignedQuad.malloc()) {
                                float[] x = new float[1];
                                float[] y = new float[1];
                                for (int k = 0; k < n; k++) {
                                    x[0] = 0f;
                                    y[0] = FONT_SIZE;
                                    stbtt_GetPackedQuad(chars, CJK_TEX_W, CJK_TEX_H, k, x, y, q, true);
                                    // 图集塞满 / 字体缺字 → 空四边形，不登记（绘制时按字宽留白）
                                    if ((int) q.x1() <= (int) q.x0() || (int) q.y1() <= (int) q.y0()) {
                                        continue;
                                    }
                                    cjk.put(cps.get(k), new Glyph((int) x[0],
                                            (int) q.x0(), (int) q.y0(), (int) q.x1(), (int) q.y1(),
                                            q.s0(), q.t0(), q.s1(), q.t1()));
                                }
                            }
                        }
                        ok = packedOk;
                    }
                }
            } catch (Throwable t) {
                // 中文字体不可用：降级为只画 ASCII，不致命。
                // 但必须留痕——否则中文整片空白时无从判断是「没打包」还是「没字」。
                MirageLog.MIRAGE.warn("中文字体加载失败，早期窗口降级为仅 ASCII（" + cjkPath + "）", t);
            }
        }
        this.cjkReady = ok;
        glActiveTexture(GL_TEXTURE0);
    }

    int lineSpacing() {
        return lineSpacing;
    }

    int descent() {
        return descent;
    }

    int stringWidth(String text) {
        int width = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c <= 126) {
                width += monoAdvance(c);
            } else if (cjkReady) {
                Glyph g = cjk.get((int) c);
                width += (g != null) ? g.charWidth() : FONT_SIZE;
            } else {
                width += FONT_SIZE;
            }
        }
        return width;
    }

    /** 以设计坐标 (x,y) 为基线起点画一行文本，按 (sx,sy) 拉伸到帧缓冲像素。 */
    void drawText(EarlyGl gl, String text, int x, int y, int colour, float sx, float sy) {
        // 第一遍：ASCII（CJK 仅占位前进，不绘制）
        gl.begin();
        int cursor = x;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c <= 126) {
                if (c == '\n' || c == '\t') continue;
                if (c == ' ') {
                    cursor += glyphs[0].charWidth();
                    continue;
                }
                if (c > 32 && c - 32 < GLYPH_COUNT) {
                    Glyph g = glyphs[c - 32];
                    gl.quad((cursor + g.x0()) * sx, (cursor + g.x1()) * sx,
                            (y + g.y0()) * sy, (y + g.y1()) * sy,
                            g.u0(), g.u1(), g.v0(), g.v1(), colour);
                    cursor += g.charWidth();
                }
            } else {
                cursor += cjkAdvance(c);
            }
        }
        gl.draw(EarlyGl.TYPE_FONT, texUnit);

        // 第二遍：CJK
        if (cjkReady) {
            gl.begin();
            cursor = x;
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c > 126) {
                    Glyph g = cjk.get((int) c);
                    if (g != null) {
                        gl.quad((cursor + g.x0()) * sx, (cursor + g.x1()) * sx,
                                (y + g.y0()) * sy, (y + g.y1()) * sy,
                                g.u0(), g.u1(), g.v0(), g.v1(), colour);
                        cursor += g.charWidth();
                    } else {
                        cursor += FONT_SIZE;
                    }
                } else {
                    cursor += monoAdvance(c);
                }
            }
            gl.draw(EarlyGl.TYPE_FONT, cjkUnit);
        }
    }

    private int monoAdvance(char c) {
        if (c == ' ' || c == '\n' || c == '\t') return glyphs[0].charWidth();
        if (c > 32 && c - 32 < GLYPH_COUNT) return glyphs[c - 32].charWidth();
        return glyphs[0].charWidth();
    }

    private int cjkAdvance(char c) {
        if (!cjkReady) return FONT_SIZE;
        Glyph g = cjk.get((int) c);
        return (g != null) ? g.charWidth() : FONT_SIZE;
    }

    private static ByteBuffer readResource(String name) {
        try (InputStream in = EarlyFont.class.getResourceAsStream(name)) {
            if (in == null) throw new IllegalStateException("找不到字体资源: " + name);
            byte[] data = in.readAllBytes();
            ByteBuffer buf = BufferUtils.createByteBuffer(data.length);
            buf.put(data).flip();
            return buf;
        } catch (Exception e) {
            throw new IllegalStateException("读取字体资源失败: " + name, e);
        }
    }

    private static ByteBuffer readFile(String path) throws Exception {
        byte[] data = Files.readAllBytes(Paths.get(path));
        ByteBuffer buf = BufferUtils.createByteBuffer(data.length);
        buf.put(data).flip();
        return buf;
    }
}
