package io.mirage.ls.stage;

import io.mirage.ls.LifecycleContext;
import io.mirage.ls.Stage;
import io.mirage.ls.StageOutcome;

/**
 * S3 字节码转换（LS-Interfaces.md §7；语义见 MG-Lifecycle.md S3）。
 *
 * <p>phase 2 迁入的 Launcher 逻辑：MirageClassLoader 创建、mod jar 挂载、
 * mixin config 扫描、MirageMixinBootstrap.init、AppClassLoader addURL。
 */
public final class TransformStage implements Stage {

    @Override
    public String id() {
        return "S3";
    }

    @Override
    public String name() {
        return "BytecodeTransform";
    }

    @Override
    public StageOutcome execute(LifecycleContext ctx) {
        return StageOutcome.skipped("phase-2 迁移未完成（LS-Interfaces §7）");
    }
}
