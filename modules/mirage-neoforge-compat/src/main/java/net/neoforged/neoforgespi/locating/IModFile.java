package net.neoforged.neoforgespi.locating;

import net.neoforged.fml.jarcontents.JarContents;

/**
 * 运行时桥接 stub：磁盘上的 mod 文件（clean-room）。
 * 实现见 {@code io.mirage.neoforge.runtime.NeoModFile}。
 */
public interface IModFile {

    JarContents getContents();
}
