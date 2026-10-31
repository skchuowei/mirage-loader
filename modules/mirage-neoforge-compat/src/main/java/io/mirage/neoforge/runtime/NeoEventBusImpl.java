package io.mirage.neoforge.runtime;

import io.mirage.api.log.MirageLog;

import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * NeoForge {@link IEventBus} 的运行时实现（clean-room）。
 *
 * <p>本轮语义：
 * <ul>
 *   <li>{@code register}：扫描目标类及其父类的 {@link SubscribeEvent} 方法；</li>
 *   <li>{@code addListener}：lambda / 方法引用，事件类型从泛型签名推断；</li>
 *   <li>{@code post}：按事件类型 → 父类 → 接口顺序合并监听器，按优先级稳定排序；</li>
 *   <li>事件被取消后，跳过未声明 {@code receiveCancelled=true} 的后续监听器；</li>
 *   <li>监听器抛异常只打印，不中断其他监听器。</li>
 * </ul>
 */
public final class NeoEventBusImpl implements IEventBus {

    private static final class Listener {
        final Object owner;
        final Method method;
        final Consumer<Object> consumer;
        final EventPriority priority;
        final boolean receiveCancelled;

        Listener(Object owner, Method method, Consumer<Object> consumer, EventPriority priority, boolean receiveCancelled) {
            this.owner = owner;
            this.method = method;
            this.consumer = consumer;
            this.priority = priority;
            this.receiveCancelled = receiveCancelled;
        }

        void invoke(Event event) throws Exception {
            if (consumer != null) {
                consumer.accept(event);
            } else {
                method.invoke(Modifier.isStatic(method.getModifiers()) ? null : owner, event);
            }
        }
    }

    private final Map<Class<?>, CopyOnWriteArrayList<Listener>> byType = new ConcurrentHashMap<>();
    private final Map<Object, List<Listener>> byOwner = new ConcurrentHashMap<>();

