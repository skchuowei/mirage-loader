package io.mirage.mixin;

import io.mirage.neoforge.runtime.NeoForgeEventBridge;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 实体 tick → EntityTickEvent.Pre（可取消）/ Post。 */
@Mixin(targets = "net.minecraft.world.entity.Entity", remap = false)
public class NeoEntityTickMixin {

    @Inject(method = "tick()V", at = @At("HEAD"), cancellable = true, require = 1)
    private void mirage$entityTickPre(CallbackInfo ci) {
        if (NeoForgeEventBridge.postEntityTickPre((Entity) (Object) this)) {
            ci.cancel();
        }
    }

    @Inject(method = "tick()V", at = @At("RETURN"), require = 1)
    private void mirage$entityTickPost(CallbackInfo ci) {
        NeoForgeEventBridge.postEntityTickPost((Entity) (Object) this);
    }
}
