package io.mirage.ls;

/**
 * Lifecycle Starter (LS) — 唯一入口（LS-Interfaces.md，2026-10-24 接口冻结）。
 *
 * <p>phase 1（本轮）：解析 --lifecycle-mode 后经调度器执行单一
 * {@link LegacyBootStage}（整体委托现有 Launcher），保证行为 100% 不变。
 * 启动脚本仍进 io.mirage.launch.Launcher，LS 是平行入口。
 *
 * <p>phase 2（Launcher 逻辑迁移后）：pipeline 换成
 * stage.DiscoveryStage..LoadedStage（S1–S7），删除 LegacyBootStage，
 * Launcher 降级为 thin wrapper 单向委托本类（脚本不变）。
 *
 * <p>默认模式：transpile（兼容现有行为）。
 */
public final class LifecycleStarter {

    public static final String DEFAULT_MODE = "transpile";

    public static void main(String[] args) {
        LifecycleMode mode = LifecycleMode.parse(args);
        // [LS] mode= 是计划文档冻结的冒烟关键字，格式不得变
        System.out.println("[LS] mode=" + mode.logName());

        LifecycleContext ctx = new LifecycleContext(args, mode);
        LifecycleScheduler scheduler = new LifecycleScheduler();
        // phase 1：S1–S7 逻辑仍在 Launcher 单体内，整体委托（LS-Interfaces §6）。
        // phase 2 替换为 stage.DiscoveryStage..LoadedStage（io.mirage.ls.stage 包，
        // 当前为占位骨架，不在活动 pipeline 中）。
        scheduler.add(new LegacyBootStage());
        scheduler.run(ctx);
    }

    private LifecycleStarter() {}
}
