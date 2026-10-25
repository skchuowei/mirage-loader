package io.mirage.mixin;

import io.mirage.neoforge.runtime.NeoForgeEventBridge;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.BooleanSupplier;

/** 服务端主循环 tick → ServerTickEvent.Pre/Post。 */
@Mixin(targets = "net.minecraft.server.MinecraftServer", remap = false)
public class NeoMinecraftServerTickMixin {

    @Inject(method = "tickServer(Ljava/util/function/BooleanSupplier;)V", at = @At("HEAD"), require = 1)
    private void mirage$serverTickPre(BooleanSupplier hasTime, CallbackInfo ci) {
        NeoForgeEventBridge.postServerTickPre((MinecraftServer) (Object) this, hasTime);
    }

    @Inject(method = "tickServer(Ljava/util/function/BooleanSupplier;)V", at = @At("RETURN"), require = 1)
    private void mirage$serverTickPost(BooleanSupplier hasTime, CallbackInfo ci) {
        NeoForgeEventBridge.postServerTickPost((MinecraftServer) (Object) this, hasTime);
    }
}
