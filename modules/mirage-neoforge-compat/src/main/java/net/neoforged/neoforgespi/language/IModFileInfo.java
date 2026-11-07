package net.neoforged.neoforgespi.language;

import net.neoforged.neoforgespi.locating.IModFile;

/**
 * 运行时桥接 stub：mod jar 描述（clean-room）。实现见
 * {@code io.mirage.neoforge.runtime.NeoModFileInfo}。
 */
public interface IModFileInfo {

    IModFile getFile();

    IConfigurable getConfig();
}
