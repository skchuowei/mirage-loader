/*
 * Mirage Fabric Compat — clean-room reimplementation of Fabric's lifecycle event surface.
 *
 * Fabric (net.fabricmc.*) is licensed under the Apache License, Version 2.0.
 * This is an independent, minimal implementation that mirrors the public interface
 * so Fabric mods can register server lifecycle callbacks under Mirage.
 */
package net.fabricmc.fabric.api.event.lifecycle.v1;

import net.minecraft.server.MinecraftServer;

import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;

/**
 * Server lifecycle events (clean-room mirror of fabric-lifecycle-events-v1).
 *
 * <p>Firing points are provided by {@code io.mirage.fabric.runtime.FabricLifecycleBridge},
 * invoked from Mixin injection on {@code net.minecraft.server.MinecraftServer}.
 */
public final class ServerLifecycleEvents {
    private ServerLifecycleEvents() {
    }

    /** Called when a Minecraft server is starting. Occurs before the player list and any levels are loaded. */
    public static final Event<ServerStarting> SERVER_STARTING = EventFactory.createArrayBacked(ServerStarting.class, callbacks -> server -> {
        for (ServerStarting callback : callbacks) {
            callback.onServerStarting(server);
        }
    });

    /** Called when a Minecraft server has started and is about to tick for the first time. All levels are live. */
    public static final Event<ServerStarted> SERVER_STARTED = EventFactory.createArrayBacked(ServerStarted.class, callbacks -> server -> {
        for (ServerStarted callback : callbacks) {
            callback.onServerStarted(server);
        }
    });

    /** Called when a Minecraft server has started shutting down, before the network channel is closed. */
    public static final Event<ServerStopping> SERVER_STOPPING = EventFactory.createArrayBacked(ServerStopping.class, callbacks -> server -> {
        for (ServerStopping callback : callbacks) {
            callback.onServerStopping(server);
        }
    });

    /** Called when a Minecraft server has stopped. All levels have been closed and players unloaded. */
    public static final Event<ServerStopped> SERVER_STOPPED = EventFactory.createArrayBacked(ServerStopped.class, callbacks -> server -> {
        for (ServerStopped callback : callbacks) {
            callback.onServerStopped(server);
        }
    });

    @FunctionalInterface
    public interface ServerStarting {
        void onServerStarting(MinecraftServer server);
    }

    @FunctionalInterface
    public interface ServerStarted {
        void onServerStarted(MinecraftServer server);
    }

    @FunctionalInterface
    public interface ServerStopping {
        void onServerStopping(MinecraftServer server);
    }

    @FunctionalInterface
    public interface ServerStopped {
        void onServerStopped(MinecraftServer server);
    }
}
