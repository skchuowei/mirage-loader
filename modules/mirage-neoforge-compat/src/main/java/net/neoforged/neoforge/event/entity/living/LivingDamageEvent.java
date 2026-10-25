package net.neoforged.neoforge.event.entity.living;

import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;

// 兼容 stub：NeoForge 26.x 的伤害结算事件（构造参数由 DamageContainer 简化为 DamageSource + 数值）。
public abstract class LivingDamageEvent extends LivingEvent {
    private LivingDamageEvent(LivingEntity entity) {
        super(entity);
    }

    public static class Pre extends LivingDamageEvent {
        private final DamageSource source;
        private final float originalDamage;
        private float newDamage;

        public Pre(LivingEntity entity, DamageSource source, float newDamage) {
            this(entity, source, newDamage, newDamage);
        }

        public Pre(LivingEntity entity, DamageSource source, float originalDamage, float newDamage) {
            super(entity);
            this.source = source;
            this.originalDamage = originalDamage;
            this.newDamage = newDamage;
        }

        public DamageSource getSource() {
            return this.source;
        }

        public float getNewDamage() {
            return this.newDamage;
        }

        public float getOriginalDamage() {
            return this.originalDamage;
        }

        public void setNewDamage(float newDamage) {
            this.newDamage = newDamage;
        }
    }

    public static class Post extends LivingDamageEvent {
        private final DamageSource source;
        private final float originalDamage;
        private final float inflictedDamage;
        private final float healthDamage;
        private final boolean performSideEffects;
        private final int postAttackInvulnerabilityTicks;

        public Post(LivingEntity entity, DamageSource source, float originalDamage, float healthDamage,
                float inflictedDamage, boolean performSideEffects, int postAttackInvulnerabilityTicks) {
            super(entity);
            this.source = source;
            this.originalDamage = originalDamage;
            this.inflictedDamage = inflictedDamage;
            this.healthDamage = healthDamage;
            this.performSideEffects = performSideEffects;
            this.postAttackInvulnerabilityTicks = postAttackInvulnerabilityTicks;
        }

        public DamageSource getSource() {
            return this.source;
        }

        public float getOriginalDamage() {
            return this.originalDamage;
        }

        public float getInflictedDamage() {
            return this.inflictedDamage;
        }

        public float getHealthDamage() {
            return this.healthDamage;
        }

        public boolean shouldCauseSideEffects() {
            return this.performSideEffects;
        }

        public int getPostAttackInvulnerabilityTicks() {
            return this.postAttackInvulnerabilityTicks;
        }
    }
}
