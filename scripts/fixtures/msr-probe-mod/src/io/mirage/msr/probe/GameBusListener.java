package io.mirage.msr.probe;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/**
 * MSR P3 · 合成探针 mod 的 <b>game bus</b> 监听器（2026-11-07）。
 *
 * <p><b>为什么必须独立成类</b>：{@code modBus.register(this)} 会把该类里<b>所有</b>
 * {@code @SubscribeEvent} 方法注册到 <b>mod bus</b>。game bus 事件不是
 * {@code IModBusEvent} 的子类 ⇒ 会被 mod bus 拒绝并让 mod 加载失败：
 * 「This bus only accepts subclasses of interface IModBusEvent」。
 *
 * <p>拆成独立类后，只有 {@code NeoForge.EVENT_BUS.register(new GameBusListener())}
 * 会扫到它 ⇒ game bus 事件正常生效，mod bus 也不受污染。
 */
public class GameBusListener {

    /** game bus 事件（P3 应当观测到这个订阅）。 */
    @SubscribeEvent
    public void onLevelTick(LevelTickEvent.Post event) {
        System.out.println("[MSRProbe] LevelTickEvent.Post 已生效");
    }
}
