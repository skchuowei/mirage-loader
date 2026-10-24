package io.mirage.loader.sandbox;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * {@link RecordingSink} 的线程安全实现。触达/事件用去重集合收集，DeferredRegister 计数用原子累加。
 */
public final class RecordingSinkImpl implements RecordingSink {

    private final Set<String> apis = ConcurrentHashMap.newKeySet();
    private final Set<String> events = ConcurrentHashMap.newKeySet();
    private final AtomicInteger drCount = new AtomicInteger();

    @Override
    public void touch(String api) {
        if (api != null && !api.isEmpty()) apis.add(api);
    }

    @Override
    public void registerEvent(String eventClass) {
        if (eventClass != null && !eventClass.isEmpty()) events.add(eventClass);
    }

    @Override
    public void deferredRegisterCreated() {
        drCount.incrementAndGet();
    }

    /** 触达的 API 列表（排序保证确定性）。 */
    public List<String> apis() {
        List<String> l = new ArrayList<>(apis);
        java.util.Collections.sort(l);
        return l;
    }

    /** 注册的事件类型列表（排序保证确定性）。 */
    public List<String> events() {
        List<String> l = new ArrayList<>(events);
        java.util.Collections.sort(l);
        return l;
    }

    public int deferredRegisterCount() {
        return drCount.get();
    }
}
