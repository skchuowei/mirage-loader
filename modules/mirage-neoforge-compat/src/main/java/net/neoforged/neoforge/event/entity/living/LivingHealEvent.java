package net.neoforged.neoforge.event.entity.living;

import net.minecraft.world.entity.LivingEntity;
import net.neoforged.bus.api.ICancellableEvent;

// 兼容 stub：NeoForge 26.x 的生物治疗事件。
public class LivingHealEvent extends LivingEvent implements ICancellableEvent {
    private float amount;

    public LivingHealEvent(LivingEntity entity, float amount) {
        super(entity);
        this.amount = amount;
    }

    public float getAmount() {
        return this.amount;
    }

    public void setAmount(float amount) {
        this.amount = amount;
    }
}
