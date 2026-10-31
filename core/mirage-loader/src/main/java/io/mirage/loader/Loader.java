package io.mirage.loader;

import io.mirage.api.log.MirageLog;

import java.nio.file.Path;
import java.util.List;

/**
 * 加载器门面：被 Launcher 调用，编排发现 -> 排序 -> 日志。
 * M2 阶段不实例化 mainClass，只识别。
 */
public class Loader {

    public List<ModContainer> discoverMods(Path gameDir) {
        return discoverMods(gameDir, null, false, false, "auto");
    }

    /**
     * @param cache      MID 磁盘缓存；null 表示不缓存
     * @param forceRegen 为 true 时忽略缓存
     * @param verbose    为 true 时打印缓存命中日志
     */
    public List<ModContainer> discoverMods(Path gameDir, MidCache cache,
                                          boolean forceRegen, boolean verbose) {
        return discoverMods(gameDir, cache, forceRegen, verbose, "auto");
    }

    /**
     * @param sandboxMode 沙盒录制开关（off/auto/force）
     */
    public List<ModContainer> discoverMods(Path gameDir, MidCache cache,
                                          boolean forceRegen, boolean verbose,
                                          String sandboxMode) {
        Path modsDir = gameDir.resolve("mods");
        MirageLog.LOADER.info("---- 开始扫描 mod ----");
        try {
            List<ModContainer> raw = new ModDiscoverer().discover(modsDir, cache, forceRegen, verbose, sandboxMode);
            List<ModContainer> sorted = new DependencySorter().sort(raw);
            MirageLog.LOADER.info("---- 加载顺序（" + sorted.size() + " 个 mod）----");
            int i = 1;
            for (ModContainer c : sorted) {
                // 逐条清单只在排查加载顺序问题时有用，属诊断输出
                MirageLog.LOADER.debug("  " + (i++) + ". " + c.manifest.id + " v" + c.manifest.version);
            }
            MirageLog.LOADER.info("---- mod 扫描完成 ----");
            return sorted;
        } catch (Exception e) {
            // 扫描失败会让「一个 mod 都没有」，必须带全堆栈——否则只能看到 "null"
            MirageLog.LOADER.error("mod 扫描失败（本次按 0 个 mod 继续启动）", e);
            return List.of();
        }
    }
}
