package io.mirage.mixin;

import io.mirage.fabric.runtime.FabricClientTickBridge;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 客户端 tick → Fabric {@code ClientTickEvents}。
 *   START_CLIENT_TICK → Minecraft.tick() HEAD
 *   END_CLIENT_TICK   → Minecraft.tick() TAIL
 * 注入点经 javap 在 26.2.jar 核实存在（public void tick()）。
 */
@Mixin(targets = "net.minecraft.client.Minecraft", remap = false)
public class FabricClientTickMixin {

    @Inject(method = "tick", at = @At("HEAD"), require = 1)
    private void mirage$fabricClientTickStart(CallbackInfo ci) {
        FabricClientTickBridge.fireStartTick((Minecraft) (Object) this);
    }

    @Inject(method = "tick", at = @At("TAIL"), require = 1)
    private void mirage$fabricClientTickEnd(CallbackInfo ci) {
        FabricClientTickBridge.fireEndTick((Minecraft) (Object) this);
    }
}
