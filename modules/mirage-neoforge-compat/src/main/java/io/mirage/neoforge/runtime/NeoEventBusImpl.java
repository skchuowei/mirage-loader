package io.mirage.neoforge.runtime;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * NeoForge {@link IEventBus} 的运行时实现（clean-room）。
 *
 * <p>与 MG 自家 {@code io.mirage.eventbus.EventBus} 同构但独立：
 * 它扫描的是 {@link SubscribeEvent}（net.neoforged 版），按事件类型分桶分发。
 *
 * <p>第一版语义最小化：
 * <ul>
 *   <li>{@link #register(Object)}：扫描实例的 declared methods，单参数 + 注解即入桶；</li>
 *   <li>{@link #post(Object)}：按事件精确类型分发（不做继承层次遍历）；</li>
 *   <li>监听器抛异常仅打印，不中断其他监听器，不静默吞掉。</li>
 * </ul>
 */
public final class NeoEventBusImpl implements IEventBus {

    private final Map<Class<?>, List<Method>> listeners = new ConcurrentHashMap<>();
    private final Map<Object, List<Method>> ownerMethods = new HashMap<>();

    @Override
    public void register(Object target) {
        int found = 0;
        for (Method m : target.getClass().getDeclaredMethods()) {
            if (m.isAnnotationPresent(SubscribeEvent.class) && m.getParameterCount() == 1) {
                m.setAccessible(true);
                Class<?> eventType = m.getParameterTypes()[0];
                listeners.computeIfAbsent(eventType, k -> new ArrayList<>()).add(m);
                ownerMethods.computeIfAbsent(target, k -> new ArrayList<>()).add(m);
                found++;
            }
        }
        System.out.println("[Mirage:NeoBus] register " + target.getClass().getName()
                + " -> " + found + " 个 @SubscribeEvent 方法");
    }

    @Override
    public boolean post(Object event) {
        List<Method> methods = listeners.get(event.getClass());
        if (methods == null || methods.isEmpty()) {
            return false;
        }
        boolean any = false;
        for (Method m : methods) {
            for (Map.Entry<Object, List<Method>> e : ownerMethods.entrySet()) {
                if (e.getValue().contains(m)) {
                    try {
                        m.invoke(e.getKey(), event);
                    } catch (Exception ex) {
                        System.err.println("[Mirage:NeoBus] 监听器 " + m + " 抛错: " + ex);
                    }
                    any = true;
                    break;
                }
            }
        }
        return any;
    }
}
