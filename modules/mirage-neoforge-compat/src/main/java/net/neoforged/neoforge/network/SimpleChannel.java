package net.neoforged.neoforge.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.Identifier;

import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Mirage NeoForge 兼容层 —— 旧 API 简单频道。
 *
 * <p>对应 NeoForge 1.20.x / Forge 时代的 {@code SimpleChannel}：
 * <pre>{@code
 * SimpleChannel ch = NetworkRegistry.newSimpleChannel(Identifier.fromNamespaceAndPath("mymod", "main"));
 * ch.messageBuilder(MyMsg.class)
 *   .encoder(MyMsg::write)
 *   .decoder(MyMsg::read)
 *   .consumerMainThread((msg, ctx) -> { ... })
 *   .add();
 * ch.sendToServer(new MyMsg(...));
 * }</pre>
 * 本地通路：消息对象直接传引用派发（encoder/decoder 登记但不执行）。</p>
 */
public final class SimpleChannel {

    private final Identifier name;
    private final String version;

    public SimpleChannel(Identifier name, String version) {
        this.name = name;
        this.version = version;
    }

    public Identifier getName() {
        return name;
    }

    public String getVersion() {
        return version;
    }

    /** 开始构建一个消息类型。 */
    public <MSG> MessageBuilder<MSG> messageBuilder(Class<MSG> type) {
        return new MessageBuilder<>(this, type);
    }

    /** 消息构建器（encoder/decoder/consumer 链式，add() 落登记）。 */
    public static final class MessageBuilder<MSG> {
        private final SimpleChannel channel;
        private final Class<MSG> type;
        private BiConsumer<MSG, FriendlyByteBuf> encoder;
        private Function<FriendlyByteBuf, MSG> decoder;
        private BiConsumer<MSG, Supplier<NetworkEvent.Context>> consumer;

        private MessageBuilder(SimpleChannel channel, Class<MSG> type) {
            this.channel = channel;
            this.type = type;
        }

        public MessageBuilder<MSG> encoder(BiConsumer<MSG, FriendlyByteBuf> encoder) {
            this.encoder = encoder;
            return this;
        }

        public MessageBuilder<MSG> decoder(Function<FriendlyByteBuf, MSG> decoder) {
            this.decoder = decoder;
            return this;
        }

        /** 主线程消费（Mirage 本地通路同 consumerNetworkThread，立即执行）。 */
        public MessageBuilder<MSG> consumerMainThread(BiConsumer<MSG, Supplier<NetworkEvent.Context>> consumer) {
            this.consumer = consumer;
            return this;
        }

        /** 网络线程消费（Mirage 本地通路立即执行）。 */
        public MessageBuilder<MSG> consumerNetworkThread(BiConsumer<MSG, Supplier<NetworkEvent.Context>> consumer) {
            this.consumer = consumer;
            return this;
        }

        /** 完成登记：把 consumer 挂到 ChannelRegistry（按消息 Class 作 key）。 */
        public void add() {
            if (consumer == null) {
                throw new IllegalStateException("[Mirage:NeoNet] messageBuilder(" + type.getName() + ") 未设置 consumer 就 add()");
            }
            ChannelRegistry.register(type, ChannelRegistry.Direction.BIDIRECTIONAL, consumer);
        }
    }

    /** 客户端 -> 服务端 发送（本地直接派发 consumer）。 */
    public <MSG> void sendToServer(MSG msg) {
        ChannelRegistry.dispatchServerbound(msg, msg.getClass());
    }

    /** 服务端 -> 所有客户端（本地派发）。 */
    public <MSG> void sendToAll(MSG msg) {
        ChannelRegistry.dispatchClientbound(msg, msg.getClass());
    }

    /** 单机环境恒 true。 */
    public boolean isRemotePresent(Object connection) {
        return true;
    }
}
