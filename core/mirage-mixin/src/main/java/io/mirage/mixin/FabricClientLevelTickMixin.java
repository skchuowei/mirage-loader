package io.mirage.mixin;

import io.mirage.fabric.runtime.FabricClientTickBridge;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.BooleanSupplier;

/**
 * 客户端世界 tick → Fabric {@code ClientTickEvents} 的 LEVEL_TICK 两个事件。
 *   START_LEVEL_TICK → ClientLevel.tick(BooleanSupplier) HEAD
 *   END_LEVEL_TICK   → ClientLevel.tick(BooleanSupplier) TAIL
 * 注入点经 javap 在 26.2.jar 核实存在（public void tick(BooleanSupplier)）。
 */
@Mixin(targets = "net.minecraft.client.multiplayer.ClientLevel", remap = false)
public class FabricClientLevelTickMixin {

    @Inject(method = "tick", at = @At("HEAD"), require = 1)
    private void mirage$fabricClientLevelTickStart(BooleanSupplier shouldTick, CallbackInfo ci) {
        FabricClientTickBridge.fireStartLevelTick((ClientLevel) (Object) this);
    }

    @Inject(method = "tick", at = @At("TAIL"), require = 1)
    private void mirage$fabricClientLevelTickEnd(BooleanSupplier shouldTick, CallbackInfo ci) {
        FabricClientTickBridge.fireEndLevelTick((ClientLevel) (Object) this);
    }
}
