/*
 * Mirage Fabric Compat — clean-room bridge stub of Fabric API's ClientTickEvents.
 *
 * Fabric API 原作以 Apache License 2.0 发布；本文件仅镜像其公开 API 形状。
 * 事件由 io.mirage.fabric.runtime.FabricClientTickBridge 派发，
 * 注入点见 core/mirage-mixin 的 FabricClientTickMixin /
 * FabricClientLevelTickMixin。
 */
package net.fabricmc.fabric.api.client.event.lifecycle.v1;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;

import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;

public final class ClientTickEvents {
	private ClientTickEvents() {
	}

	/**
	 * Called at the start of the client tick.
	 */
	public static final Event<StartTick> START_CLIENT_TICK = EventFactory.createArrayBacked(StartTick.class, callbacks -> client -> {
		for (StartTick event : callbacks) {
			event.onStartTick(client);
		}
	});

	/**
	 * Called at the end of the client tick.
	 */
	public static final Event<EndTick> END_CLIENT_TICK = EventFactory.createArrayBacked(EndTick.class, callbacks -> client -> {
		for (EndTick event : callbacks) {
			event.onEndTick(client);
		}
	});

	/**
	 * Called at the start of a ClientLevel's tick.
	 */
	public static final Event<StartLevelTick> START_LEVEL_TICK = EventFactory.createArrayBacked(StartLevelTick.class, callbacks -> level -> {
		for (StartLevelTick callback : callbacks) {
			callback.onStartTick(level);
		}
	});

	/**
	 * Called at the end of a ClientLevel's tick.
	 */
	public static final Event<EndLevelTick> END_LEVEL_TICK = EventFactory.createArrayBacked(EndLevelTick.class, callbacks -> level -> {
		for (EndLevelTick callback : callbacks) {
			callback.onEndTick(level);
		}
	});

	@FunctionalInterface
	public interface StartTick {
		void onStartTick(Minecraft client);
	}

	@FunctionalInterface
	public interface EndTick {
		void onEndTick(Minecraft client);
	}

	@FunctionalInterface
	public interface StartLevelTick {
		void onStartTick(ClientLevel level);
	}

	@FunctionalInterface
	public interface EndLevelTick {
		void onEndTick(ClientLevel level);
	}
}
