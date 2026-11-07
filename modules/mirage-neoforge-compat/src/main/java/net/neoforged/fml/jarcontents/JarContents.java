package net.neoforged.fml.jarcontents;

/**
 * 运行时桥接 stub：mod jar 内容读取（clean-room）。
 *
 * <p>JEI 读 mod 图标时按 {@code readFile(name)} 取字节；找不到返回 {@code null}
 * （调用方随后 {@code Optional.ofNullable}）。实现见
 * {@code io.mirage.neoforge.runtime.NeoJarContents}。
 */
public interface JarContents {

    byte[] readFile(String name);
}
