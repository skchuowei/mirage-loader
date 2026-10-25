package net.neoforged.neoforge.network.registration;

import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.ChannelRegistry;
import net.neoforged.neoforge.network.handling.IPayloadHandler;

/**
 * Mirage NeoForge 兼容层 —— payload 登记器（新 API）。
 *
 * <p>对应真实 NeoForge 的
 * {@code net.neoforged.neoforge.network.registration.PayloadRegistrar}。
 * 在 {@code RegisterPayloadHandlersEvent.registrar("1")} 中获取，随后：
 * <pre>{@code
 * registrar.playToServer(MyPayload.TYPE, MyPayload.CODEC, (payload, ctx) -> { ... });
 * registrar.playToClient(OtherPayload.TYPE, OtherPayload.CODEC, handler);
 * }</pre></p>
 */
public final class PayloadRegistrar {

    /** 协议版本标记（仅记录，本地通路无协商）。 */
    private final String version;

    public PayloadRegistrar(String version) {
        this.version = version;
    }

    /** 本 registrar 的协议版本。 */
    public String version() {
        return version;
    }

    /** 登记客户端 -> 服务端 的 payload + handler。 */
    public <T extends CustomPacketPayload> PayloadRegistrar playToServer(
            CustomPacketPayload.Type<T> type,
            StreamCodec<? super net.minecraft.network.RegistryFriendlyByteBuf, T> codec,
            IPayloadHandler<T> handler) {
        ChannelRegistry.register(type, ChannelRegistry.Direction.SERVERBOUND, handler);
        return this;
    }

    /** 服务端 -> 客户端。 */
    public <T extends CustomPacketPayload> PayloadRegistrar playToClient(
            CustomPacketPayload.Type<T> type,
            StreamCodec<? super net.minecraft.network.RegistryFriendlyByteBuf, T> codec,
            IPayloadHandler<T> handler) {
        ChannelRegistry.register(type, ChannelRegistry.Direction.CLIENTBOUND, handler);
        return this;
    }

    /** 双向。 */
    public <T extends CustomPacketPayload> PayloadRegistrar playBidirectional(
            CustomPacketPayload.Type<T> type,
            StreamCodec<? super net.minecraft.network.RegistryFriendlyByteBuf, T> codec,
            IPayloadHandler<T> handler) {
        ChannelRegistry.register(type, ChannelRegistry.Direction.BIDIRECTIONAL, handler);
        return this;
    }

    /** 无 handler 重载（mod 稍后用别的方式处理，仅登记 codec 占位）。 */
    public <T extends CustomPacketPayload> PayloadRegistrar playToServer(
            CustomPacketPayload.Type<T> type,
            StreamCodec<? super net.minecraft.network.RegistryFriendlyByteBuf, T> codec) {
        ChannelRegistry.register(type, ChannelRegistry.Direction.SERVERBOUND,
                (IPayloadHandler<T>) (payload, ctx) -> ctx.setPacketHandled(true));
        return this;
    }

    public <T extends CustomPacketPayload> PayloadRegistrar playToClient(
            CustomPacketPayload.Type<T> type,
            StreamCodec<? super net.minecraft.network.RegistryFriendlyByteBuf, T> codec) {
        ChannelRegistry.register(type, ChannelRegistry.Direction.CLIENTBOUND,
                (IPayloadHandler<T>) (payload, ctx) -> ctx.setPacketHandled(true));
        return this;
    }

    /** 声明配置阶段 channel（简化：同双向空实现）。 */
    public PayloadRegistrar configurationToServer(CustomPacketPayload.Type<? extends CustomPacketPayload> type,
            StreamCodec<?, ? extends CustomPacketPayload> codec) {
        return this;
    }
}
