package io.mirage.neoforge.runtime;

import net.neoforged.fml.jarcontents.JarContents;
import net.neoforged.neoforgespi.locating.IModFile;

import java.nio.file.Path;

/**
 * {@link IModFile} 的运行时实现（clean-room）：持有 mod jar 路径，按需打开。
 */
public final class NeoModFile implements IModFile {

    private final Path jarFile;

    public NeoModFile(Path jarFile) {
        this.jarFile = jarFile;
    }

    @Override
    public JarContents getContents() {
        return new NeoJarContents(jarFile);
    }
}
