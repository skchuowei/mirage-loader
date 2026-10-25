package net.neoforged.fml.config;

import java.nio.file.Path;

/**
 * 一个 mod 的配置文件描述（NeoForge 兼容 stub）。
 *
 * <p>MG 不实现完整的 Forge ConfigSpec 校验；配置值直接从 .toml 文件读取，
 * 通过 {@link #getConfigData()} 暴露类型化访问。
 */
public final class ModConfig {

    /** 配置类型（NeoForge 1.20+ 语义）。 */
    public enum Type { COMMON, CLIENT, SERVER, STARTUP, UNIVERSAL }

    private final Type type;
    private final String modId;
    private final String fileName;
    private final Path fullPath;
    private final ConfigData data;

    public ModConfig(Type type, String modId, String fileName, Path fullPath, ConfigData data) {
        this.type = type;
        this.modId = modId;
        this.fileName = fileName;
        this.fullPath = fullPath;
        this.data = data;
    }

    public Type getType() { return type; }
    public String getModId() { return modId; }
    public String getFileName() { return fileName; }
    public Path getFullPath() { return fullPath; }

    /** 类型化配置数据（读取自 .toml）。 */
    public ConfigData getConfigData() { return data; }
}
