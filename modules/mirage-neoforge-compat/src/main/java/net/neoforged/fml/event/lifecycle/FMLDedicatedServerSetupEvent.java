package net.neoforged.fml.event.lifecycle;

import net.neoforged.bus.api.Event;

/**
 * 运行时桥接 stub：服务端 setup 事件（dedicated server 时 post；当前 CLIENT 运行时不发）。
 */
public class FMLDedicatedServerSetupEvent extends Event {
}
