package net.neoforged.fml;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * 模组间通信（IMC）注册表：{@code InterModEnqueueEvent} 阶段各 mod 用
 * {@link #sendTo(String, String, Supplier)} 发送消息，{@code InterModProcessEvent}
 * 阶段用 {@link #getMessages(String, String)} 读取。
 *
 * <p>MG 为运行时桥接 stub，消息在进程内存储，不做真实网络。
 */
public final class InterModComms {

    private record Entry(String toModId, String methodName, Supplier<Object> supplier) {}

    private static final List<Entry> REGISTRY = new CopyOnWriteArrayList<>();

    private InterModComms() {}

    /** 向目标 mod 的某 methodName 发送一条 IMC 消息（延迟由 supplier 提供）。 */
    public static void sendTo(String toModId, String methodName, Supplier<Object> messageSupplier) {
        REGISTRY.add(new Entry(toModId, methodName, messageSupplier));
    }

    /** 取目标 mod / methodName 下全部消息负载（已解析）。 */
    public static Stream<Object> getMessages(String toModId, String methodName) {
        return REGISTRY.stream()
                .filter(e -> e.toModId().equals(toModId) && e.methodName().equals(methodName))
                .map(e -> safeGet(e.supplier()));
    }

    /** 取某 methodName 下全部消息（不限目标 mod）。 */
    public static Stream<Object> getMessages(String methodName) {
        return REGISTRY.stream()
                .filter(e -> e.methodName().equals(methodName))
                .map(e -> safeGet(e.supplier()));
    }

    private static Object safeGet(Supplier<Object> s) {
        try {
            return s.get();
        } catch (Throwable t) {
            return null;
        }
    }

    /** 仅供测试/诊断：清空注册表。 */
    public static void clear() {
        REGISTRY.clear();
    }

    /** 当前注册表快照（诊断用）。 */
    public static Map<String, Long> snapshot() {
        return REGISTRY.stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        e -> e.toModId() + ":" + e.methodName(),
                        java.util.stream.Collectors.counting()));
    }
}
