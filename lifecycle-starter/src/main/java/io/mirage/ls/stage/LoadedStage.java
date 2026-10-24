package io.mirage.ls.stage;

import io.mirage.ls.LifecycleContext;
import io.mirage.ls.Stage;
import io.mirage.ls.StageOutcome;

/**
 * S7 加载完成（LS-Interfaces.md §7；语义见 MG-Lifecycle.md S7）。
 *
 * <p>phase 2 迁入的 Launcher 逻辑：ClientStartingEvent、FMLClientSetupEvent、
 * 移交 net.minecraft.client.main.Main、MirageDebug.setLoaded。
 * 本阶段正常路径永不返回（MC 主循环阻塞在内）——LS-Interfaces §2 契约允许。
 */
public final class LoadedStage implements Stage {

    @Override
    public String id() {
        return "S7";
    }

    @Override
    public String name() {
        return "Loaded";
    }

    @Override
    public StageOutcome execute(LifecycleContext ctx) {
        return StageOutcome.skipped("phase-2 迁移未完成（LS-Interfaces §7）");
    }
}
