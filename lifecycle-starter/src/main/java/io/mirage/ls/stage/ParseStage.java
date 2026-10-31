package io.mirage.ls.stage;

import io.mirage.api.log.MirageLog;

import io.mirage.api.Mirage;
import io.mirage.eventbus.EventBus;
import io.mirage.eventbus.EventBusHandleImpl;
import io.mirage.loader.ModContainer;
import io.mirage.loader.runtime.MirageProviderImpl;
import io.mirage.ls.LifecycleContext;
import io.mirage.ls.LifecycleStarter;
import io.mirage.ls.ParseFailPolicy;
import io.mirage.ls.Stage;
import io.mirage.ls.StageOutcome;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * S2 解析（LS-Interfaces.md §7；语义见 MG-Lifecycle.md S2）。
 *
 * <p>phase 2 实际迁入内容（保序决定，见 LS-Interfaces 附录 A）：
 * <ol>
 *   <li><b>公共 API 门面装配</b>：EventBus / EventBusHandleImpl / MirageProviderImpl /
 *       {@link Mirage#bootstrap} + NeoForge 配置目录。拆分前 Launcher 的时序位置就在
 *       S1 与 S3 之间，原样落入 S2——不把门面挪到 S5（MG-Lifecycle 的 S5 语义位），
 *       因为那会改变 mod 可观察到的 API 就绪时机（冒烟等价性优先）；</li>
 *   <li><b>解析失败策略消费</b>：按 {@link ParseFailPolicy} 处理「无法完全解析」的 mod。
 *       当前判定信号：<b>主类定位失败</b>（manifest.mainClass 与 fabric entrypoints 均为空）。
 *       其余判定标准（MBD 覆盖率 / MID 校验 / 依赖满足）随 AG 解耦（phase 3）接入。</li>
 * </ol>
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
        // 不用 StageSupport.guard：失败策略的 FATAL 结论需要作为 StageOutcome 直接返回
        // （guard 会把阶段体统一收敛成 success，吞掉 crash/abort 结论）。
        try {
            List<ModContainer> mods = StageSupport.get(ctx, "S1.mods");

            // ---- 初始化公共 API 门面（Mirage.* 从此可用）----
            EventBus eventBus = new EventBus();
            EventBusHandleImpl busHandle = new EventBusHandleImpl(eventBus);
            MirageProviderImpl provider = new MirageProviderImpl(
                    ctx.dist(), ctx.gameDir(), mods, busHandle, LifecycleStarter.VERSION);
            Mirage.bootstrap(provider);
            // P1.5：设置 NeoForge 配置目录 <gameDir>/config，供 ModLoadingContext 读取 .toml
            net.neoforged.fml.ModLoadingContext.setConfigDirectory(ctx.gameDir().resolve("config"));
            MirageLog.MIRAGE.info("公共 API 门面已就绪 (loader v" + LifecycleStarter.VERSION
                    + ", mods=" + mods.size() + ", dist=" + ctx.dist() + ")");

            ctx.properties().put("S2.eventBus", eventBus);
            ctx.properties().put("S2.provider", provider);

            return applyParseFailPolicy(ctx, mods);
        } catch (Throwable t) {
            MirageLog.MIRAGE.error("启动 Minecraft 时发生致命错误", t);
            return StageOutcome.fatal(MirageLog.summary(t));
        }
    }

    /**
     * ParseFailPolicy 消费：主类定位失败的 mod 按策略 skip / crash / confirm 处理。
     * 缺省（C1/C2 = skip）：WARN + 记入 {@code ctx.skippedMods()}，S6 据此跳过——
     * 相比拆分前「无声 continue」，这是原则 3 要求的显式失败。
     */
    private static StageOutcome applyParseFailPolicy(LifecycleContext ctx, List<ModContainer> mods) {
        ParseFailPolicy policy = ctx.failPolicy();
        List<ModContainer> unresolved = new ArrayList<>();
        for (ModContainer mod : mods) {
            if (ctx.skippedMods().contains(mod.manifest.id)) continue;
            if (ModEntrypoints.mainClasses(mod).isEmpty()
                    && ModEntrypoints.sideClasses(mod, ctx.dist()).isEmpty()) {
                unresolved.add(mod);
            }
        }
        if (unresolved.isEmpty()) {
            return StageOutcome.success();
        }
        MirageLog.LS.raw("[LS] ParseFailPolicy=" + policy.logName() + "：检测到 " + unresolved.size()
                + " 个无法完全解析的 mod（主类定位失败：无 @Mod 注解、无可用入口）");
        for (ModContainer m : unresolved) {
            MirageLog.LS.warn("! " + m.manifest.id + " v" + m.manifest.version
                    + " [" + m.jarFile.getFileName() + "]");
        }
        switch (policy) {
            case SKIP -> {
                for (ModContainer m : unresolved) {
                    ctx.skippedMods().add(m.manifest.id);
                    MirageLog.MIRAGE.warn("按 ParseFailPolicy=skip 跳过无法解析的 mod: " + m.manifest.id);
                }
                return StageOutcome.success();
            }
            case CRASH -> {
                return StageOutcome.fatal("ParseFailPolicy=crash：" + unresolved.size()
                        + " 个 mod 无法完全解析（主类定位失败），清单见上。补齐入口，或改用 skip/confirm 策略");
            }
            case CONFIRM -> {
                MirageLog.LS.raw("[LS] 请选择：[Y] 继续启动（这些 mod 仍会被跳过并记 WARN） / [S] 跳过这些 mod 并继续 / [N] 中止启动");
                String line = readLine();
                if (line == null) {
                    return StageOutcome.fatal("无人值守环境（无控制台）无法交互确认，按无人值守策略降级为 crash");
                }
                switch (line.trim().toUpperCase(Locale.ROOT)) {
                    case "Y" -> {
                        MirageLog.LS.info("用户选择继续启动（这些 mod 将在 S6 跳过并记 WARN）");
                        return StageOutcome.success();
                    }
                    case "S" -> {
                        for (ModContainer m : unresolved) {
                            ctx.skippedMods().add(m.manifest.id);
                        }
                        MirageLog.LS.info("已按用户选择跳过 " + unresolved.size() + " 个无法解析的 mod");
                        return StageOutcome.success();
                    }
                    default -> {
                        return StageOutcome.fatal("用户选择中止启动（" + unresolved.size() + " 个 mod 无法完全解析）");
                    }
                }
            }
        }
        return StageOutcome.success(); // 不可达（枚举全覆盖）
    }

    /** 读取一行控制台输入；不可读返回 null（调用方按无人值守处理）。不关闭 System.in。 */
    private static String readLine() {
        try {
            BufferedReader r = new BufferedReader(
                    new InputStreamReader(System.in, StandardCharsets.UTF_8));
            return r.readLine();
        } catch (Exception e) {
            return null;
        }
    }
}
