package net.neoforged.fml;

/**
 * 运行时桥接 stub：NeoForge {@code IExtensionPoint}（clean-room，仅对齐签名与形状）。
 *
 * <p>真实签名是一个空标记接口（见 26.2.0.90 loader jar 的公开字节码签名观察），
 * {@code IConfigScreenFactory} 继承它。本类只提供链接期形状，不承载任何实现逻辑。
 *
 * <p>合规边界：NeoForge 为 LGPL 2.1；本类由公开签名观察后 clean-room 手写，
 * 不复制、不反编译移植其实现代码。
 */
public interface IExtensionPoint {
}
