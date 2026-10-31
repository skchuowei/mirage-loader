package io.mirage.earlydisplay;

import org.lwjgl.BufferUtils;
import org.lwjgl.stb.STBTTAlignedQuad;
import org.lwjgl.stb.STBTTFontinfo;
import org.lwjgl.stb.STBTTPackContext;
import org.lwjgl.stb.STBTTPackRange;
import org.lwjgl.stb.STBTTPackedchar;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static org.lwjgl.opengl.GL32C.*;
import static org.lwjgl.stb.STBTruetype.*;
import static org.lwjgl.system.MemoryUtil.NULL;

/**
 * SimpleFont 的移植：STB 打包 Monocraft 24px 到 256x128 R8 图集，
 * 用 {@link EarlyGl} 画文本四边形。
 */
final class EarlyFont {

    private static final int GLYPH_COUNT = 127 - 32;
    private static final int TEXTURE_WIDTH = 256;
    private static final int TEXTURE_HEIGHT = 128;
    private static final int FONT_SIZE = 24;

    private final int lineSpacing;
    private final int descent;
    private final Glyph[] glyphs;

    private record Glyph(int charWidth, int x0, int y0, int x1, int y1,
                         float u0, float v0, float u1, float v1) {
    }

    EarlyFont(String resource, int textureUnit) {
        ByteBuffer fontData = readResource(resource);
        STBTTFontinfo info = STBTTFontinfo.create();
        if (!stbtt_InitFont(info, fontData)) {
            throw new IllegalStateException("字体初始化失败: " + resource);
        }

        float[] ascent = new float[1];
        float[] descent = new float[1];
        float[] lineGap = new float[1];
        stbtt_GetScaledFontVMetrics(fontData, 0, FONT_SIZE, ascent, descent, lineGap);
        this.lineSpacing = (int) (ascent[0] - descent[0] + lineGap[0]);
        this.descent = (int) Math.floor(descent[0]);

        int textureId = glGenTextures();
        glActiveTexture(GL_TEXTURE0 + textureUnit);
        glBindTexture(GL_TEXTURE_2D, textureId);
        glyphs = new Glyph[GLYPH_COUNT];
        try (STBTTPackedchar.Buffer packedChars = STBTTPackedchar.malloc(GLYPH_COUNT)) {
            ByteBuffer bitmap = BufferUtils.createByteBuffer(TEXTURE_WIDTH * TEXTURE_HEIGHT);
            try (STBTTPackRange.Buffer packRanges = STBTTPackRange.malloc(1);
                 STBTTPackRange packRange = STBTTPackRange.malloc()) {
                packRanges.put(packRange.set(FONT_SIZE, 32, null, GLYPH_COUNT, packedChars,
                        (byte) 1, (byte) 1));
                packRanges.flip();
                try (STBTTPackContext pc = STBTTPackContext.malloc()) {
                    stbtt_PackBegin(pc, bitmap, TEXTURE_WIDTH, TEXTURE_HEIGHT, 0, 1, NULL);
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
    }

    int lineSpacing() {
        return lineSpacing;
    }

    int descent() {
        return descent;
    }

    int stringWidth(String text) {
        byte[] bytes = text.getBytes(StandardCharsets.US_ASCII);
        int width = 0;
        for (byte b : bytes) {
            if (b == '\n' || b == '\t') continue;
            if (b == ' ') {
                width += glyphs[0].charWidth();
            } else if (b > 32 && b - 32 < GLYPH_COUNT) {
                width += glyphs[b - 32].charWidth();
            }
        }
        return width;
    }

    /** 以设计坐标 (x,y) 为左上起点画一行文本，按 (sx,sy) 拉伸到帧缓冲像素。 */
    void drawText(EarlyGl gl, String text, int x, int y, int colour, float sx, float sy) {
        int cursor = x;
        for (byte b : text.getBytes(StandardCharsets.US_ASCII)) {
            if (b == '\n' || b == '\t') continue;
            if (b == ' ') {
                cursor += glyphs[0].charWidth();
                continue;
            }
            if (b <= 32 || b - 32 >= GLYPH_COUNT) continue;
            Glyph g = glyphs[b - 32];
            gl.quad((cursor + g.x0()) * sx, (cursor + g.x1()) * sx,
                    (y + g.y0()) * sy, (y + g.y1()) * sy,
                    g.u0(), g.u1(), g.v0(), g.v1(), colour);
            cursor += g.charWidth();
        }
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
}
