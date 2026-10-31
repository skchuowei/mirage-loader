/*
 * Mirage Fabric Compat — runtime bridge for Fabric client tick events.
 *
 * Mirror of FabricLifecycleBridge: Mixin injection points call these static methods,
 * which dispatch to the callbacks registered through ClientTickEvents.
 */
package io.mirage.fabric.runtime;

import io.mirage.api.log.MirageLog;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;

/**
 * Fabric 客户端 tick 事件派发入口。Mixin 只调用这里的静态方法。
 *
 * <p>tick 是高频事件，每类只在**首次**派发时打一行 {@code [Fabric] XXX fired}，
 * 留作"回调真的被调到"的证据，不随帧刷屏。
 */
public final class FabricClientTickBridge {

	private static final java.util.Set<String> LOGGED = java.util.concurrent.ConcurrentHashMap.newKeySet();

	private static void note(String event) {
		if (LOGGED.add(event)) {
			MirageLog.MIXIN.info("[Fabric] " + event + " fired");
		}
	}


	private FabricClientTickBridge() {
	}

	public static void fireStartTick(Minecraft client) {
		note("START_CLIENT_TICK");
		ClientTickEvents.START_CLIENT_TICK.invoker().onStartTick(client);
	}

	public static void fireEndTick(Minecraft client) {
		note("END_CLIENT_TICK");
		ClientTickEvents.END_CLIENT_TICK.invoker().onEndTick(client);
	}

	public static void fireStartLevelTick(ClientLevel level) {
		note("START_LEVEL_TICK");
		ClientTickEvents.START_LEVEL_TICK.invoker().onStartTick(level);
	}

	public static void fireEndLevelTick(ClientLevel level) {
		note("END_LEVEL_TICK");
		ClientTickEvents.END_LEVEL_TICK.invoker().onEndTick(level);
	}
}
