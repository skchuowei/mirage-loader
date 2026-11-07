package net.neoforged.fml.loading;

import java.nio.file.Path;

/**
 * 运行时桥接 stub：NeoForge 标准路径（clean-room）。
 *
 * <p>真实签名的用法是 {@code FMLPaths.CONFIGDIR.get()}——字段类型是 {@code FMLPaths}，
 * 实例方法 {@code get()} 返回 {@link Path}。因此这里做成枚举而不是静态 {@code Path} 常量，
 * 保证 {@code getstatic + invokevirtual get()} 的字节码形态与真实 mod 一致。
 *
 * <p>值由加载器在 S6 之前注入（{@code NeoCompatBootstrap.init}），未注入时用当前目录兜底。
 */
public enum FMLPaths {

    GAMEDIR,
    CONFIGDIR,
    MODSDIR,
    LOGSDIR,
    ASSETDIR,
    ROOT;

    private static volatile Path gameDir = Path.of(".").toAbsolutePath().normalize();

    /** 由加载器在 mod 构造之前调用；重复调用幂等。 */
    public static void init(Path dir) {
        if (dir != null) {
            gameDir = dir.toAbsolutePath().normalize();
        }
    }

    public Path get() {
        return switch (this) {
            case GAMEDIR, ROOT -> gameDir;
            case CONFIGDIR -> gameDir.resolve("config");
            case MODSDIR -> gameDir.resolve("mods");
            case LOGSDIR -> gameDir.resolve("logs");
            case ASSETDIR -> gameDir.resolve("assets");
        };
    }
}
