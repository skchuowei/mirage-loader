/*
 * Mirage Fabric Compat — clean-room bridge stub of Fabric API's ServerPlayConnectionEvents.
 *
 * Fabric API 原作以 Apache License 2.0 发布；本文件仅镜像其公开 API 形状。
 * 事件由 io.mirage.fabric.runtime.FabricNetworkingBridge 派发，
 * 注入点见 core/mirage-mixin 的 FabricServerPlayConnectionMixin /
 * FabricServerPayloadMixin（对齐 fabric-networking-api-v1 的语义）。
 */
package net.fabricmc.fabric.api.networking.v1;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;

import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;

/**
 * Offers access to events related to the connection to a client on a logical server while a client is in game.
 */
public final class ServerPlayConnectionEvents {
	/**
	 * Event indicating a connection entered the PLAY state, ready for registering channel handlers.
	 */
	public static final Event<Init> INIT = EventFactory.createArrayBacked(Init.class, callbacks -> (listener, server) -> {
		for (Init callback : callbacks) {
			callback.onPlayInit(listener, server);
		}
	});

	/**
	 * An event for notification when the server game packet listener is ready to send packets to the client.
	 */
	public static final Event<Join> JOIN = EventFactory.createArrayBacked(Join.class, callbacks -> (listener, sender, server) -> {
		for (Join callback : callbacks) {
			callback.onPlayReady(listener, sender, server);
		}
	});

	/**
	 * An event for the disconnection of the server game packet listener.
	 *
	 * <p>No packets should be sent when this event is invoked.
	 */
	public static final Event<Disconnect> DISCONNECT = EventFactory.createArrayBacked(Disconnect.class, callbacks -> (listener, server) -> {
		for (Disconnect callback : callbacks) {
			callback.onPlayDisconnect(listener, server);
		}
	});

	private ServerPlayConnectionEvents() {
	}

	@FunctionalInterface
	public interface Init {
		void onPlayInit(ServerGamePacketListenerImpl listener, MinecraftServer server);
	}

	@FunctionalInterface
	public interface Join {
		void onPlayReady(ServerGamePacketListenerImpl listener, PacketSender sender, MinecraftServer server);
	}

	@FunctionalInterface
	public interface Disconnect {
		void onPlayDisconnect(ServerGamePacketListenerImpl listener, MinecraftServer server);
	}
}
