package net.neoforged.bus.api;

import io.mirage.loader.sandbox.Sink;

import java.lang.reflect.Method;

/**
 * 沙盒 fake：{@link IEventBus} 的录制实现。
 * {@code register(target)} / {@code addListener(target)} 反射扫描 {@link SubscribeEvent}
 * 方法，把第一个参数的事件类型记入 {@code registered_events}；不真正派发。
 */
public final class FakeEventBus implements IEventBus {

    @Override
    public void register(Object target) {
        scan(target);
    }

    @Override
    public void addListener(Object target) {
        scan(target);
    }

    @Override
    public boolean post(Object event) {
        return false;
    }

    private static void scan(Object target) {
        if (target == null) return;
        for (Method m : target.getClass().getDeclaredMethods()) {
            if (m.isAnnotationPresent(SubscribeEvent.class) && m.getParameterCount() == 1) {
                Sink.registerEvent(m.getParameterTypes()[0].getName());
            }
        }
    }
}
