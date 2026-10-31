/*
 * Mirage Fabric Compat — clean-room bridge stub of Fabric API's ServerPlayNetworking.
 *
 * Fabric API 原作以 Apache License 2.0 发布；本文件仅镜像其公开 API 形状，
 * 实现转发到 io.mirage.fabric.runtime.FabricNetworkingBridge。
 *
 * 与真实 fabric-api 的差异（桥接 stub 限制，见 docs/Fabric兼容层缺口清单.md）：
 *   - Context 不提供 packetContext()（依赖 Fabric mixin 注入原版的方法，桥接侧不存在）；
 *   - getSendable/canSend 以「已登记 receiver 的 payload id」近似「对端声明可收」；
 *   - reconfigure 为记录日志的空操作（未接原版 configuration 阶段）。
 */
package net.fabricmc.fabric.api.networking.v1;

import java.util.Objects;
import java.util.Set;

import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientCommonPacketListener;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;

import io.mirage.fabric.runtime.FabricNetworkingBridge;

/**
 * Offers access to play stage server-side networking functionalities.
 *
 * <p>Server-side networking functionalities include receiving serverbound packets, sending clientbound packets, and events related to server-side packet listeners.
 * Packets <strong>received</strong> by this class must be registered to {@link PayloadTypeRegistry#serverboundPlay()} on both ends.
 * Packets <strong>sent</strong> by this class must be registered to {@link PayloadTypeRegistry#clientboundPlay()} on both ends.
 * Packets must be registered before registering any receivers.
 */
public final class ServerPlayNetworking {
	/**
	 * Registers a handler for a payload type.
	 * A global receiver is registered to all connections, in the present and future.
	 */
	public static <T extends CustomPacketPayload> boolean registerGlobalReceiver(CustomPacketPayload.Type<T> type, PlayPayloadHandler<T> handler) {
		return FabricNetworkingBridge.registerServerGlobalReceiver(type, handler);
	}

	/**
	 * Removes the handler for a payload type.
	 */
	public static PlayPayloadHandler<?> unregisterGlobalReceiver(Identifier id) {
		return FabricNetworkingBridge.unregisterServerGlobalReceiver(id);
	}

	/**
	 * Gets all channel names which global receivers are registered for.
	 */
	public static Set<Identifier> getGlobalReceivers() {
		return FabricNetworkingBridge.getServerGlobalReceivers();
	}

	/**
	 * Registers a handler for a payload type, applied only to the given packet listener.
	 */
	public static <T extends CustomPacketPayload> boolean registerReceiver(ServerGamePacketListenerImpl packetListener, CustomPacketPayload.Type<T> type, PlayPayloadHandler<T> handler) {
		return FabricNetworkingBridge.registerServerReceiver(packetListener, type, handler);
	}

	/**
	 * Removes the handler for a payload type from the given packet listener.
	 */
	public static PlayPayloadHandler<?> unregisterReceiver(ServerGamePacketListenerImpl packetListener, Identifier id) {
		return FabricNetworkingBridge.unregisterServerReceiver(packetListener, id);
	}

	/**
	 * Gets all the channel names that the server can receive packets on.
	 */
	public static Set<Identifier> getReceived(ServerPlayer player) {
		Objects.requireNonNull(player, "Server player cannot be null");

		return getReceived(player.connection);
	}

	/**
	 * Gets all the channel names that the server can receive packets on.
	 */
	public static Set<Identifier> getReceived(ServerGamePacketListenerImpl listener) {
		Objects.requireNonNull(listener, "Server game packet listener cannot be null");

		return FabricNetworkingBridge.getServerReceived(listener);
	}

	/**
	 * Gets all channel names that the connected client declared the ability to receive a packets on.
	 */
	public static Set<Identifier> getSendable(ServerPlayer player) {
		Objects.requireNonNull(player, "Server player cannot be null");

		return getSendable(player.connection);
	}

	/**
	 * Gets all channel names that a connected client declared the ability to receive a packets on.
	 */
	public static Set<Identifier> getSendable(ServerGamePacketListenerImpl listener) {
		Objects.requireNonNull(listener, "Server game packet listener cannot be null");

		return FabricNetworkingBridge.getServerSendable(listener);
	}

