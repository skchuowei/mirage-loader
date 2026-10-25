package io.mirage.mixin;

import io.mirage.neoforge.runtime.NeoForgeEventBridge;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.Commands;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 指令树构建完成 → RegisterCommandsEvent。 */
@Mixin(targets = "net.minecraft.commands.Commands", remap = false)
public class NeoCommandsMixin {

    @Inject(method = "<init>(Lnet/minecraft/commands/Commands$CommandSelection;Lnet/minecraft/commands/CommandBuildContext;)V",
            at = @At("RETURN"), require = 1)
    private void mirage$registerCommands(Commands.CommandSelection selection, CommandBuildContext context, CallbackInfo ci) {
        NeoForgeEventBridge.postRegisterCommands((Commands) (Object) this, selection, context);
    }
}
