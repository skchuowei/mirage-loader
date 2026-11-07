package io.mirage.neoforge.runtime;

import net.neoforged.neoforgespi.language.IConfigurable;
import net.neoforged.neoforgespi.language.IModFileInfo;
import net.neoforged.neoforgespi.language.IModInfo;

import java.nio.file.Path;
import java.util.Optional;

/**
 * {@link IModInfo} 的运行时实现（clean-room）。
 *
 * <p>由 {@code NeoCompatBootstrap.registerMods} 按加载器已发现的 mod 清单构造。
 * 数据来源是 MG 的 {@code ModManifest}，不读 NeoForge 自己的实现代码。
 */
public final class NeoModInfo implements IModInfo {

    private static final IConfigurable EMPTY_CONFIG = keys -> Optional.empty();

    private final String modId;
    private final String version;
    private final String displayName;
    private final NeoModFileInfo owningFile;

    public NeoModInfo(String modId, String version, String displayName, Path jarFile) {
        this.modId = modId;
        this.version = version;
        this.displayName = displayName;
        this.owningFile = new NeoModFileInfo(jarFile);
    }

    @Override
    public String getModId() {
        return modId;
    }

    @Override
    public String getDisplayName() {
        return displayName;
    }

    @Override
    public IModFileInfo getOwningFile() {
        return owningFile;
    }

    @Override
    public IConfigurable getConfig() {
        return EMPTY_CONFIG;
    }

    @Override
    public Optional<String> getLogoFile() {
        return Optional.empty();
    }

    public String getVersionString() {
        return version;
    }

    @Override
    public String toString() {
        return modId + "@" + version;
    }
}
