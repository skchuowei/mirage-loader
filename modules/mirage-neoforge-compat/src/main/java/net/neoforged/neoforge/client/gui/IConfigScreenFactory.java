package net.neoforged.neoforge.client.gui;

import java.util.Optional;
import net.minecraft.client.gui.screens.Screen;
import net.neoforged.fml.IExtensionPoint;
import net.neoforged.fml.ModContainer;
import net.neoforged.neoforgespi.language.IModInfo;

/**
 * 运行时桥接 stub：NeoForge {@code IConfigScreenFactory}（clean-room，仅对齐签名与形状）。
 *
 * <p>形状取自 26.2.0.90 universal / loader jar 的公开字节码签名观察：空标记父接口
 * {@link IExtensionPoint} + 一个实例方法 {@link #createScreen(ModContainer, Screen)} +
 * 一个静态查询 {@link #getForMod(IModInfo)}。
 *
 * <p>本类是接口，{@code createScreen} 只声明——真正的配置屏由 mod 自己实现并提供。
 * {@code getForMod} 返回 {@link Optional#empty()} 是<b>真值</b>：MG 侧没有 NeoForge 的
 * 扩展点注册表，所以"查不到任何已注册工厂"是事实，不是编造默认值（N1 原则）。
 *
 * <p>合规边界：NeoForge 为 LGPL 2.1；不复制、不反编译移植其实现代码。
 */
public interface IConfigScreenFactory extends IExtensionPoint {

    /** 由实现方返回该 mod 的配置屏；MG 侧接口只声明，不提供假实现。 */
    Screen createScreen(ModContainer container, Screen parentScreen);

    /**
     * 查询指定 mod 注册的配置屏工厂。
     *
     * <p>MG 没有 NeoForge 的扩展点注册表，恒为空是真实语义（见类注释）。
     */
    static Optional<IConfigScreenFactory> getForMod(IModInfo modInfo) {
        return Optional.empty();
    }
}
