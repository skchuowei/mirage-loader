package io.mirage.ls.stage;

import io.mirage.ls.LifecycleContext;
import io.mirage.ls.Stage;
import io.mirage.ls.StageOutcome;

/**
 * S4 注册表合并（LS-Interfaces.md §7；语义见 MG-Lifecycle.md S4）。
 *
 * <p>phase 2 迁入的 Launcher 逻辑：Bootstrap.bootStrap、反射解冻全量 MappedRegistry、
 * 装配 DeferredRegister hook + nativeBackend（开窗）。窗口关闭（BlockState 修补 +
 * 重新冻结）在 S6 尾部执行——拆分前 Launcher 同样排在 mod 初始化之后，
 * 保序迁移不改变注册表可见性时序（详见 {@link RegistryWindow} 类注释）。
 *
 * <p>MG-Lifecycle S4 要求单线程按加载图顺序，禁止并行；调度器串行执行天然满足。
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
        return StageSupport.guard(() -> {
            RegistryWindow window = RegistryWindow.open(ctx);
            ctx.properties().put("S4.registryWindow", window);
        });
    }
}
