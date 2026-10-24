package net.neoforged.bus.api;

/**
 * 沙盒 fake：NeoForge 事件总线接口占位。
 * 方法签名对齐 NeoForge 高频子集（register / post / addListener）。
 */
public interface IEventBus {

    /** 把带 {@link SubscribeEvent} 注解方法的对象注册到本总线。 */
    void register(Object target);

    /** 派发事件。 */
    boolean post(Object event);

    /** 挂一个监听器对象（NeoForge 语义：反射扫描其 @SubscribeEvent 方法）。 */
    void addListener(Object target);
}
