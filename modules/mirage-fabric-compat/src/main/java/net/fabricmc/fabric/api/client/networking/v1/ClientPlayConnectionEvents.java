/*
 * Mirage Fabric Compat — clean-room bridge stub of Fabric API's ClientPlayConnectionEvents.
 *
 * Fabric API 原作以 Apache License 2.0 发布；本文件仅镜像其公开 API 形状。
 * 事件由 io.mirage.fabric.runtime.FabricNetworkingBridge 派发，
 * 注入点见 core/mirage-mixin 的 FabricClientPlayConnectionMixin
 * （对齐 fabric-networking-api-v1 的 ClientPacketListenerMixin）。
 */
package net.fabricmc.fabric.api.client.networking.v1;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;

import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;
import net.fabricmc.fabric.api.networking.v1.PacketSender;

/**
 * Offers access to events related to the connection to a server on a logical client.
 */
public final class ClientPlayConnectionEvents {
	/**
	 * Event indicating a connection entered the PLAY state, ready for registering channel handlers.
	 */
	public static final Event<Init> INIT = EventFactory.createArrayBacked(Init.class, callbacks -> (listener, client) -> {
		for (Init callback : callbacks) {
			callback.onPlayInit(listener, client);
		}
	});

	/**
	 * An event for notification when the client play packet listener is ready to send packets to the server.
	 */
	public static final Event<Join> JOIN = EventFactory.createArrayBacked(Join.class, callbacks -> (listener, sender, client) -> {
		for (Join callback : callbacks) {
			callback.onPlayReady(listener, sender, client);
		}
	});

	/**
	 * An event for the disconnection of the client play packet listener.
	 *
	 * <p>No packets should be sent when this event is invoked.
	 */
	public static final Event<Disconnect> DISCONNECT = EventFactory.createArrayBacked(Disconnect.class, callbacks -> (listener, client) -> {
		for (Disconnect callback : callbacks) {
			callback.onPlayDisconnect(listener, client);
		}
	});

	private ClientPlayConnectionEvents() {
	}

	@FunctionalInterface
	public interface Init {
		void onPlayInit(ClientPacketListener listener, Minecraft client);
	}

	@FunctionalInterface
	public interface Join {
		void onPlayReady(ClientPacketListener listener, PacketSender sender, Minecraft client);
	}

	@FunctionalInterface
	public interface Disconnect {
		void onPlayDisconnect(ClientPacketListener listener, Minecraft client);
	}
}
