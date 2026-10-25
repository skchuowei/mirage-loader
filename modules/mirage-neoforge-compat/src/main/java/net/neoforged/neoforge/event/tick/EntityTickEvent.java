package net.neoforged.neoforge.event.tick;

import net.minecraft.world.entity.Entity;
import net.neoforged.bus.api.ICancellableEvent;
import net.neoforged.neoforge.event.entity.EntityEvent;

// 兼容 stub：NeoForge 26.x 的实体 tick 事件，仅对齐签名。
public abstract class EntityTickEvent extends EntityEvent {
    protected EntityTickEvent(Entity entity) {
        super(entity);
    }

    public static class Pre extends EntityTickEvent implements ICancellableEvent {
        public Pre(Entity entity) {
            super(entity);
        }
    }

    public static class Post extends EntityTickEvent {
        public Post(Entity entity) {
            super(entity);
        }
    }
}
