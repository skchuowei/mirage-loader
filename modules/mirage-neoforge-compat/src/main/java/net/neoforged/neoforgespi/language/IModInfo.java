package net.neoforged.neoforgespi.language;

import java.util.Optional;

/**
 * 运行时桥接 stub：NeoForge {@code IModInfo}（clean-room）。
 *
 * <p>只保留真实样本（JEI / Cloth Config）实际会调的成员；其余方法按需再补，
 * 不臆造签名。实现见 {@code io.mirage.neoforge.runtime.NeoModInfo}。
 */
public interface IModInfo {

    String getModId();

    String getDisplayName();

    /** mod jar 的描述信息（JEI 读图标时会经过 {@code getOwningFile() → getFile() → getContents()}）。 */
    IModFileInfo getOwningFile();

    /** 自定义 TOML 配置段（FerriteCore 用它读 {@code ferritecore:disabled_options}）。 */
    IConfigurable getConfig();

    /** {@code logoFile} 字段；未配置时为空。 */
    Optional<String> getLogoFile();
}
