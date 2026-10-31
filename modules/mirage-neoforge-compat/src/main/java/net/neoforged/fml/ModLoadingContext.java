package net.neoforged.fml;

import io.mirage.api.hook.PassiveHooks;
import io.mirage.api.log.MirageLog;

import net.neoforged.fml.config.ConfigData;
import net.neoforged.fml.config.IConfigSpec;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.config.TomlConfigData;
import net.neoforged.fml.config.TomlConfigIO;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 配置注册入口（NeoForge 兼容 stub）。mod 在构造期调用
 * {@code ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, spec, "mymod-common.toml")}。
 *
 * <p>MG 不实现完整 ConfigSpec 校验：注册时读取（或创建）对应 .toml，配置值通过
 * {@link ModConfig#getConfigData()} 暴露。
 */
public final class ModLoadingContext {

    private static final ModLoadingContext INSTANCE = new ModLoadingContext();

    /** 构造期由加载器写入的当前活跃 modId（单总线模式退化为 "mirage"）。 */
    private static final ThreadLocal<String> ACTIVE_MOD = new ThreadLocal<>();

    /** 配置目录：<gameDir>/config，由加载器启动早期设置。 */
    private static volatile Path configDir = Path.of("config");

    private final Map<String, ModConfig> byKey = new LinkedHashMap<>();

    private ModLoadingContext() {}

    public static ModLoadingContext get() {
        return INSTANCE;
    }

    public static void setActiveMod(String modId) {
        ACTIVE_MOD.set(modId);
    }

    public static void setConfigDirectory(Path dir) {
        if (dir != null) configDir = dir;
    }

    public Path getConfigDirectory() {
        return configDir;
    }

    /** 用当前活跃 modId 注册配置（mod 构造期常用写法）。 */
    public ModConfig registerConfig(ModConfig.Type type, IConfigSpec spec, String fileName) {
        return registerConfig(type, spec, fileName, ACTIVE_MOD.get());
    }

    /** 显式指定 modId 注册配置。 */
    public ModConfig registerConfig(ModConfig.Type type, IConfigSpec spec, String fileName, String modId) {
        String id = (modId == null || modId.isEmpty()) ? "mirage" : modId;
        // MSR P2 被动捕获：配置注册是 mod 构造期最典型的 API 触达之一
        PassiveHooks.api("net.neoforged.fml.ModLoadingContext.registerConfig");
        Path fullPath = configDir.resolve(fileName);
        Map<String, Object> parsed;
        try {
            parsed = TomlConfigIO.readFile(fullPath);
        } catch (Exception e) {
            MirageLog.MIRAGE.warn("[Config] 读取 " + fullPath + " 失败: " + e + "，使用空配置");
            parsed = new LinkedHashMap<>();
        }
        ModConfig mc = new ModConfig(type, id, fileName, fullPath, new TomlConfigData(parsed));
        byKey.put(key(id, type), mc);
        MirageLog.MIRAGE.info("[Config] 注册 mod=" + id + " type=" + type + " file=" + fullPath);
        return mc;
    }

    /** 取某 mod + 类型的配置。 */
    public ModConfig getConfig(String modId, ModConfig.Type type) {
        return byKey.get(key(modId, type));
    }

    /** 用活跃 modId 取某类型配置。 */
    public ModConfig getConfig(ModConfig.Type type) {
        return byKey.get(key(ACTIVE_MOD.get() == null ? "mirage" : ACTIVE_MOD.get(), type));
    }

    /** 便捷：取配置数据（可能为 null）。 */
    public ConfigData getConfigData(ModConfig.Type type) {
        ModConfig mc = getConfig(type);
        return mc == null ? null : mc.getConfigData();
    }

    private static String key(String modId, ModConfig.Type type) {
        return modId + ":" + type.name();
    }
}
