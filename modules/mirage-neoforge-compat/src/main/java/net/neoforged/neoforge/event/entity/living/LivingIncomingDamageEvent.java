package net.neoforged.neoforge.event.entity.living;

import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.bus.api.ICancellableEvent;

// 兼容 stub：NeoForge 26.x 的实体即将受伤事件（构造参数由 DamageContainer 简化为 DamageSource + 数值）。
public class LivingIncomingDamageEvent extends LivingEvent implements ICancellableEvent {
    private final DamageSource source;
    private float amount;
    private final float originalAmount;
    private int invulnerabilityTicks;

    public LivingIncomingDamageEvent(LivingEntity entity, DamageSource source, float amount) {
        super(entity);
        this.source = source;
        this.amount = amount;
        this.originalAmount = amount;
    }

    public DamageSource getSource() {
        return this.source;
    }

    public float getAmount() {
        return this.amount;
    }

    public float getOriginalAmount() {
        return this.originalAmount;
    }

    public void setAmount(float newDamage) {
        this.amount = newDamage;
    }

    public void setInvulnerabilityTicks(int ticks) {
        this.invulnerabilityTicks = ticks;
    }

    public int getInvulnerabilityTicks() {
        return this.invulnerabilityTicks;
    }
}
