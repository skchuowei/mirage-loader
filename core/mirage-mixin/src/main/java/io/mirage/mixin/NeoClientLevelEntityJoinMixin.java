package io.mirage.mixin;

import io.mirage.neoforge.runtime.NeoForgeEventBridge;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 实体加入客户端等级 → EntityJoinLevelEvent（可取消）。 */
@Mixin(targets = "net.minecraft.client.multiplayer.ClientLevel", remap = false)
public class NeoClientLevelEntityJoinMixin {

    @Inject(method = "addEntity(Lnet/minecraft/world/entity/Entity;)V",
            at = @At("HEAD"), cancellable = true, require = 1)
    private void mirage$entityJoin(Entity entity, CallbackInfo ci) {
        if (NeoForgeEventBridge.postEntityJoinLevel(entity, (ClientLevel) (Object) this)) {
            ci.cancel();
        }
    }
}
