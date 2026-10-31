package io.mirage.eventbus;

import io.mirage.api.SubscribeEvent;
import io.mirage.api.hook.PassiveHooks;
import io.mirage.api.log.MirageLog;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * 轻量事件总线：按事件类型分桶。
 *
 * <p>两种注册方式：
 * <ul>
 *   <li>{@link #register(Object)}：扫 {@code @SubscribeEvent} 方法（Java mod 用法，语义未变）；</li>
 *   <li>{@link #register(Class, Consumer)}：函数式注册（脚本 mod 用——脚本函数不是 Java 方法，
 *       没有注解可扫）。</li>
 * </ul>
 * 两条路径共用「先 Method、后 Consumer」的派发顺序，既有 mod 的触发次序不受影响。
 */
public class EventBus {
    private final Map<Class<?>, List<Method>> listeners = new ConcurrentHashMap<>();
    private final Map<Object, List<Method>> ownerMethods = new ConcurrentHashMap<>();
    private final Map<Class<?>, List<Consumer<Object>>> consumers = new ConcurrentHashMap<>();

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

    /**
     * 函数式注册（MGScript 用）。返回一个取消句柄，便于 `/mgs reload` 时摘掉旧回调——
     * 否则重载会把同一个事件重复派发 N 次，表现为「脚本里一句 log 打两遍」。
     */
    public Runnable register(Class<?> eventType, Consumer<Object> handler) {
        consumers.computeIfAbsent(eventType, k -> new CopyOnWriteArrayList<>()).add(handler);
        return () -> {
            List<Consumer<Object>> list = consumers.get(eventType);
            if (list != null) list.remove(handler);
        };
    }

    public void post(Object event) {
        List<Method> methods = listeners.get(event.getClass());
        if (methods != null) {
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

        List<Consumer<Object>> handlers = consumers.get(event.getClass());
        if (handlers != null) {
            for (Consumer<Object> h : handlers) {
                try {
                    h.accept(event);
                } catch (Throwable t) {
                    MirageLog.MIRAGE.error("事件处理错误（脚本回调）处理 "
                            + event.getClass().getSimpleName() + " 时抛出", t);
                }
            }
        }
    }
}
