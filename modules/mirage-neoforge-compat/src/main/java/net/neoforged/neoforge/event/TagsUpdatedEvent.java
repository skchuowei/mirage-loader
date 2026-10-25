package net.neoforged.neoforge.event;

import net.minecraft.core.RegistryAccess;
import net.minecraft.server.ReloadableServerResources;
import net.neoforged.bus.api.Event;

// 兼容 stub：NeoForge 26.x 的标签更新事件。
public sealed class TagsUpdatedEvent extends Event {
    private final RegistryAccess registries;

    protected TagsUpdatedEvent(RegistryAccess registries) {
        this.registries = registries;
    }

    public RegistryAccess getRegistries() {
        return this.registries;
    }

    public boolean shouldUpdateStaticData() {
        return true;
    }

    public static final class ServerDataLoad extends TagsUpdatedEvent {
        private final ReloadableServerResources serverResources;

        public ServerDataLoad(ReloadableServerResources serverResources, RegistryAccess registries) {
            super(registries);
            this.serverResources = serverResources;
        }

        public ReloadableServerResources getServerResources() {
            return this.serverResources;
        }
    }

    public static final class ClientPacketReceived extends TagsUpdatedEvent {
        private final boolean integratedServer;

        public ClientPacketReceived(RegistryAccess registries, boolean isIntegratedServerConnection) {
            super(registries);
            this.integratedServer = isIntegratedServerConnection;
        }

        @Override
        public boolean shouldUpdateStaticData() {
            return !this.integratedServer;
        }
    }
}
