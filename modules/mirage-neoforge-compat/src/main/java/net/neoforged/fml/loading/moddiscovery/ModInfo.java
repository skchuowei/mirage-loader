package net.neoforged.fml.loading.moddiscovery;

import java.util.Optional;

/**
 * 运行时桥接 stub：加载器视角的 mod 元数据（clean-room）。
 *
 * <p>FerriteCore 的 {@code ConfigFileHandler.collectDisabledOverrides} 会遍历
 * {@code LoadingModList.getMods()} 并把元素 cast 成这个类，再调
 * {@code getConfigElement("ferritecore:disabled_options")}。当前一律返回空
 * （不猜自定义 TOML 段），效果等同「没有 disabled overrides」。
 */
public final class ModInfo {

    private final String modId;
    private final String version;
    private final String displayName;

    public ModInfo(String modId, String version, String displayName) {
        this.modId = modId;
        this.version = version;
        this.displayName = displayName;
    }

    public String getModId() {
        return modId;
    }

    public String getVersion() {
        return version;
    }

    public String getDisplayName() {
        return displayName;
    }

    public Optional<Object> getConfigElement(String... keys) {
        return Optional.empty();
    }

    @Override
    public String toString() {
        return modId + "@" + version;
    }
}
