package net.neoforged.fml.event;

/**
 * 运行时桥接 stub：只在 mod 总线上派发的事件标记接口（clean-room）。
 *
 * <p>真实 NeoForge 用它区分「mod 加载期事件」。当前事件总线是单总线模型，
 * 标记接口只保证 mod 的 {@code instanceof} / 泛型约束能通过编译与链接。
 */
public interface IModBusEvent {
}
