package io.mirage.eventbus;

import io.mirage.api.log.MirageLog;

import io.mirage.api.bus.EventBusHandle;

import java.lang.reflect.Method;

/**
 * 把内部 {@link EventBus} 适配成公共 {@link EventBusHandle}。
 *
 * <p>core 的 EventBus 已经按事件类分桶、反射调用 {@code @SubscribeEvent}；
 * 这里只补两个公共方法：unregister / hasListeners。
 */
public final class EventBusHandleImpl implements EventBusHandle {

    private final EventBus delegate;

    public EventBusHandleImpl(EventBus delegate) {
        this.delegate = delegate;
    }

    public EventBus unwrap() {
        return delegate;
    }

    @Override
    public void register(Object listener) {
        delegate.register(listener);
    }

    @Override
    public void unregister(Object listener) {
        // core EventBus 当前没实现反注册；M4 后补。
        // 这里打个占位日志，避免 Mod 以为 unregister 生效了。
        MirageLog.MIRAGE.warn("EventBus.unregister() 暂未实现，"
                + listener.getClass().getName() + " 的监听器仍会被保留");
    }

    @Override
    public void post(Object event) {
        delegate.post(event);
    }

    @Override
    public boolean hasListeners(Class<?> eventType) {
        // core EventBus 的 listeners map 是 private；用一次反射探测太重。
        // 简单实现：post 一个空测试事件不可行（有副作用）。
        // 这里保守返回 true——hasListeners 主要用于优化，Mod 不能依赖它做正确性判断。
        return true;
    }
}
