package io.mirage.mixin;

import io.mirage.neoforge.runtime.NeoForgeEventBridge;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 玩家破坏方块 → BreakBlockEvent（可取消，取消后 destroyBlock 返回 false）。 */
@Mixin(targets = "net.minecraft.server.level.ServerPlayerGameMode", remap = false)
public abstract class NeoServerPlayerGameModeMixin {

    @Shadow
    protected ServerLevel level;

    @Shadow @Final
    protected ServerPlayer player;

    @Inject(method = "destroyBlock(Lnet/minecraft/core/BlockPos;)Z",
            at = @At("HEAD"), cancellable = true, require = 1)
    private void mirage$breakBlock(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        if (NeoForgeEventBridge.postBreakBlock(this.level, pos, this.level.getBlockState(pos), this.player)) {
            cir.setReturnValue(false);
        }
    }
}
