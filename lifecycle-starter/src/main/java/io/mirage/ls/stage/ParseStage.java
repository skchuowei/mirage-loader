package io.mirage.ls.stage;

import io.mirage.ls.LifecycleContext;
import io.mirage.ls.Stage;
import io.mirage.ls.StageOutcome;

/**
 * S2 解析（LS-Interfaces.md §7；语义见 MG-Lifecycle.md S2）。
 *
 * <p>phase 2 迁入的 Launcher 逻辑：manifest/entrypoint 解析胶水
 * （核心解析在 Loader/ModDiscoverer，只搬 Launcher 侧调用）。
 * phase 3 起 AG 转译（MTN/MTF/MBD）经 ModuleHandle contributor 挂进本阶段。
 */
public final class ParseStage implements Stage {

    @Override
    public String id() {
        return "S2";
    }

    @Override
    public String name() {
        return "Parse";
    }

    @Override
    public StageOutcome execute(LifecycleContext ctx) {
        return StageOutcome.skipped("phase-2 迁移未完成（LS-Interfaces §7）");
    }
}
