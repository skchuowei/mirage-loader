package io.mirage.mixin;

import io.mirage.fabric.runtime.FabricLifecycleBridge;
import net.minecraft.client.Minecraft;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 客户端生命周期 → Fabric {@code ClientLifecycleEvents}。
 * 注入点对齐 fabric-lifecycle-events-v1 的 MinecraftMixin：
 *   CLIENT_STARTING → &lt;init&gt; RETURN（兼容旧 mod；新版 Fabric 已移除该事件，仅保留 STARTED）
 *   CLIENT_STARTED  → run() 中 gameThread 字段赋值之后（此时 BlockableEventLoop#getRunningThread 可用）
 *   CLIENT_STOPPING → exitWorldAndClose() 入口（离开世界/关闭客户端）
 * 与 NeoForge 的 tick/entity Mixin 同款写法：只调 bridge，事件构造细节留 bridge。
 */
@Mixin(targets = "net.minecraft.client.Minecraft", remap = false)
public class FabricClientLifecycleMixin {

    @Inject(method = "<init>", at = @At("RETURN"), require = 1)
    private void mirage$fabricClientStarting(CallbackInfo ci) {
        FabricLifecycleBridge.fireClientStarting((Minecraft) (Object) this);
    }

    @Inject(
        method = "run",
        at = @At(value = "FIELD", target = "Lnet/minecraft/client/Minecraft;gameThread:Ljava/lang/Thread;", shift = At.Shift.AFTER, ordinal = 0, opcode = Opcodes.PUTFIELD),
        require = 1
    )
    private void mirage$fabricClientStarted(CallbackInfo ci) {
        FabricLifecycleBridge.fireClientStarted((Minecraft) (Object) this);
    }

    @Inject(method = "exitWorldAndClose", at = @At("HEAD"), require = 1)
    private void mirage$fabricClientStopping(CallbackInfo ci) {
        FabricLifecycleBridge.fireClientStopping((Minecraft) (Object) this);
    }
}
