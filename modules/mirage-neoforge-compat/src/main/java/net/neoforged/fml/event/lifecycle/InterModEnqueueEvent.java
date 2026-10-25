package net.neoforged.fml.event.lifecycle;

import net.neoforged.bus.api.Event;

/**
 * 模组间通信入队事件（Inter-Mod Communication enqueue）。
 *
 * <p>在 {@link FMLConstructModEvent} 之后、{@link FMLCommonSetupEvent} 之前 post。
 * mod 在此阶段通过 {@code InterModComms.sendTo(...)} 向其它 mod 发消息；
 * 接收方在 {@link InterModProcessEvent} 阶段读取。
 *
 * <p>MG 为运行时桥接 stub：{@link #getModEventID()} 返回当前活跃 modId（由派发桥写入），
 * 单 mod 总线模式下退化为加载器占位符。
 */
public class InterModEnqueueEvent extends Event {

    /** 派发桥在 post 前写入的当前活跃 modId（单总线模式退化为 "mirage"）。 */
    private static final ThreadLocal<String> ACTIVE_MOD = new ThreadLocal<>();

    static void setActiveMod(String modId) {
        ACTIVE_MOD.set(modId);
    }

    public String getModEventID() {
        String id = ACTIVE_MOD.get();
        return id == null ? "mirage" : id;
    }
}
