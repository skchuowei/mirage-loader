package io.mirage.ls.stage;

import io.mirage.ls.LifecycleContext;
import io.mirage.ls.Stage;
import io.mirage.ls.StageOutcome;

/**
 * S1 发现（LS-Interfaces.md §7；语义见 MG-Lifecycle.md S1）。
 *
 * <p>phase 2 迁入的 Launcher 逻辑：{@code new Loader().discoverMods(...)}
 * 及 mods 计数日志。迁移前本类不在活动 pipeline 中，接线即跳过，防止改变行为。
 */
public final class DiscoveryStage implements Stage {

    @Override
    public String id() {
        return "S1";
    }

    @Override
    public String name() {
        return "Discovery";
    }

    @Override
    public StageOutcome execute(LifecycleContext ctx) {
        return StageOutcome.skipped("phase-2 迁移未完成（LS-Interfaces §7）");
    }
}
