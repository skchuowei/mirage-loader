package io.mirage.mixin;

import io.mirage.neoforge.runtime.NeoForgeEventBridge;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 客户端玩家 tick → PlayerTickEvent.Pre/Post（服务端玩家见 NeoServerPlayerTickMixin）。 */
@Mixin(targets = "net.minecraft.world.entity.player.Player", remap = false)
public class NeoPlayerTickMixin {

    @Inject(method = "tick()V", at = @At("HEAD"), require = 1)
    private void mirage$playerTickPre(CallbackInfo ci) {
        NeoForgeEventBridge.postPlayerTickPre((Player) (Object) this);
    }

    @Inject(method = "tick()V", at = @At("RETURN"), require = 1)
    private void mirage$playerTickPost(CallbackInfo ci) {
        NeoForgeEventBridge.postPlayerTickPost((Player) (Object) this);
    }
}