    @Override
    public void register(Object target) {
        int found = 0;
        for (Class<?> c = target.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                SubscribeEvent ann = m.getAnnotation(SubscribeEvent.class);
                if (ann == null || m.getParameterCount() != 1 || m.isSynthetic()) {
                    continue;
                }
                Class<?> eventType = m.getParameterTypes()[0];
                if (!Event.class.isAssignableFrom(eventType)) {
                    MirageLog.of("Mirage:NeoBus").warn("" + m + " 的参数不是 Event 子类，已跳过");
                    continue;
                }
                m.setAccessible(true);
                add(new Listener(target, m, null, toPriority(ann.priority()), ann.receiveCancelled()), eventType);
                found++;
            }
        }
        MirageLog.of("Mirage:NeoBus").info("register " + target.getClass().getName()
                + " -> " + found + " 个 @SubscribeEvent 方法");
    }

    @Override
    public void unregister(Object target) {
        List<Listener> removed = byOwner.remove(target);
        if (removed == null) {
            return;
        }
        for (CopyOnWriteArrayList<Listener> list : byType.values()) {
            list.removeAll(removed);
        }
        MirageLog.of("Mirage:NeoBus").info("unregister " + target.getClass().getName()
                + " -> " + removed.size() + " 个监听器");
    }

    @Override
    public <T extends Event> void addListener(Consumer<T> consumer) {
        addListener(EventPriority.NORMAL, false, consumer);
    }

    @Override
    public <T extends Event> void addListener(EventPriority priority, Consumer<T> consumer) {
        addListener(priority, false, consumer);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends Event> void addListener(EventPriority priority, boolean receiveCancelled, Consumer<T> consumer) {
        Class<?> eventType = resolveEventType(consumer);
        add(new Listener(consumer, null, (Consumer<Object>) (Consumer<?>) consumer, priority, receiveCancelled), eventType);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends Event> void addListener(EventPriority priority, boolean receiveCancelled, Class<T> eventType, Consumer<T> consumer) {
        add(new Listener(consumer, null, (Consumer<Object>) (Consumer<?>) consumer, priority, receiveCancelled), eventType);
    }

    @Override
    public boolean post(Object event) {
        if (event instanceof Event e) {
            return post(e);
        }
        // 非 Event 对象仍按精确类型分发一次，兼容内部旧调用。
        if (event == null) {
            return false;
        }
        return dispatch(event, collect(event.getClass()));
    }

    @Override
    public boolean post(Event event) {
        return dispatch(event, collect(event.getClass()));
    }

    private void add(Listener listener, Class<?> eventType) {
        byType.computeIfAbsent(eventType, k -> new CopyOnWriteArrayList<>()).add(listener);
        if (listener.method != null) {
            byOwner.computeIfAbsent(listener.owner, k -> new CopyOnWriteArrayList<>()).add(listener);
        }
    }

    private boolean dispatch(Object event, List<Listener> listeners) {
        if (listeners.isEmpty()) {
            return false;
        }
        boolean any = false;
        boolean canceled = event instanceof Event e && e.isCanceled();
        for (Listener l : listeners) {
            if (canceled && !l.receiveCancelled) {
                continue;
            }
            try {
                l.invoke((Event) event);
                any = true;
            } catch (Throwable t) {
                MirageLog.of("Mirage:NeoBus").warn("监听器 " + l.method + " 抛错", t);
                Throwable cause = t.getCause();
                if (cause != null) {
                    cause.printStackTrace();
                }
            }
            if (event instanceof Event e) {
                canceled = e.isCanceled();
            }
        }
        return any;
    }

    /** 事件类型 → 父类 → 各级接口，去重后按优先级稳定排序。 */
    private List<Listener> collect(Class<?> type) {
        Map<String, Listener> merged = new LinkedHashMap<>();
        Set<Listener> seen = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        for (Class<?> c = type; c != null && Event.class.isAssignableFrom(c); c = c.getSuperclass()) {
            collectInto(c, merged, seen);
            for (Class<?> itf : allInterfaces(c)) {
                collectInto(itf, merged, seen);
            }
        }
        List<Listener> list = new ArrayList<>(merged.values());
        list.sort(Comparator.comparingInt(l -> l.priority.ordinal()));
        return list;
    }

    private void collectInto(Class<?> type, Map<String, Listener> merged, Set<Listener> seen) {
        List<Listener> list = byType.get(type);
        if (list == null) {
            return;
        }
        for (Listener l : list) {
            if (seen.add(l)) {
                merged.put(l.owner + "#" + (l.method == null ? System.identityHashCode(l.consumer) : l.method), l);
            }
        }
    }

    private static Set<Class<?>> allInterfaces(Class<?> type) {
        Set<Class<?>> result = new HashSet<>();
        collectInterfaces(type, result);
        return result;
    }

    private static void collectInterfaces(Class<?> type, Set<Class<?>> out) {
        for (Class<?> itf : type.getInterfaces()) {
            if (!Event.class.isAssignableFrom(itf)) {
                continue;
            }
            if (out.add(itf)) {
                collectInterfaces(itf, out);
            }
        }
        Class<?> superclass = type.getSuperclass();
        if (superclass != null) {
            collectInterfaces(superclass, out);
        }
    }

    private static EventPriority toPriority(int priority) {
        for (EventPriority p : EventPriority.VALUES) {
            if (p.ordinal() == priority) {
                return p;
            }
        }
        return EventPriority.NORMAL;
    }

    /** 从 lambda / 方法引用的泛型签名里解析事件类型；解析不到直接报错，不静默。 */
    private static Class<?> resolveEventType(Consumer<?> consumer) {
        for (Type t : consumer.getClass().getGenericInterfaces()) {
            Class<?> raw = rawType(t);
            if (raw == Consumer.class && t instanceof ParameterizedType pt) {
                Class<?> arg = classOf(pt.getActualTypeArguments()[0]);
                if (arg != null && Event.class.isAssignableFrom(arg)) {
                    return arg;
                }
            }
        }
        for (Method m : consumer.getClass().getDeclaredMethods()) {
            if (m.getParameterCount() == 1 && Event.class.isAssignableFrom(m.getParameterTypes()[0])) {
                return m.getParameterTypes()[0];
            }
        }
        throw new IllegalArgumentException(
                "[Mirage:NeoBus] 无法从 " + consumer.getClass().getName()
                        + " 推断事件类型；请改用 addListener(EventPriority, boolean, Class, Consumer) 显式指定");
    }

    private static Class<?> rawType(Type t) {
        return t instanceof Class<?> c ? c : t instanceof ParameterizedType pt ? rawType(pt.getRawType()) : null;
    }

    private static Class<?> classOf(Type t) {
        return t instanceof Class<?> c ? c : null;
    }
}
