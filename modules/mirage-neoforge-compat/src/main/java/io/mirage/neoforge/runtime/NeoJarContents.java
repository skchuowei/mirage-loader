package io.mirage.neoforge.runtime;

import net.neoforged.fml.jarcontents.JarContents;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * {@link JarContents} 的运行时实现（clean-room）：直接读 mod jar。
 *
 * <p>找不到 entry 或 jar 不存在时返回 {@code null}（真实调用方会
 * {@code Optional.ofNullable}），不抛异常打断 mod 初始化。
 */
public final class NeoJarContents implements JarContents {

    private final Path jarFile;

    public NeoJarContents(Path jarFile) {
        this.jarFile = jarFile;
    }

    @Override
    public byte[] readFile(String name) {
        if (jarFile == null || name == null || !jarFile.toFile().isFile()) {
            return null;
        }
        String entryName = name.startsWith("/") ? name.substring(1) : name;
        try (ZipFile zip = new ZipFile(jarFile.toFile())) {
            ZipEntry entry = zip.getEntry(entryName);
            if (entry == null) {
                return null;
            }
            try (InputStream in = zip.getInputStream(entry)) {
                return in.readAllBytes();
            }
        } catch (IOException e) {
            return null;
        }
    }
}
