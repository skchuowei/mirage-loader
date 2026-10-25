package net.neoforged.neoforge.event.entity.player;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.ICancellableEvent;

// 兼容 stub：NeoForge 26.x 的玩家攻击实体事件。
public class AttackEntityEvent extends PlayerEvent implements ICancellableEvent {
    private final Entity target;

    public AttackEntityEvent(Player player, Entity target) {
        super(player);
        this.target = target;
    }

    public Entity getTarget() {
        return this.target;
    }
}
