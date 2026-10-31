package io.mirage.loader;

/**
 * Forge 式加载界面的布局常量（设计坐标 854×480）。
 *
 * <p><b>唯一基准是早期窗口</b> {@code io.mirage.earlydisplay.MirageEarlyWindow}
 * 的实际绘制代码，不是 Forge 官方 {@code RenderElement}。两者不同：早期窗口用
 * 「外框 → 内衬(inset 2) → 填充(inset 4)」三层，外框比 {@code BAR_WIDTH} 宽 8；
 * Forge 官方是 inset 2 三层、外框宽 8 但填充内缩 2。以早期窗口为准，两段界面才一致。
 *
 * <h2>纵向节奏的推导</h2>
 * 早期窗口的字体是 Monocraft 24px（STB 实测：{@code lineSpacing=25}、
 * {@code descent=-4}，字形墨迹在「文本左上 y + 9」到「y + 26」之间）：
 * <pre>
 *   进度条行距   = lineSpacing - descent + BAR_HEIGHT = 25 + 4 + 20 = 49
 *   标签→条      = lineSpacing - descent            = 25 + 4      = 29
 *   内存文字     = 10 + 18 = 28
 *   版本号       = 480 - 25 + (-4) - 10 = 441
 * </pre>
 *
 * <p>原生 MC 窗口用 MC 自己的 {@code Font}（行高 9 GUI 单位），所以另给一组
 * 「MC 字体顶部锚点」常量：早期窗口里的 y 是文本左上，MC 的字形单元顶比墨迹顶
 * 高约 1 像素，故 {@code -1}。
 */
public final class ForgeStyleLayout {

    /** 设计分辨率，与早期窗口一致。 */
    public static final int DESIGN_WIDTH = 854;
    public static final int DESIGN_HEIGHT = 480;

    /** 进度条名义宽度。 */
    public static final int BAR_WIDTH = 400;
    /** 进度条（含内存条）高度。 */
    public static final int BAR_HEIGHT = 20;

    /** 外框比 BAR_WIDTH 宽出的总量（左右各 4）。 */
    public static final int OUTER_EXPAND = 8;
    /** 内衬相对外框的内缩。 */
    public static final int INNER_INSET = 2;
    /** 填充块相对外框的内缩。 */
    public static final int FILL_INSET = 4;

    /** Forge 早期窗口红底（RGB）。 */
    public static final int FORGE_RED = 0xEF323D;
    /** 前景白（RGB）。 */
    public static final int WHITE = 0xFFFFFF;

    /** 内存条外框顶边。 */
    public static final int MEMORY_BAR_TOP = 10;
    /** 内存文字的「文本左上 y」（早期窗口语义）。 */
    public static final int MEMORY_TEXT_TOP = 28;

    /** 第一条进度条标签的「文本左上 y」。 */
    public static final int BAR_LABEL_TOP = 250;
    /** 两条进度条的行距（lineSpacing - descent + BAR_HEIGHT）。 */
    public static final int BAR_SPACING = 49;
    /** 标签左上 → 条顶边的距离（lineSpacing - descent）。 */
    public static final int LABEL_TO_BAR = 29;

    /** 版本号的「文本左上 y」。 */
    public static final int VERSION_TOP = 441;
    /** 版本号右边缘留白。 */
    public static final int VERSION_MARGIN = 10;

    /**
     * 早期窗口「文本左上 y」→ MC 字体顶部锚点的偏移。
     *
     * <p>早期窗口的 y 是<b>行盒顶部</b>，而 Monocraft 24px 的行盒顶到墨迹顶还有 9px 空白
     * （STB 实测字形 y0=9）；MC 的字体字形几乎贴着行盒顶（差 1px）。
     * 若直接照抄 y，文字会比早期窗口高出 8px，正好压到条上——这就是「UI 重叠」的来源。
     * 按墨迹对齐：{@code MC y = 早期窗口 y + 9 - 1}。
     */
    public static final int MC_TEXT_OFFSET = 8;

    private ForgeStyleLayout() {
    }

    /** 条（内存条 / 进度条）的左边界。 */
    public static int barLeft() {
        return (DESIGN_WIDTH - BAR_WIDTH) / 2;
    }

    /** 外框右边界：比 BAR_WIDTH 宽 OUTER_EXPAND。 */
    public static int barOuterRight() {
        return barLeft() + BAR_WIDTH + OUTER_EXPAND;
    }

    /** 内衬左 / 右边界。 */
    public static int barInnerLeft() {
        return barLeft() + INNER_INSET;
    }

    public static int barInnerRight() {
        return barOuterRight() - INNER_INSET;
    }

    /** 填充块左边界。 */
    public static int barFillLeft() {
        return barLeft() + FILL_INSET;
    }

    /** 填充块在给定进度处的右边界。 */
    public static int barFillRight(float fraction) {
        return Math.round(barFillLeft() + clamp01(fraction) * BAR_WIDTH);
    }

    /** 第 index 条进度条标签的「文本左上 y」。 */
    public static int labelTop(int index) {
        return BAR_LABEL_TOP + index * BAR_SPACING;
    }

    /** 第 index 条进度条外框的顶边。 */
    public static int barTop(int index) {
        return labelTop(index) + LABEL_TO_BAR;
    }

    /** 第 index 条进度条标签的 MC 字体顶部锚点。 */
    public static int labelTextY(int index) {
        return labelTop(index) + MC_TEXT_OFFSET;
    }

    /** 内存文字的 MC 字体顶部锚点。 */
    public static int memoryTextY() {
        return MEMORY_TEXT_TOP + MC_TEXT_OFFSET;
    }

    /** 版本号的 MC 字体顶部锚点。 */
    public static int versionTextY() {
        return VERSION_TOP + MC_TEXT_OFFSET;
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }
}
