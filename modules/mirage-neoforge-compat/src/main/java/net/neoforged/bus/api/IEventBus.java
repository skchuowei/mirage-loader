package net.neoforged.bus.api;

import java.util.function.Consumer;

/**
 * 运行时桥接 stub：NeoForge 事件总线接口。
 *
 * <p>实现为 {@link io.mirage.neoforge.runtime.NeoEventBusImpl}。
 * 支持 {@code register / unregister / addListener / post}。
 */
public interface IEventBus {

    /** 把带 {@link SubscribeEvent} 注解方法的对象注册到本总线。 */
    void register(Object target);

    /** 注销该对象注册过的全部监听器。 */
    void unregister(Object target);

    /** 注册 lambda / 方法引用监听器，事件类型由泛型签名推断。 */
    <T extends Event> void addListener(Consumer<T> consumer);

    <T extends Event> void addListener(EventPriority priority, Consumer<T> consumer);

    <T extends Event> void addListener(EventPriority priority, boolean receiveCancelled, Consumer<T> consumer);

    <T extends Event> void addListener(EventPriority priority, boolean receiveCancelled, Class<T> eventType, Consumer<T> consumer);

    /** 派发事件，按事件类型的继承层次分发。返回是否有至少一个监听器被调用。 */
    boolean post(Event event);

    /** 兼容内部旧调用点的 Object 重载。 */
    boolean post(Object event);
}
