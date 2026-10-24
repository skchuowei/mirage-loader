package io.mirage.ls;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 阶段间共享上下文（LS-Interfaces.md §4，2026-10-24 冻结）。
 *
 * <p>阶段之间只通过本类传状态；properties key 用阶段 id 前缀约定
 * （如 "S1.mods"、"S3.classLoader"），正式 key 清单见 LS-Interfaces.md 附录 A，
 * 未登记的 key 视为阶段私有，禁止跨阶段引用。
 */
public final class LifecycleContext {

    private final String[] args;
    private final LifecycleMode mode;
    private final Path gameDir;
    private final Map<String, Object> properties = new ConcurrentHashMap<>();

    public LifecycleContext(String[] args, LifecycleMode mode) {
        this.args = args;
        this.mode = mode;
        this.gameDir = parseGameDir(args);
    }

    public String[] args() {
        return args;
    }

    public LifecycleMode mode() {
        return mode;
    }

    /** 与现有 Launcher.parseGameDir 一致：--gameDir &lt;path&gt;，缺省当前目录。 */
    public Path gameDir() {
        return gameDir;
    }

    public Map<String, Object> properties() {
        return properties;
    }

    private static Path parseGameDir(String[] args) {
        for (int i = 0; i < args.length - 1; i++) {
            if ("--gameDir".equals(args[i])) {
                return Paths.get(args[i + 1]);
            }
        }
        return Paths.get(".").toAbsolutePath();
    }
}
