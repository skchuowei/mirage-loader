package io.mirage.loader;

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
        System.out.println("[Mirage] ---- 开始扫描 mod ----");
        try {
            List<ModContainer> raw = new ModDiscoverer().discover(modsDir, cache, forceRegen, verbose, sandboxMode);
            List<ModContainer> sorted = new DependencySorter().sort(raw);
            System.out.println("[Mirage] ---- 加载顺序（" + sorted.size() + " 个 mod）----");
            int i = 1;
            for (ModContainer c : sorted) {
                System.out.println("[Mirage]   " + (i++) + ". " + c.manifest.id + " v" + c.manifest.version);
            }
            System.out.println("[Mirage] ---- mod 扫描完成 ----");
            return sorted;
        } catch (Exception e) {
            System.err.println("[Mirage] mod 扫描失败: " + e.getMessage());
            e.printStackTrace();
            return List.of();
        }
    }
}
