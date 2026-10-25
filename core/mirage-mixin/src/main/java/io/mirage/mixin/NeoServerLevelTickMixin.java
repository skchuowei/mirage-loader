package io.mirage.mixin;

import io.mirage.neoforge.runtime.NeoForgeEventBridge;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.BooleanSupplier;

/** 服务端等级 tick → LevelTickEvent.Pre/Post。 */
@Mixin(targets = "net.minecraft.server.level.ServerLevel", remap = false)
public class NeoServerLevelTickMixin {

    @Inject(method = "tick(Ljava/util/function/BooleanSupplier;)V", at = @At("HEAD"), require = 1)
    private void mirage$levelTickPre(BooleanSupplier hasTime, CallbackInfo ci) {
        NeoForgeEventBridge.postLevelTickPre((ServerLevel) (Object) this, hasTime);
    }

    @Inject(method = "tick(Ljava/util/function/BooleanSupplier;)V", at = @At("RETURN"), require = 1)
    private void mirage$levelTickPost(BooleanSupplier hasTime, CallbackInfo ci) {
        NeoForgeEventBridge.postLevelTickPost((ServerLevel) (Object) this, hasTime);
    }
}
