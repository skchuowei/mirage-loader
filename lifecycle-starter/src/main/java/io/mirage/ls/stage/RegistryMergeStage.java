package io.mirage.ls.stage;

import io.mirage.ls.LifecycleContext;
import io.mirage.ls.Stage;
import io.mirage.ls.StageOutcome;

/**
 * S4 注册表合并（LS-Interfaces.md §7；语义见 MG-Lifecycle.md S4）。
 *
 * <p>phase 2 迁入的 Launcher 逻辑：SharedConstants/Bootstrap、解冻全量
 * MappedRegistry、DeferredRegister nativeBackend、flush、BlockState 修补、
 * 重新冻结。注意 MG-Lifecycle S4 要求单线程按加载图顺序，禁止并行。
 */
public final class RegistryMergeStage implements Stage {

    @Override
    public String id() {
        return "S4";
    }

    @Override
    public String name() {
        return "RegistryMerge";
    }

    @Override
    public StageOutcome execute(LifecycleContext ctx) {
        return StageOutcome.skipped("phase-2 迁移未完成（LS-Interfaces §7）");
    }
}
