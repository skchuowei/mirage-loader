package io.mirage.api.bus;

/**
 * Mirage 公共事件总线句柄。
 *
 * <p>Mod 不应自己 new EventBus，而应通过
 * {@link io.mirage.api.Mirage#getEventBus()} 拿到加载器持有的全局总线。
 *
 * <p>事件派发顺序与异常隔离语义由实现决定；
 * 一个监听器抛异常不应影响其他监听器。
 */
public interface EventBusHandle {

    /**
     * 注册一个监听器对象。
     * 对象内部所有带 {@code @SubscribeEvent} 且仅有一个参数的方法都会被挂上。
     *
     * @throws IllegalArgumentException 如果对象没有任何可订阅方法
     */
    void register(Object listener);

    /** 反注册一个之前注册过的监听器对象。未注册过时调用是 no-op。 */
    void unregister(Object listener);

    /**
     * 派发一个事件。同步调用所有匹配的监听器。
     *
     * @param event 事件对象，其具体类决定被派发到哪些监听器
     */
    void post(Object event);

    /** 是否已存在针对指定事件类型的监听器。 */
    boolean hasListeners(Class<?> eventType);
}
