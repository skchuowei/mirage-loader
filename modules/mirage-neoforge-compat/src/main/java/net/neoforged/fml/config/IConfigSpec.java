package net.neoforged.fml.config;

/**
 * 配置规格占位接口（NeoForge 兼容 stub）。
 *
 * <p>完整 Forge 的 {@code IConfigSpec} 含 builder / 校验 / 默认值；MG 不实现规格校验，
 * 配置值直接来自 .toml 文件。本接口仅作为 {@code registerConfig} 的令牌参数存在。
 */
public interface IConfigSpec {
}
