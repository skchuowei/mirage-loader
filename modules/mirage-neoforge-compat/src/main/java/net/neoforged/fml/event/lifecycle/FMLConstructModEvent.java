package net.neoforged.fml.event.lifecycle;

import net.neoforged.bus.api.Event;

/**
 * 运行时桥接 stub：mod 构造完成事件（NeoForge 生命周期事件第一站）。
 *
 * <p>MG 在所有 mod 主类实例化完成后，向 mod bus post 本事件；
 * mod 里 {@code @SubscribeEvent public void onConstruct(FMLConstructModEvent e)}
 * 方法会被 {@code NeoEventBusImpl} 分发到。
 */
public class FMLConstructModEvent extends Event {
}
