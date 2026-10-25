package net.neoforged.neoforge.event;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.ICancellableEvent;

// 兼容 stub：NeoForge 26.x 的服务器聊天事件。
public class ServerChatEvent extends Event implements ICancellableEvent {
    private final ServerPlayer player;
    private final String username;
    private final String rawText;
    private Component message;

    public ServerChatEvent(ServerPlayer player, String rawText, Component message) {
        this.player = player;
        this.username = player == null ? "" : player.getScoreboardName();
        this.rawText = rawText;
        this.message = message;
    }

    public ServerPlayer getPlayer() {
        return this.player;
    }

    public String getUsername() {
        return this.username;
    }

    public String getRawText() {
        return this.rawText;
    }

    public Component getMessage() {
        return this.message;
    }

    public void setMessage(Component message) {
        this.message = message;
    }
}
