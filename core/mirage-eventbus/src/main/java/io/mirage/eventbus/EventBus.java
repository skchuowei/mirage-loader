package io.mirage.eventbus;

import io.mirage.api.SubscribeEvent;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 轻量事件总线：按事件类型分桶，扫描 @SubscribeEvent 方法。
 */
public class EventBus {
    private final Map<Class<?>, List<Method>> listeners = new ConcurrentHashMap<>();
    private final Map<Object, List<Method>> ownerMethods = new ConcurrentHashMap<>();

    public void register(Object listener) {
        for (Method m : listener.getClass().getDeclaredMethods()) {
            if (m.isAnnotationPresent(SubscribeEvent.class) && m.getParameterCount() == 1) {
                Class<?> eventType = m.getParameterTypes()[0];
                m.setAccessible(true);
                listeners.computeIfAbsent(eventType, k -> new ArrayList<>()).add(m);
                ownerMethods.computeIfAbsent(listener, k -> new ArrayList<>()).add(m);
            }
        }
    }

    public void post(Object event) {
        List<Method> methods = listeners.get(event.getClass());
        if (methods == null) return;
        for (Method m : methods) {
            try {
                // 找到 owner
                for (Map.Entry<Object, List<Method>> e : ownerMethods.entrySet()) {
                    if (e.getValue().contains(m)) {
                        m.invoke(e.getKey(), event);
                        break;
                    }
                }
            } catch (Exception e) {
                System.err.println("[Mirage] 事件处理错误: " + e.getMessage());
            }
        }
    }
}
