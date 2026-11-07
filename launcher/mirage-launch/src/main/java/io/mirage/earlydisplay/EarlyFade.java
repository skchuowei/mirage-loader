package io.mirage.earlydisplay;

/**
 * 早期窗口交接期的「仪表盘 → 纯红背景」淡出状态机。
 *
 * <p>用户实机反馈：早期窗口（满屏调试仪表盘）在交接时是被 Minecraft 自己的 Mojang
 * 闪屏「硬切」覆盖的——最后一帧还是密集仪表盘，下一帧直接变成居中 Logo，衔接生硬。
 * 这里把交接前的最后一程改成：渲染线程每帧在仪表盘上叠一层随 alpha 增长的背景红，
 * 直到纯红，再交还窗口句柄。由于背景红与 Minecraft 闪屏背景同色，硬切就变成了
 * 平滑溶解。
 *
 * <p>纯逻辑、不碰 GL，可离线单元测试（见 scripts/verify_earlyfade.ps1）。
 */
final class EarlyFade {

    private double alpha = 0.0;
    private boolean done = false;
    private final double step;

    /**
     * @param totalMs 淡出总时长（毫秒）
     * @param fps     渲染线程帧率（决定每帧步进）
     */
    EarlyFade(float totalMs, int fps) {
        double frames = Math.max(1.0, (totalMs / 1000.0) * fps);
        this.step = 1.0 / frames;
    }

    /** 推进一步淡出；返回是否已淡出完成。幂等：完成后调用仍是 true。 */
    boolean advance() {
        if (done) return true;
        double next = alpha + step;
        if (next >= 1.0) {
            alpha = 1.0;
            done = true;
        } else {
            alpha = next;
        }
        return done;
    }

    float alpha() {
        return (float) alpha;
    }

    boolean done() {
        return done;
    }

    /**
     * 叠加层颜色：背景红 + 随 alpha 增长的透明度（0xAABBGGRR，与 EarlyGl 一致）。
     * alpha=0 全透明（不影响仪表盘），alpha=1 即纯背景红（完全覆盖仪表盘）。
     */
    static int overlayColour(float alpha, int backgroundPacked) {
        int a = Math.round(Math.min(1f, Math.max(0f, alpha)) * 255f);
        return (a << 24) | (backgroundPacked & 0x00FFFFFF);
    }
}