	/**
	 * Checks if the connected client declared the ability to receive a packet on a specified channel name.
	 */
	public static boolean canSend(ServerPlayer player, Identifier channelName) {
		Objects.requireNonNull(player, "Server player cannot be null");

		return canSend(player.connection, channelName);
	}

	/**
	 * Checks if the connected client declared the ability to receive a specific type of packet.
	 */
	public static boolean canSend(ServerPlayer player, CustomPacketPayload.Type<?> type) {
		Objects.requireNonNull(player, "Server player cannot be null");

		return canSend(player.connection, type.id());
	}

	/**
	 * Checks if the connected client declared the ability to receive a packet on a specified channel name.
	 */
	public static boolean canSend(ServerGamePacketListenerImpl listener, Identifier channelName) {
		Objects.requireNonNull(listener, "Server game packet listener cannot be null");
		Objects.requireNonNull(channelName, "Channel name cannot be null");

		return FabricNetworkingBridge.getServerSendable(listener).contains(channelName);
	}

	/**
	 * Checks if the connected client declared the ability to receive a specific type of packet.
	 */
	public static boolean canSend(ServerGamePacketListenerImpl listener, CustomPacketPayload.Type<?> type) {
		Objects.requireNonNull(listener, "Server game packet listener cannot be null");
		Objects.requireNonNull(type, "Packet type cannot be null");

		return canSend(listener, type.id());
	}

	/**
	 * Creates a packet which may be sent to a connected client.
	 */
	public static <T extends CustomPacketPayload> Packet<ClientCommonPacketListener> createClientboundPacket(T packet) {
		return FabricNetworkingBridge.createClientboundPacket(packet);
	}

	/**
	 * Gets the packet sender which sends packets to the connected client.
	 */
	public static PacketSender getSender(ServerPlayer player) {
		Objects.requireNonNull(player, "Server player cannot be null");

		return getSender(player.connection);
	}

	/**
	 * Gets the packet sender which sends packets to the connected client.
	 */
	public static PacketSender getSender(ServerGamePacketListenerImpl listener) {
		Objects.requireNonNull(listener, "Server game packet listener cannot be null");

		return FabricNetworkingBridge.serverSender(listener);
	}

	/**
	 * Sends a packet to a player.
	 */
	public static void send(ServerPlayer player, CustomPacketPayload payload) {
		Objects.requireNonNull(player, "Server player cannot be null");
		Objects.requireNonNull(payload, "Payload cannot be null");
		Objects.requireNonNull(payload.type(), "CustomPacketPayload#type() cannot return null for payload class: " + payload.getClass());

		player.connection.send(createClientboundPacket(payload));
	}

	/**
	 * Put the player back into configuration phase and re-run all of the configuration tasks.
	 *
	 * <p>桥接 stub：仅记录日志（未接原版 configuration 阶段）。
	 */
	public static void reconfigure(ServerPlayer player) {
		Objects.requireNonNull(player, "Server player cannot be null");

		reconfigure(player.connection);
	}

	/**
	 * Put the player back into configuration phase and re-run all of the configuration tasks.
	 *
	 * <p>桥接 stub：仅记录日志（未接原版 configuration 阶段）。
	 */
	public static void reconfigure(ServerGamePacketListenerImpl listener) {
		Objects.requireNonNull(listener, "Server game packet listener cannot be null");

		FabricNetworkingBridge.reconfigure(listener);
	}

	private ServerPlayNetworking() {
	}

	/**
	 * A thread-safe packet handler utilizing {@link CustomPacketPayload}.
	 * @param <T> the type of the packet
	 */
	@FunctionalInterface
	public interface PlayPayloadHandler<T extends CustomPacketPayload> {
		/**
		 * Handles the incoming packet. This is called on the server thread, and can safely
		 * manipulate the world.
		 *
		 * @param payload the packet payload
		 * @param context the play networking context
		 */
		void receive(T payload, Context context);
	}

	public interface Context {
		/**
		 * @return The MinecraftServer instance
		 */
		MinecraftServer server();

		/**
		 * @return The player that received the packet
		 */
		ServerPlayer player();

		/**
		 * @return The packet sender
		 */
		PacketSender responseSender();
	}
}
