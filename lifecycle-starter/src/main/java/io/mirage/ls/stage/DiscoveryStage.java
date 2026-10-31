package io.mirage.ls.stage;

import io.mirage.api.log.MirageLog;

import io.mirage.loader.Loader;
import io.mirage.loader.MidCache;
import io.mirage.loader.ModContainer;
import io.mirage.loader.MirageDebug;
import io.mirage.ls.LifecycleContext;
import io.mirage.ls.LifecycleMode;
import io.mirage.ls.LifecycleStarter;
import io.mirage.ls.Stage;
import io.mirage.ls.StageOutcome;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * S1 发现（LS-Interfaces.md §7；语义见 MG-Lifecycle.md S1）。
 *
 * <p>phase 2 迁入的 Launcher 逻辑（保序）：MirageDebug 阶段/进度打点 → gameDir 日志 →
 * MidCache 构建 → {@code new Loader().discoverMods(...)} → mods 计数/命名空间登记。
 * MID 缓存开关与沙盒录制开关由 LS 入口统一解析后经 {@link LifecycleContext} 传入
 * （v0.2：参数解析决策集中在 LS 侧，阶段不再各自解析）。
 *
 * <p>C1 附加行为（周期语义，opt-in）：只保留 mirage 原生描述符的 mod
 * （IR {@code mod.mirage.json} 或 legacy {@code META-INF/mirage.mod.json}），
 * 非原生（Fabric/NeoForge）按 WARN 显式过滤——不静默丢弃（原则 3）。
 */
public final class DiscoveryStage implements Stage {

    @Override
    public String id() {
        return "S1";
    }

    @Override
    public String name() {
        return "Discovery";
    }

    @Override
    public StageOutcome execute(LifecycleContext ctx) {
        return StageSupport.guard(() -> {
            io.mirage.loader.MirageDebug.setStage("扫描 mods 目录");
            io.mirage.loader.MirageDebug.setProgress(3);
            Path gameDir = ctx.gameDir();
            MirageLog.MIRAGE.info("游戏目录 = " + gameDir);

            io.mirage.loader.MirageDebug.setStage("解析 mod 清单");
            io.mirage.loader.MirageDebug.setProgress(8);

            MidCache midCache = new MidCache(gameDir, LifecycleStarter.MC_VERSION);
            List<ModContainer> mods = new Loader().discoverMods(gameDir, midCache,
                    ctx.midCacheForceRegen(), ctx.midCacheVerbose(), ctx.sandboxMode());

            if (ctx.mode() == LifecycleMode.C1) {
                mods = filterNativeOnly(mods);
            }

            io.mirage.loader.MirageDebug.setModTotal(mods.size());
            MirageLog.MIRAGE.info("发现 " + mods.size() + " 个 mod");
            for (ModContainer m : mods) {
                io.mirage.loader.MirageDebug.modNamespaces.add(m.manifest.id);
            }
            ctx.properties().put("S1.mods", mods);
        });
    }

    /** C1 原生过滤：IR 描述符 / legacy mirage.mod.json 视为原生；Fabric/NeoForge mod 按 WARN 滤除。 */
    private static List<ModContainer> filterNativeOnly(List<ModContainer> mods) {
        List<ModContainer> nativeOnly = new ArrayList<>();
        for (ModContainer m : mods) {
            if (m.ir != null || (m.fabricEntrypoints == null && !m.fromNeoForge)) {
                nativeOnly.add(m);
            } else {
                MirageLog.MIRAGE.warn("C1 直启模式跳过非 mirage 原生 mod: "
                        + m.manifest.id + " v" + m.manifest.version
                        + "（需要 AG 转译，请用 C2/C3 周期）");
            }
        }
        return nativeOnly;
    }
}
