package net.neoforged.neoforge.event.tick;

import java.util.function.BooleanSupplier;
import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.Event;

// 兼容 stub：NeoForge 26.x 的服务器 tick 事件，仅对齐签名。
public abstract class ServerTickEvent extends Event {
    private final BooleanSupplier hasTime;
    private final MinecraftServer server;

    protected ServerTickEvent(BooleanSupplier hasTime, MinecraftServer server) {
        this.hasTime = hasTime;
        this.server = server;
    }

    public boolean hasTime() {
        return this.hasTime != null && this.hasTime.getAsBoolean();
    }

    public MinecraftServer getServer() {
        return this.server;
    }

    public static class Pre extends ServerTickEvent {
        public Pre(BooleanSupplier hasTime, MinecraftServer server) {
            super(hasTime, server);
        }
    }

    public static class Post extends ServerTickEvent {
        public Post(BooleanSupplier hasTime, MinecraftServer server) {
            super(hasTime, server);
        }
    }
}
