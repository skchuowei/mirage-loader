package io.mirage.mixin;

import io.mirage.api.log.MirageLog;

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

    // 目标 tryDetectVersion() 是 static 方法，注入回调必须同为 static，
    // 否则 Mixin 在 sanityCheck 阶段抛 InvalidInjectionException（non-static callback
    // targets a static method），导致 SharedConstants 整体转译失败 -> "Game version not set"。
    @Inject(method = "tryDetectVersion()V", at = @At("HEAD"), require = 0)
    private static void mirage$onDetectVersion(CallbackInfo ci) {
        MirageLog.MIXIN.debug("@Inject 已生效：SharedConstants.tryDetectVersion() HEAD");
    }
}
