package io.mirage.ls;

import io.mirage.api.log.MirageLog;

import io.mirage.ls.stage.DiscoveryStage;
import io.mirage.ls.stage.EventBusStage;
import io.mirage.ls.stage.LoadedStage;
import io.mirage.ls.stage.ModInitStage;
import io.mirage.ls.stage.ParseStage;
import io.mirage.ls.stage.RegistryMergeStage;
import io.mirage.ls.stage.TransformStage;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Lifecycle Starter (LS) — 唯一调度入口（LS-Interfaces.md，2026-10-24 接口冻结，
 * v0.2 随 phase 2 迁移修订）。
 *
 * <p>phase 2（本轮）：解析启动周期（C1/C2/C3/C4）与全部环境量，装配 S1–S7  pipeline 并
 * 交 {@link LifecycleScheduler} 串行执行。S1–S7 的真实逻辑已从 Launcher 迁入
 * {@code io.mirage.ls.stage} 各类；启动脚本仍进 {@code io.mirage.launch.Launcher}，
 * 由它（bootstrap 后）单向委托本类（LS-Interfaces §9 反递归约束）。
 *
 * <p>默认周期：{@link LifecycleMode#C2 C2}（兼容现有行为，用户无感知）。
 */
public final class LifecycleStarter {

    /** 缺省周期（兼容现有行为）。 */
    public static final String DEFAULT_MODE = "C2";

    /** 加载器版本（与 Launcher.VERSION 一致；LS 侧自持有防模块循环依赖）。 */
    public static final String VERSION = "0.4.0";

    /** 目标 MC 版本（MidCache key / 早期窗口同源）。 */
    public static final String MC_VERSION = "26.2";

    public static void main(String[] args) {
        // [LS] mode= 是计划文档冻结的冒烟关键字，格式不得变
        LifecycleMode mode = LifecycleMode.parse(args);
        MirageLog.LS.raw("[LS] mode=" + mode.logName());

        // C4 自定义周期：加载 mirage.ls.json（--lifecycle-config=<file> 可覆盖路径）
        LsConfig config = null;
        if (mode == LifecycleMode.CUSTOM) {
            Path configPath = resolveConfigPath(args);
            if (!Files.isRegularFile(configPath)) {
                MirageLog.LS.fatal("C4 自定义周期需要配置文件: " + configPath
                        + "（可用 --lifecycle-config=<file> 显式指定）");
                System.exit(1);
                return;
            }
            config = LsConfig.load(configPath); // 内部校验失败即 FATAL
            MirageLog.LS.raw("[LS] config=" + configPath.toAbsolutePath());
            if (!config.agModules().isEmpty()) {
                MirageLog.LS.raw("[LS] AG 启用集 = " + config.agModules()
                        + "（ModuleHandle 接入为 phase 3，本期仅登记）");
            }
        }

        LifecycleContext ctx = new LifecycleContext(args, mode, config);
        MirageLog.LS.raw("[LS] cycle=" + mode.describe());
        MirageLog.LS.raw("[LS] sandbox-record=" + ctx.sandboxMode()
                + " parseFailPolicy=" + ctx.failPolicy().logName()
                + (config != null && config.minCoverage() >= 0
                        ? " minCoverage=" + config.minCoverage() : ""));

        LifecycleScheduler scheduler = new LifecycleScheduler();
        assemble(scheduler, mode, config);
        scheduler.run(ctx);
    }

    /**
     * 装配 S1–S7 pipeline。四周期的阶段序列相同（计划文档：C1/C2/C3 = S1–S7 全集，
     * 差异在录制开关/失败策略/原生过滤，由各阶段读 ctx 决策）；C4 可按配置禁用
     * S2–S6 中的任意阶段（S1/S7 禁用于 LsConfig 校验期已被拒）。
     */
    private static void assemble(LifecycleScheduler scheduler, LifecycleMode mode, LsConfig config) {
        add(scheduler, new DiscoveryStage(), "S1", config);
        add(scheduler, new ParseStage(), "S2", config);
        add(scheduler, new TransformStage(), "S3", config);
        add(scheduler, new RegistryMergeStage(), "S4", config);
        add(scheduler, new EventBusStage(), "S5", config);
        add(scheduler, new ModInitStage(), "S6", config);
        add(scheduler, new LoadedStage(), "S7", config);
    }

    private static void add(LifecycleScheduler scheduler, Stage stage, String stageId, LsConfig config) {
        Boolean enabled = config == null ? null : config.stageEnabled(stageId);
        if (enabled != null && !enabled) {
            MirageLog.LS.raw("[LS] stage " + stageId + " " + stage.name()
                    + " 按 mirage.ls.json 配置禁用（enabled=false）");
            return;
        }
        scheduler.add(stage);
    }

    /** C4 配置文件路径：--lifecycle-config=<file> 优先，否则 <gameDir>/mirage.ls.json。 */
    private static Path resolveConfigPath(String[] args) {
        for (String a : args) {
            if (a.startsWith("--lifecycle-config=")) {
                return Path.of(a.substring("--lifecycle-config=".length()).trim());
            }
        }
        return LifecycleContext.resolveGameDir(args).resolve("mirage.ls.json");
    }

    private LifecycleStarter() {}
}
