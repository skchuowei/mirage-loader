package io.mirage.loader;

/**
 * 标题画面首次出现时「仪表盘 → 标题画面」的交叉溶解状态机。
 *
 * <p><b>为什么需要它</b>：加载遮罩（{@code LoadingOverlay}）被 Minecraft 移除的那一刻，
 * 仪表盘是<b>满不透明</b>直接消失的——逐帧取证实测就是一帧硬切（21.63s 还是满屏仪表盘，
 * 21.67s 已是 MC 自己的 Mojang Logo）。遮罩自身的淡出由 MC 的 {@code fadeOutStart} 控制，
 * 我们只能靠反射去读它的私有字段，跨 classloader 读不到时会静默回退成「不透明」，
 * 所以不能把收尾押在那条路径上。
 *
 * <p>于是把收尾挪到<b>我们自己确定会被调用</b>的标题画面上：标题画面出现的第一时间整屏
 * 盖住（与遮罩最后一帧完全一致，视觉连续），随后在 {@code fadeMs} 内线性淡出到 0，
 * 把「仪表盘」交叉溶解成 MC 自己的 Logo / 标题画面。
 *
 * <p>纯逻辑、不碰 GL 与 GuiGraphics，可离线单元测试（见 scripts/verify_exit_dissolve.ps1）。
 */
final class ExitDissolve {

    private final long fadeMs;
    private long startedAt = -1L;
    private boolean done;

    /**
     * @param fadeMs 溶解总时长（毫秒），必须 &gt; 0
     */
    ExitDissolve(long fadeMs) {
        this.fadeMs = Math.max(1L, fadeMs);
    }

    /**
     * 开始计时。幂等：只有第一次有效（一次会话只溶解一次）。
     *
     * @return 本次调用是否真的启动了溶解
     */
    boolean begin(long nowMs) {
        if (done || startedAt >= 0) return false;
        startedAt = nowMs;
        return true;
    }

    /** 是否已启动（含已完成）。 */
    boolean started() {
        return startedAt >= 0;
    }

    /**
     * 当前剩余不透明度 0..255：启动即 255（与遮罩最后一帧一致），线性降到 0。
     * 未启动时返回 0（表示「不要画」）。
     */
    int alpha255(long nowMs) {
        if (startedAt < 0) return 0;
        long t = nowMs - startedAt;
        if (t <= 0L) return 255;
        if (t >= fadeMs) {
            done = true;
            return 0;
        }
        return (int) Math.ceil((1.0 - (double) t / (double) fadeMs) * 255.0);
    }
}
