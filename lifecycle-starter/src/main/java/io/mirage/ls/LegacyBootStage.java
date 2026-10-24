package io.mirage.ls;

import io.mirage.launch.Launcher;

/**
 * phase 1 专用过渡阶段（LS-Interfaces.md §6）。
 *
 * <p>S1–S7 的全部逻辑目前仍在 Launcher 单体内；本阶段整体委托
 * {@link Launcher#main}，保证 LS 接入后行为 100% 不变。
 *
 * <p>正常路径它永不返回（Launcher 内部移交 MC 主循环，fatal 时自行 System.exit）；
 * 若返回即为异常路径，产出 FATAL。
 *
 * <p><b>phase 2 迁移完成后删除本类</b>，并在 LS-Interfaces.md 登记删除 commit。
 */
public final class LegacyBootStage implements Stage {

    @Override
    public String id() {
        return "legacy";
    }

    @Override
    public String name() {
        return "Legacy Launcher Boot (S1-S7)";
    }

    @Override
    public StageOutcome execute(LifecycleContext ctx) {
        Launcher.main(ctx.args());
        return StageOutcome.fatal("Launcher.main 意外返回（正常应移交 MC 主循环或 exit）");
    }
}
