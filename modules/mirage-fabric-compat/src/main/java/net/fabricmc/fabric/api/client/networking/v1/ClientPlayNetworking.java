/*
 * Mirage Fabric Compat — clean-room bridge stub of Fabric API's ClientPlayNetworking.
 *
 * Fabric API 原作以 Apache License 2.0 发布；本文件仅镜像其公开 API 形状，
 * 实现转发到 io.mirage.fabric.runtime.FabricNetworkingBridge。
 *
 * 与真实 fabric-api 的差异（桥接 stub 限制，见 docs/Fabric兼容层缺口清单.md）：
 *   - Context 不提供 packetContext()；
 *   - canSend 以「已登记 receiver 的 payload id」近似「服务端声明可收」。
 */
package net.fabricmc.fabric.api.client.networking.v1;

import java.util.Objects;
import java.util.Set;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ServerCommonPacketListener;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import net.fabricmc.fabric.api.networking.v1.PacketSender;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import io.mirage.fabric.runtime.FabricNetworkingBridge;

/**
 * Offers access to play stage client-side networking functionalities.
 *
 * <p>Client-side networking functionalities include receiving clientbound packets,
 * sending serverbound packets, and events related to client-side packet listeners.
 * Packets <strong>received</strong> by this class must be registered to {@link PayloadTypeRegistry#clientboundPlay()} on both ends.
 * Packets <strong>sent</strong> by this class must be registered to {@link PayloadTypeRegistry#serverboundPlay()} on both ends.
 * Packets must be registered before registering any receivers.
 */
public final class ClientPlayNetworking {
	/**
	 * Registers a handler for a payload type.
	 * A global receiver is registered to all connections, in the present and future.
	 */
	public static <T extends CustomPacketPayload> boolean registerGlobalReceiver(CustomPacketPayload.Type<T> type, PlayPayloadHandler<T> handler) {
		return FabricNetworkingBridge.registerClientGlobalReceiver(type, handler);
	}

	/**
	 * Removes the handler for a payload type.
	 */
	public static PlayPayloadHandler<?> unregisterGlobalReceiver(Identifier id) {
		return FabricNetworkingBridge.unregisterClientGlobalReceiver(id);
	}

	/**
	 * Gets all channel names which global receivers are registered for.
	 */
	public static Set<Identifier> getGlobalReceivers() {
		return FabricNetworkingBridge.getClientGlobalReceivers();
	}

	/**
	 * Registers a handler for a payload type, applied to the current connection.
	 *
	 * @throws IllegalStateException if the client is not connected to a server
	 */
	public static <T extends CustomPacketPayload> boolean registerReceiver(CustomPacketPayload.Type<T> type, PlayPayloadHandler<T> handler) {
		final ClientPacketListener connection = Minecraft.getInstance().getConnection();

		if (connection != null) {
			return FabricNetworkingBridge.registerClientReceiver(connection, type, handler);
		}

		throw new IllegalStateException("Cannot register receiver while not in game!");
	}

	/**
	 * Removes a handler for a payload id.
	 *
	 * @throws IllegalStateException if the client is not connected to a server
	 */
	public static PlayPayloadHandler<?> unregisterReceiver(Identifier id) {
		final ClientPacketListener connection = Minecraft.getInstance().getConnection();

		if (connection != null) {
			return FabricNetworkingBridge.unregisterClientReceiver(connection, id);
		}

		throw new IllegalStateException("Cannot unregister receiver while not in game!");
	}

	/**
	 * Gets all the channel names that the client can receive packets on.
	 *
	 * @throws IllegalStateException if the client is not connected to a server
	 */
	public static Set<Identifier> getReceived() throws IllegalStateException {
		final ClientPacketListener connection = Minecraft.getInstance().getConnection();

		if (connection != null) {
			return FabricNetworkingBridge.getClientReceived(connection);
		}

		throw new IllegalStateException("Cannot get a list of channels the client can receive packets on while not in game!");
	}

	/**
	 * Gets all channel names that the connected server declared the ability to receive a packets on.
	 *
	 * @throws IllegalStateException if the client is not connected to a server
	 */
	public static Set<Identifier> getSendable() throws IllegalStateException {
		final ClientPacketListener connection = Minecraft.getInstance().getConnection();

		if (connection != null) {
			return FabricNetworkingBridge.getClientSendable(connection);
		}

		throw new IllegalStateException("Cannot get a list of channels the server can receive packets on while not in game!");
	}

	/**
	 * Checks if the connected server declared the ability to receive a payload on a specified channel name.
	 */
	public static boolean canSend(Identifier channelName) throws IllegalArgumentException {
		final ClientPacketListener connection = Minecraft.getInstance().getConnection();

		if (connection != null) {
			return FabricNetworkingBridge.getClientSendable(connection).contains(channelName);
		}

		return false;
	}

	/**
	 * Checks if the connected server declared the ability to receive a payload on a specified channel name.
	 */
	public static boolean canSend(CustomPacketPayload.Type<?> type) {
		return canSend(type.id());
	}

	/**
	 * Creates a payload which may be sent to the connected server.
	 */
	public static <T extends CustomPacketPayload> Packet<ServerCommonPacketListener> createServerboundPacket(T packet) {
		return FabricNetworkingBridge.createServerboundPacket(packet);
	}

	/**
	 * Gets the payload sender which sends packets to the connected server.
	 *
	 * @throws IllegalStateException if the client is not connected to a server
	 */
	public static PacketSender getSender() throws IllegalStateException {
		final ClientPacketListener connection = Minecraft.getInstance().getConnection();

		if (connection != null) {
			return FabricNetworkingBridge.clientSender(connection);
		}

		throw new IllegalStateException("Cannot get payload sender when not in game!");
	}

	/**
	 * Sends a payload to the connected server.
	 *
	 * @throws IllegalStateException if the client is not connected to a server
	 */
	public static void send(CustomPacketPayload payload) {
		Objects.requireNonNull(payload, "Payload cannot be null");
		Objects.requireNonNull(payload.type(), "CustomPacketPayload#type() cannot return null for payload class: " + payload.getClass());

		final ClientPacketListener connection = Minecraft.getInstance().getConnection();

		if (connection != null) {
			connection.send(createServerboundPacket(payload));
			return;
		}

		throw new IllegalStateException("Cannot send packets when not in game!");
	}

	private ClientPlayNetworking() {
	}

	/**
	 * A thread-safe payload handler utilizing {@link CustomPacketPayload}.
	 * @param <T> the type of the packet
	 */
	@FunctionalInterface
	public interface PlayPayloadHandler<T extends CustomPacketPayload> {
		/**
		 * Handles the incoming payload. This is called on the render thread, and can safely
		 * call client methods.
		 *
		 * @param payload the payload
		 * @param context the play networking context
		 */
		void receive(T payload, Context context);
	}

	public interface Context {
		/**
		 * @return The Minecraft instance
		 */
		Minecraft client();

		/**
		 * @return The player that received the payload
		 */
		LocalPlayer player();

		/**
		 * @return The packet sender
		 */
		PacketSender responseSender();
	}
}
