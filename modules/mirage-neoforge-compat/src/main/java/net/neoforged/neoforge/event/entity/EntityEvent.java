package net.neoforged.neoforge.event.entity;

import net.minecraft.core.SectionPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.Pose;
import net.neoforged.bus.api.Event;

// 兼容 stub：NeoForge 26.x 的实体事件基类及其内部事件。
public abstract class EntityEvent extends Event {
    private final Entity entity;

    public EntityEvent(Entity entity) {
        this.entity = entity;
    }

    public Entity getEntity() {
        return this.entity;
    }

    public static class EntityConstructing extends EntityEvent {
        public EntityConstructing(Entity entity) {
            super(entity);
        }
    }

    public static class EnteringSection extends EntityEvent {
        private final long packedOldPos;
        private final long packedNewPos;

        public EnteringSection(Entity entity, long packedOldPos, long packedNewPos) {
            super(entity);
            this.packedOldPos = packedOldPos;
            this.packedNewPos = packedNewPos;
        }

        public long getPackedOldPos() {
            return this.packedOldPos;
        }

        public long getPackedNewPos() {
            return this.packedNewPos;
        }

        public SectionPos getOldPos() {
            return SectionPos.of(this.packedOldPos);
        }

        public SectionPos getNewPos() {
            return SectionPos.of(this.packedNewPos);
        }

        public boolean didChunkChange() {
            return SectionPos.x(this.packedOldPos) != SectionPos.x(this.packedNewPos)
                    || SectionPos.z(this.packedOldPos) != SectionPos.z(this.packedNewPos);
        }
    }

    public static class Size extends EntityEvent {
        private final Pose pose;
        private final EntityDimensions oldSize;
        private EntityDimensions newSize;

        public Size(Entity entity, Pose pose, EntityDimensions size) {
            this(entity, pose, size, size);
        }

        public Size(Entity entity, Pose pose, EntityDimensions oldSize, EntityDimensions newSize) {
            super(entity);
            this.pose = pose;
            this.oldSize = oldSize;
            this.newSize = newSize;
        }

        public Pose getPose() {
            return this.pose;
        }

        public EntityDimensions getOldSize() {
            return this.oldSize;
        }

        public EntityDimensions getNewSize() {
            return this.newSize;
        }

        public void setNewSize(EntityDimensions size) {
            this.newSize = size;
        }
    }
}
