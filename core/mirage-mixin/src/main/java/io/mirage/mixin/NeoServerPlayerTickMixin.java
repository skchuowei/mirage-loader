package io.mirage.mixin;

import io.mirage.neoforge.runtime.NeoForgeEventBridge;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 服务端玩家 tick（ServerPlayer.tick 不调用 super.tick）→ PlayerTickEvent.Pre/Post。 */
@Mixin(targets = "net.minecraft.server.level.ServerPlayer", remap = false)
public class NeoServerPlayerTickMixin {

    @Inject(method = "tick()V", at = @At("HEAD"), require = 1)
    private void mirage$playerTickPre(CallbackInfo ci) {
        NeoForgeEventBridge.postPlayerTickPre((ServerPlayer) (Object) this);
    }

    @Inject(method = "tick()V", at = @At("RETURN"), require = 1)
    private void mirage$playerTickPost(CallbackInfo ci) {
        NeoForgeEventBridge.postPlayerTickPost((ServerPlayer) (Object) this);
    }
}
