package io.mirage.earlydisplay;

import org.lwjgl.BufferUtils;

import java.nio.ByteBuffer;

import static org.lwjgl.opengl.GL32C.*;

/**
 * Forge earlydisplay 的极简移植版：一个 shader + 一个动态 VBO，够画
 * 纯色矩形、RGBA 贴图和 STB 字体图集。
 *
 * <p>与 Forge 的差别：不经过 FBO + blit，直接画默认帧缓冲，因此顶点
 * shader 里把 Y 翻转（屏幕坐标 y 向下）；四边形在 CPU 侧展开成三角形，
 * 省掉 IBO。
 */
final class EarlyGl {

    static final int TYPE_FONT = 0;
    static final int TYPE_TEXTURE = 1;
    static final int TYPE_BAR = 2;

    private static final int FLOATS_PER_VERTEX = 5;   // x,y,u,v + 1 个打包颜色
    private static final int BYTES_PER_VERTEX = 20;
    private static final int INITIAL_BYTES = 1 << 20;

    private int program;
    private int textureUniform;
    private int screenSizeUniform;
    private int renderTypeUniform;

    private int vao;
    private int vbo;

    private ByteBuffer buffer = BufferUtils.createByteBuffer(INITIAL_BYTES);
    private int vertexCount;

    void init() {
        int vertexShader = glCreateShader(GL_VERTEX_SHADER);
        int fragmentShader = glCreateShader(GL_FRAGMENT_SHADER);

        glShaderSource(fragmentShader, """
                #version 150 core
                uniform sampler2D tex;
                uniform int rendertype;
                in vec2 fTex;
                in vec4 fColour;
                out vec4 fragColor;

                void main() {
                    if (rendertype == 0)
                        fragColor = vec4(1, 1, 1, texture(tex, fTex).r) * fColour;
                    if (rendertype == 1)
                        fragColor = texture(tex, fTex) * fColour;
                    if (rendertype == 2)
                        fragColor = fColour;
                }
                """);
        glShaderSource(vertexShader, """
                #version 150 core
                in vec2 position;
                in vec2 tex;
                in vec4 colour;
                uniform vec2 screenSize;
                out vec2 fTex;
                out vec4 fColour;

                void main() {
                    fTex = tex;
                    fColour = colour;
                    // y 轴翻转：窗口坐标原点在左上角
                    gl_Position = vec4((position / screenSize) * vec2(2.0, -2.0) + vec2(-1.0, 1.0), 0.0, 1.0);
                }
                """);

        glCompileShader(vertexShader);
        if (glGetShaderi(vertexShader, GL_COMPILE_STATUS) == GL_FALSE) {
            throw new IllegalStateException("VertexShader 编译失败: " + glGetShaderInfoLog(vertexShader));
        }
        glCompileShader(fragmentShader);
        if (glGetShaderi(fragmentShader, GL_COMPILE_STATUS) == GL_FALSE) {
            throw new IllegalStateException("FragmentShader 编译失败: " + glGetShaderInfoLog(fragmentShader));
        }

        int prog = glCreateProgram();
        glBindAttribLocation(prog, 0, "position");
        glBindAttribLocation(prog, 1, "tex");
        glBindAttribLocation(prog, 2, "colour");
        glAttachShader(prog, vertexShader);
        glAttachShader(prog, fragmentShader);
        glLinkProgram(prog);
        if (glGetProgrami(prog, GL_LINK_STATUS) == GL_FALSE) {
            throw new IllegalStateException("ShaderProgram 链接失败: " + glGetProgramInfoLog(prog));
        }
        glDetachShader(prog, vertexShader);
        glDetachShader(prog, fragmentShader);
        glDeleteShader(vertexShader);
        glDeleteShader(fragmentShader);
        this.program = prog;
        textureUniform = glGetUniformLocation(prog, "tex");
        screenSizeUniform = glGetUniformLocation(prog, "screenSize");
        renderTypeUniform = glGetUniformLocation(prog, "rendertype");

        vao = glGenVertexArrays();
        vbo = glGenBuffers();
        glBindVertexArray(vao);
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(0, 2, GL_FLOAT, false, BYTES_PER_VERTEX, 0L);
        glEnableVertexAttribArray(1);
        glVertexAttribPointer(1, 2, GL_FLOAT, false, BYTES_PER_VERTEX, 8L);
        glEnableVertexAttribArray(2);
        glVertexAttribPointer(2, 4, GL_UNSIGNED_BYTE, true, BYTES_PER_VERTEX, 16L);
        glBindVertexArray(0);

        activate();
    }

    void activate() {
        glUseProgram(program);
    }

    void screenSize(int width, int height) {
        glUniform2f(screenSizeUniform, width, height);
    }

    void begin() {
        buffer.clear();
        vertexCount = 0;
    }

    /** 追加一个顶点；颜色为 0xAABBGGRR（与 Forge ColourScheme#packedint 一致）。 */
    void vertex(float x, float y, float u, float v, int colour) {
        ensureCapacity(BYTES_PER_VERTEX);
        buffer.putFloat(x);
        buffer.putFloat(y);
        buffer.putFloat(u);
        buffer.putFloat(v);
        buffer.putInt(colour);
        vertexCount++;
    }

    /** 追加一个四边形（展开成两个三角形）。 */
    void quad(float x0, float x1, float y0, float y1,
              float u0, float u1, float v0, float v1, int colour) {
        vertex(x0, y0, u0, v0, colour);
        vertex(x1, y0, u1, v0, colour);
        vertex(x0, y1, u0, v1, colour);
        vertex(x1, y0, u1, v0, colour);
        vertex(x1, y1, u1, v1, colour);
        vertex(x0, y1, u0, v1, colour);
    }

    void draw(int renderType, int textureUnit) {
        if (vertexCount == 0) return;
        glUniform1i(textureUniform, textureUnit);
        glUniform1i(renderTypeUniform, renderType);
        buffer.flip();
        glBindVertexArray(vao);
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferData(GL_ARRAY_BUFFER, buffer, GL_DYNAMIC_DRAW);
        glDrawArrays(GL_TRIANGLES, 0, vertexCount);
        glBindVertexArray(0);
    }

    private void ensureCapacity(int extraBytes) {
        if (buffer.remaining() >= extraBytes) return;
        int used = buffer.position();
        ByteBuffer bigger = BufferUtils.createByteBuffer(Math.max(buffer.capacity() * 2, used + extraBytes));
        buffer.flip();
        bigger.put(buffer);
        buffer = bigger;
    }

    int floatsPerVertex() {
        return FLOATS_PER_VERTEX;
    }
}
