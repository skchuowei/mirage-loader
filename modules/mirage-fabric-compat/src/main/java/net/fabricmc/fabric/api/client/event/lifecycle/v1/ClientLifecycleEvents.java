/*
 * Mirage Fabric Compat — clean-room reimplementation of Fabric's lifecycle event surface.
 *
 * Fabric (net.fabricmc.*) is licensed under the Apache License, Version 2.0.
 * This is an independent, minimal implementation that mirrors the public interface
 * so Fabric mods can register client lifecycle callbacks under Mirage.
 */
package net.fabricmc.fabric.api.client.event.lifecycle.v1;

import net.minecraft.client.Minecraft;

import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;

/**
 * Client lifecycle events (clean-room mirror of fabric-lifecycle-events-v1).
 *
 * <p>Note: {@code CLIENT_STARTING} is kept for parity with mods that still reference it
 * (newer Fabric dropped it, but many mods use it). Firing points are provided by
 * {@code io.mirage.fabric.runtime.FabricLifecycleBridge}, invoked from Mixin injection on
 * {@code net.minecraft.client.Minecraft}.
 */
public final class ClientLifecycleEvents {
    private ClientLifecycleEvents() {
    }

    /** Called when Minecraft's client begins to start. The client instance is available. */
    public static final Event<ClientStarting> CLIENT_STARTING = EventFactory.createArrayBacked(ClientStarting.class, callbacks -> client -> {
        for (ClientStarting callback : callbacks) {
            callback.onClientStarting(client);
        }
    });

    /** Called when Minecraft has started and its client is about to tick for the first time. */
    public static final Event<ClientStarted> CLIENT_STARTED = EventFactory.createArrayBacked(ClientStarted.class, callbacks -> client -> {
        for (ClientStarted callback : callbacks) {
            callback.onClientStarted(client);
        }
    });

    /** Called when Minecraft's client begins to stop (quitting while in game, or closing the window). */
    public static final Event<ClientStopping> CLIENT_STOPPING = EventFactory.createArrayBacked(ClientStopping.class, callbacks -> client -> {
        for (ClientStopping callback : callbacks) {
            callback.onClientStopping(client);
        }
    });

    @FunctionalInterface
    public interface ClientStarting {
        void onClientStarting(Minecraft client);
    }

    @FunctionalInterface
    public interface ClientStarted {
        void onClientStarted(Minecraft client);
    }

    @FunctionalInterface
    public interface ClientStopping {
        void onClientStopping(Minecraft client);
    }
}
