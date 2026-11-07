package io.mirage.loader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 游戏目录下的"约定路径"准备（纯 JDK，无游戏类依赖，可离线验证）。
 *
 * <p><b>为什么需要它（已知 bug #4）</b>：NeoForge 的语义是「{@code <gameDir>/config}
 * 在 mod 构造之前就已存在」——ExpOre 这类 mod 在构造函数里直接
 * {@code new FileWriter("config/expore-common.toml")}，NeoForge 侧靠的是加载器提前建好目录。
 * Mirage 原先没做这一步，S6 构造期就抛
 * {@code FileNotFoundException: config\expore-common.toml (系统找不到指定的路径)}，
 * mod 退化为默认配置继续跑（不崩，但配置写不下去）。
 *
 * <p><b>为什么单独成类</b>：{@link io.mirage.ls.stage.ModInitStage} 引了一大堆游戏/NeoForge 类，
 * 纯 JVM 回归根本加载不了它；把「建目录」这步纯路径逻辑抽出来，就能在离线用例里
 * 对着真实生产代码跑（{@code scripts/verify_config_dir.ps1}），而不是测一份副本。
 *
 * <p>注意：本类**不做缓存**，每次调用都打一次 {@link Files#createDirectories}；
 * 该方法是幂等的（已存在直接返回），代价可忽略，换来的是"任何时机调用都保证目录在"。
 */
public final class GameDirs {

    /** 配置目录名，与 MC 自身、NeoForge {@code FMLPaths.CONFIGDIR} 保持一致。 */
    public static final String CONFIG_DIR_NAME = "config";

    private GameDirs() {}

    /** {@code <gameDir>/config}，**不保证存在**（只做路径拼接）。 */
    public static Path configDir(Path gameDir) {
        return gameDir.resolve(CONFIG_DIR_NAME);
    }

    /**
     * 确保 {@code <gameDir>/config} 存在并返回它。
     *
     * <p>用 {@link Files#createDirectories} 而不是 {@code createDirectory}：{@code --gameDir}
     * 指向的目录本身也可能还没建（首次启动 / 临时目录），父级需要一并创建。
     *
     * <p>已存在时幂等返回；若该路径被**普通文件**占用，抛
     * {@link java.nio.file.FileAlreadyExistsException}（{@link IOException} 子类），
     * 由调用方决定降级策略。
     *
     * @throws IOException 创建失败（无权限、路径被文件占用等）
     */
    public static Path ensureConfigDir(Path gameDir) throws IOException {
        Path dir = configDir(gameDir);
        Files.createDirectories(dir);
        return dir;
    }
}
