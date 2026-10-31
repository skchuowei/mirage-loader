/*
 * Mirage Fabric Compat — clean-room bridge stub of Fabric API's PacketSender.
 *
 * Fabric API 原作以 Apache License 2.0 发布；本文件仅镜像其公开 API 形状。
 */
package net.fabricmc.fabric.api.networking.v1;

import io.netty.channel.ChannelFutureListener;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Represents something that supports sending packets to channels.
 * Any packets sent must be registered in the appropriate registry.
 */
public interface PacketSender {
	/**
	 * Creates a packet from a packet payload.
	 */
	Packet<?> createPacket(CustomPacketPayload payload);

	/**
	 * Sends a packet.
	 */
	default void sendPacket(Packet<?> packet) {
		sendPacket(packet, null);
	}

	/**
	 * Sends a packet.
	 */
	default void sendPacket(CustomPacketPayload payload) {
		sendPacket(createPacket(payload));
	}

	/**
	 * Sends a packet.
	 *
	 * @param packet the packet
	 * @param callback an optional callback to execute after the packet is sent, may be {@code null}.
	 */
	void sendPacket(Packet<?> packet, ChannelFutureListener callback);

	/**
	 * Sends a packet.
	 *
	 * @param payload the payload
	 * @param callback an optional callback to execute after the packet is sent, may be {@code null}.
	 */
	default void sendPacket(CustomPacketPayload payload, ChannelFutureListener callback) {
		sendPacket(createPacket(payload), callback);
	}

	/**
	 * Disconnects the player.
	 */
	void disconnect(Component disconnectReason);
}
