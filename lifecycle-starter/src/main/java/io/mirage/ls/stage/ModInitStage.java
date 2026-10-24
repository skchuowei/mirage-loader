package io.mirage.ls.stage;

import io.mirage.ls.LifecycleContext;
import io.mirage.ls.Stage;
import io.mirage.ls.StageOutcome;

/**
 * S6 模组初始化（LS-Interfaces.md §7；语义见 MG-Lifecycle.md S6）。
 *
 * <p>phase 2 迁入的 Launcher 逻辑：entrypoint 实例化循环、生命周期事件
 * （Construct/PreInit/Init/PostInit）、NeoForge 生命周期桥接
 * （FMLConstructModEvent/FMLCommonSetupEvent）。MG-Lifecycle S6 要求严格串行。
 */
public final class ModInitStage implements Stage {

    @Override
    public String id() {
        return "S6";
    }

    @Override
    public String name() {
        return "ModInit";
    }

    @Override
    public StageOutcome execute(LifecycleContext ctx) {
        return StageOutcome.skipped("phase-2 迁移未完成（LS-Interfaces §7）");
    }
}
