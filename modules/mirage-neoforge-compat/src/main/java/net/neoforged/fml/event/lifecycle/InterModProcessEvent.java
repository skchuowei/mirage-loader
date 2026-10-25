package net.neoforged.fml.event.lifecycle;

import net.neoforged.bus.api.Event;

import java.util.stream.Stream;

/**
 * 模组间通信处理事件（Inter-Mod Communication process）。
 *
 * <p>在 {@link FMLCommonSetupEvent} 之后 post。mod 在此阶段读取其它 mod 在
 * {@link InterModEnqueueEvent} 阶段通过 {@code InterModComms.sendTo(...)} 发送的消息。
 *
 * <p>MG 为运行时桥接 stub：{@link #getMessages(String, String)} 从全局 IMC 注册表取消息。
 */
public class InterModProcessEvent extends Event {

    /**
     * 取目标 modId / methodName 下的所有 IMC 消息负载（对象流）。无匹配返回空流。
     */
    public Stream<Object> getMessages(String modId, String methodName) {
        return net.neoforged.fml.InterModComms.getMessages(modId, methodName);
    }

    /** 取本 mod 收到的全部 IMC 消息（按 methodName 分组由调用方过滤）。 */
    public Stream<Object> getMessages(String methodName) {
        return net.neoforged.fml.InterModComms.getMessages(methodName);
    }
}
