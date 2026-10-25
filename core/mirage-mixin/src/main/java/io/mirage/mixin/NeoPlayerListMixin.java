package io.mirage.mixin;

import io.mirage.neoforge.runtime.NeoForgeEventBridge;
import net.minecraft.network.Connection;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 玩家完成登录 → PlayerEvent.PlayerLoggedInEvent。 */
@Mixin(targets = "net.minecraft.server.players.PlayerList", remap = false)
public class NeoPlayerListMixin {

    @Inject(method = "placeNewPlayer(Lnet/minecraft/network/Connection;Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/server/network/CommonListenerCookie;)V",
            at = @At("RETURN"), require = 1)
    private void mirage$playerLoggedIn(Connection connection, ServerPlayer player, CommonListenerCookie cookie, CallbackInfo ci) {
        NeoForgeEventBridge.postPlayerLoggedIn(player);
    }
}
