package net.neoforged.fml.event.lifecycle;

import net.neoforged.bus.api.Event;

/**
 * 运行时桥接 stub：通用 setup 事件（NeoForge 生命周期中段）。
 * MG 在所有 mod onInitialize 完成后 post。
 */
public class FMLCommonSetupEvent extends Event {
}
