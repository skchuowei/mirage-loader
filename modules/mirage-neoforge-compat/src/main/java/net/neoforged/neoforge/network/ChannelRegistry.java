package net.neoforged.neoforge.network;

import net.neoforged.neoforge.network.handling.IPayloadHandler;

/**
 * Mirage NeoForge 兼容层 —— 网络包本地通路。
 *
 * <p>单机 / 单 JVM 环境（Mirage 把客户端与服务端逻辑跑在同一进程）里，
 * "网络包"实际退化为进程内的消息派发：{@code sendToServer} 直达服务端侧
 * handler，{@code sendToPlayer / sendToAllPlayers} 直达客户端侧 handler。
 * 编解码器（StreamCodec）在此通路中不会被真正执行——消息对象直接传引用；
 * 但仍被登记保存，供未来接入真实 socket 传输时复用。</p>
 *
 * <p>线程语义：{@link Context#enqueueWork(Runnable)} 与派发本身在调用线程
 * 立即执行（不做主线程排队）。若 mod 依赖严格的主线程时序，需在 mod 侧自行
 * 用 {@code player.getServer().execute(...)}。</p>
 */
public final class ChannelRegistry {

    /** 派发方向：handler 注册时期望接收的方向。 */
    public enum Direction {
        /** 服务端收（客户端发出）。 */
        SERVERBOUND,
        /** 客户端收（服务端发出）。 */
        CLIENTBOUND,
        /** 双向。 */
        BIDIRECTIONAL
    }

    /** 单个频道的登记项：方向 + handler。 */
    public static final class Entry {
        public final Direction direction;
        public final Object handler; // IPayloadHandler<T> 或 旧 API consumer

        public Entry(Direction direction, Object handler) {
            this.direction = direction;
            this.handler = handler;
        }
    }

    private static final java.util.Map<Object, Entry> HANDLERS = new java.util.concurrent.ConcurrentHashMap<>();

    private ChannelRegistry() {}

    /** 按 key（CustomPacketPayload.Type 或旧 API 的消息 Class）登记 handler。 */
    public static void register(Object key, Direction direction, Object handler) {
        Entry prev = HANDLERS.putIfAbsent(key, new Entry(direction, handler));
        if (prev != null) {
            System.out.println("[Mirage:NeoNet] 警告：payload " + key + " 已注册过 handler，忽略重复注册");
        }
    }

    /** 客户端 -> 服务端：找到 SERVERBOUND / BIDIRECTIONAL 的 handler 并调用。 */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static boolean dispatchServerbound(Object payload, Object key) {
        Entry e = key != null ? HANDLERS.get(key) : HANDLERS.get(payload.getClass());
        if (e == null) {
            System.out.println("[Mirage:NeoNet] sendToServer：payload " + payload.getClass().getName()
                    + " 无已注册 handler（mod 未在 RegisterPayloadHandlersEvent / messageBuilder 中登记）");
            return false;
        }
        if (e.direction == Direction.CLIENTBOUND) {
            System.out.println("[Mirage:NeoNet] 警告：payload " + payload.getClass().getName()
                    + " 只注册了客户端方向，sendToServer 不可达");
            return false;
        }
        Object h = e.handler;
        if (h instanceof java.util.function.BiConsumer) {
            // 旧 API：BiConsumer<MSG, Supplier<NetworkEvent.Context>>
            java.util.function.Supplier<NetworkEvent.Context> sup = () -> new NetworkEvent.Context(NetworkDirection.PLAY_TO_SERVER);
            ((java.util.function.BiConsumer) h).accept(payload, sup);
        } else {
            // 新 API：IPayloadHandler<T>
            ((IPayloadHandler) h).handle(payload, new NetworkEvent.Context(NetworkDirection.PLAY_TO_SERVER));
        }
        return true;
    }

    /** 服务端 -> 客户端：找到 CLIENTBOUND / BIDIRECTIONAL 的 handler 并调用。 */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static boolean dispatchClientbound(Object payload, Object key) {
        Entry e = key != null ? HANDLERS.get(key) : HANDLERS.get(payload.getClass());
        if (e == null) {
            System.out.println("[Mirage:NeoNet] sendToClient：payload " + payload.getClass().getName() + " 无已注册 handler");
            return false;
        }
        if (e.direction == Direction.SERVERBOUND) {
            System.out.println("[Mirage:NeoNet] 警告：payload " + payload.getClass().getName()
                    + " 只注册了服务端方向，sendToPlayer 不可达");
            return false;
        }
        Object h = e.handler;
        if (h instanceof java.util.function.BiConsumer) {
            java.util.function.Supplier<NetworkEvent.Context> sup = () -> new NetworkEvent.Context(NetworkDirection.PLAY_TO_CLIENT);
            ((java.util.function.BiConsumer) h).accept(payload, sup);
        } else {
            ((IPayloadHandler) h).handle(payload, new NetworkEvent.Context(NetworkDirection.PLAY_TO_CLIENT));
        }
        return true;
    }

    /** 已注册 payload 数（诊断用）。 */
    public static int size() {
        return HANDLERS.size();
    }
}
