/*
 * Mirage Fabric Compat — runtime bridge for Fabric lifecycle events.
 *
 * Mirror of mirage-neoforge-compat's NeoForgeEventBridge: Mixin injection points call these
 * static methods, which dispatch to the registered Fabric callbacks. The bridge is loaded by
 * the MirageClassLoader together with mods, so it sees the same net.fabricmc.* stub classes.
 */
package io.mirage.fabric.runtime;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;
import io.mirage.api.log.MirageLog;

/**
 * Fabric 生命周期事件派发入口。Mixin 只调用这里的静态方法，事件构造细节留在本类。
 *
 * <p>与 {@code NeoForgeEventBridge} 不同，Fabric 事件是回调式（{@code Event<T>} 持有
 * 一个合并后的 {@code invoker}），因此这里直接调用 {@code invoker().onXxx(...)} 即可。
 *
 * <p>每个事件首次派发时打一行 {@code [Fabric] XXX fired}（只打一次，避免高频事件刷屏）：
 * 这是"注入点找到了"之外的第二重证据——回调真的被调到了。排查"mixin 显示 MODIFIED 但
 * 功能没反应"时先看这几行在不在。
 */
public final class FabricLifecycleBridge {

    private static final java.util.Set<String> LOGGED = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private static void note(String event) {
        if (LOGGED.add(event)) {
            MirageLog.MIXIN.info("[Fabric] " + event + " fired");
        }
    }

    private FabricLifecycleBridge() {
    }

    // ---- client ----

    public static void fireClientStarting(Minecraft client) {
        note("CLIENT_STARTING");
        ClientLifecycleEvents.CLIENT_STARTING.invoker().onClientStarting(client);
    }

    public static void fireClientStarted(Minecraft client) {
        note("CLIENT_STARTED");
        ClientLifecycleEvents.CLIENT_STARTED.invoker().onClientStarted(client);
    }

    public static void fireClientStopping(Minecraft client) {
        note("CLIENT_STOPPING");
        ClientLifecycleEvents.CLIENT_STOPPING.invoker().onClientStopping(client);
    }

    // ---- server ----

    public static void fireServerStarting(MinecraftServer server) {
        note("SERVER_STARTING");
        ServerLifecycleEvents.SERVER_STARTING.invoker().onServerStarting(server);
    }

    public static void fireServerStarted(MinecraftServer server) {
        note("SERVER_STARTED");
        ServerLifecycleEvents.SERVER_STARTED.invoker().onServerStarted(server);
    }

    public static void fireServerStopping(MinecraftServer server) {
        note("SERVER_STOPPING");
        ServerLifecycleEvents.SERVER_STOPPING.invoker().onServerStopping(server);
    }

    public static void fireServerStopped(MinecraftServer server) {
        note("SERVER_STOPPED");
        ServerLifecycleEvents.SERVER_STOPPED.invoker().onServerStopped(server);
    }
}
