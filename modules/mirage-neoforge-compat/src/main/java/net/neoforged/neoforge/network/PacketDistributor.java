package net.neoforged.neoforge.network;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Mirage NeoForge 兼容层 —— 发送入口（新 API）。
 *
 * <p>对应真实 NeoForge 的 {@code net.neoforged.neoforge.network.PacketDistributor}。
 * 本地通路：消息不经 socket，直接派发到对侧 handler（见 ChannelRegistry）。</p>
 */
public final class PacketDistributor {

    private PacketDistributor() {}

    /** 客户端 -> 服务端。 */
    public static void sendToServer(CustomPacketPayload payload) {
        ChannelRegistry.dispatchServerbound(payload, null);
    }

    /** 服务端 -> 指定玩家。 */
    public static void sendToPlayer(ServerPlayer player, CustomPacketPayload payload) {
        ChannelRegistry.dispatchClientbound(payload, null);
    }

    /** 服务端 -> 所有在线玩家。 */
    public static void sendToAllPlayers(CustomPacketPayload payload) {
        ChannelRegistry.dispatchClientbound(payload, null);
    }
}
