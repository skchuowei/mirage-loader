package io.mirage.eventbus;

import io.mirage.api.SubscribeEvent;
import io.mirage.api.hook.PassiveHooks;
import io.mirage.api.log.MirageLog;

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
        // MSR P2 被动捕获：窗口外 no-op，窗口内记「注册了哪些事件类型」
        PassiveHooks.api("io.mirage.eventbus.EventBus.register");
        for (Method m : listener.getClass().getDeclaredMethods()) {
            if (m.isAnnotationPresent(SubscribeEvent.class) && m.getParameterCount() == 1) {
                Class<?> eventType = m.getParameterTypes()[0];
                PassiveHooks.event(eventType.getName());
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
                // 改造前是 ": " + e.getMessage()——对 NPE / InvocationTargetException
                // 只会打印出 "null"，既不知道是哪个监听器炸的，也不知道真实 cause。
                // 这里补上「监听器方法 + 事件类型」，并把异常交给门面展开完整链路。
                MirageLog.MIRAGE.error("事件处理错误: "
                        + m.getDeclaringClass().getName() + "#" + m.getName()
                        + " 处理 " + event.getClass().getSimpleName() + " 时抛出", e);
            }
        }
    }
}
