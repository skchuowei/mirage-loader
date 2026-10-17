package net.neoforged.bus.api;

/**
 * 运行时桥接 stub：NeoForge 事件总线接口。
 *
 * <p>实现为 {@link io.mirage.neoforge.runtime.NeoEventBusImpl}。
 * 第一版只支撑 {@link #register(Object)} 与 {@link #post(Object)} 两个高频方法；
 * {@code addListener / unregister} 等暂缺，被调用时给出清晰报错而非静默失败。
 */
public interface IEventBus {

    /** 把带 {@link SubscribeEvent} 注解方法的对象注册到本总线。 */
    void register(Object target);

    /** 派发事件，按事件类型分桶调用已注册监听方法。返回是否有至少一个监听器被调用。 */
    boolean post(Object event);
}
