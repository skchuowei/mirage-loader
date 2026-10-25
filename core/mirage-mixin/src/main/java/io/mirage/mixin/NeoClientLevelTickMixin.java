package io.mirage.mixin;

import io.mirage.neoforge.runtime.NeoForgeEventBridge;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.BooleanSupplier;

/** 客户端等级 tick → LevelTickEvent.Pre/Post（26.x 无独立 ClientTickEvent）。 */
@Mixin(targets = "net.minecraft.client.multiplayer.ClientLevel", remap = false)
public class NeoClientLevelTickMixin {

    @Inject(method = "tick(Ljava/util/function/BooleanSupplier;)V", at = @At("HEAD"), require = 1)
    private void mirage$levelTickPre(BooleanSupplier hasTime, CallbackInfo ci) {
        NeoForgeEventBridge.postLevelTickPre((ClientLevel) (Object) this, hasTime);
    }

    @Inject(method = "tick(Ljava/util/function/BooleanSupplier;)V", at = @At("RETURN"), require = 1)
    private void mirage$levelTickPost(BooleanSupplier hasTime, CallbackInfo ci) {
        NeoForgeEventBridge.postLevelTickPost((ClientLevel) (Object) this, hasTime);
    }
}
