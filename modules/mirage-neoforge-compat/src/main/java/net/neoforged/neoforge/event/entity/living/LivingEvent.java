package net.neoforged.neoforge.event.entity.living;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.neoforge.event.entity.EntityEvent;

// 兼容 stub：NeoForge 26.x 的生物事件基类及其内部事件。
public abstract class LivingEvent extends EntityEvent {
    private final LivingEntity livingEntity;

    public LivingEvent(LivingEntity entity) {
        super(entity);
        this.livingEntity = entity;
    }

    @Override
    public LivingEntity getEntity() {
        return this.livingEntity;
    }

    public static class LivingJumpEvent extends LivingEvent {
        public LivingJumpEvent(LivingEntity entity) {
            super(entity);
        }
    }

    public static class LivingVisibilityEvent extends LivingEvent {
        private double visibilityModifier;
        private final Entity lookingEntity;

        public LivingVisibilityEvent(LivingEntity livingEntity, Entity lookingEntity, double originalMultiplier) {
            super(livingEntity);
            this.visibilityModifier = originalMultiplier;
            this.lookingEntity = lookingEntity;
        }

        public void modifyVisibility(double mod) {
            this.visibilityModifier *= mod;
        }

        public double getVisibilityModifier() {
            return this.visibilityModifier;
        }

        public Entity getLookingEntity() {
            return this.lookingEntity;
        }
    }
}
