package io.mirage.neoforge.runtime;

import net.neoforged.neoforgespi.language.IConfigurable;
import net.neoforged.neoforgespi.language.IModFileInfo;
import net.neoforged.neoforgespi.locating.IModFile;

import java.nio.file.Path;
import java.util.Optional;

/**
 * {@link IModFileInfo} 的运行时实现（clean-room）：只是把 jar 路径透传给
 * {@link IModFile#getContents()}，供 JEI 这类需要读 mod 图标的调用方使用。
 */
public final class NeoModFileInfo implements IModFileInfo {

    private static final IConfigurable EMPTY_CONFIG = keys -> Optional.empty();

    private final NeoModFile file;

    public NeoModFileInfo(Path jarFile) {
        this.file = new NeoModFile(jarFile);
    }

    @Override
    public IModFile getFile() {
        return file;
    }

    @Override
    public IConfigurable getConfig() {
        return EMPTY_CONFIG;
    }
}
