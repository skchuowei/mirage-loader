package io.mirage.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * PoC mixin：在 SharedConstants.tryDetectVersion() 进入时打印一行日志。
 * 验证 SpongePowered Mixin 织入管线已打通。
 */
@Mixin(targets = "net.minecraft.SharedConstants", remap = false)
public class MirageTestMixin {

    @Inject(method = "tryDetectVersion()V", at = @At("HEAD"), require = 0)
    private void mirage$onDetectVersion(CallbackInfo ci) {
        System.out.println("[Mirage-Mixin] @Inject 已生效：SharedConstants.tryDetectVersion() HEAD");
    }
}
