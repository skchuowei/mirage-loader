package io.mirage.ls.stage;

import io.mirage.ls.LifecycleContext;
import io.mirage.ls.Stage;
import io.mirage.ls.StageOutcome;

/**
 * S5 事件总线初始化（LS-Interfaces.md §7；语义见 MG-Lifecycle.md S5）。
 *
 * <p>phase 2 迁入的 Launcher 逻辑：EventBus 创建、Mirage.bootstrap 公共 API
 * 门面、mod 实例 register（subscribe 写入按加载图顺序串行）。
 */
public final class EventBusStage implements Stage {

    @Override
    public String id() {
        return "S5";
    }

    @Override
    public String name() {
        return "EventBusInit";
    }

    @Override
    public StageOutcome execute(LifecycleContext ctx) {
        return StageOutcome.skipped("phase-2 迁移未完成（LS-Interfaces §7）");
    }
}
