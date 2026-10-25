package io.mirage.mixin;

import io.mirage.neoforge.runtime.NeoForgeEventBridge;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 生物治疗 / 受伤 / 实际扣血 / 死亡 → 对应 Living 事件。 */
@Mixin(targets = "net.minecraft.world.entity.LivingEntity", remap = false)
public class NeoLivingEntityMixin {

    @Inject(method = "heal(F)V", at = @At("HEAD"), cancellable = true, require = 1)
    private void mirage$heal(float amount, CallbackInfo ci) {
        if (NeoForgeEventBridge.postLivingHeal((LivingEntity) (Object) this, amount)) {
            ci.cancel();
        }
    }

    @Inject(method = "hurtServer(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/damagesource/DamageSource;F)Z",
            at = @At("HEAD"), cancellable = true, require = 1)
    private void mirage$incomingDamage(ServerLevel level, DamageSource source, float amount, CallbackInfoReturnable<Boolean> cir) {
        if (NeoForgeEventBridge.postLivingIncomingDamage((LivingEntity) (Object) this, source, amount)) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "actuallyHurt(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/damagesource/DamageSource;F)V",
            at = @At("HEAD"), require = 1)
    private void mirage$damagePre(ServerLevel level, DamageSource source, float amount, CallbackInfo ci) {
        NeoForgeEventBridge.postLivingDamagePre((LivingEntity) (Object) this, source, amount);
    }

    @Inject(method = "actuallyHurt(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/damagesource/DamageSource;F)V",
            at = @At("RETURN"), require = 1)
    private void mirage$damagePost(ServerLevel level, DamageSource source, float amount, CallbackInfo ci) {
        NeoForgeEventBridge.postLivingDamagePost((LivingEntity) (Object) this, source, amount);
    }

    @Inject(method = "die(Lnet/minecraft/world/damagesource/DamageSource;)V", at = @At("HEAD"), cancellable = true, require = 1)
    private void mirage$death(DamageSource source, CallbackInfo ci) {
        if (NeoForgeEventBridge.postLivingDeath((LivingEntity) (Object) this, source)) {
            ci.cancel();
        }
    }
}
