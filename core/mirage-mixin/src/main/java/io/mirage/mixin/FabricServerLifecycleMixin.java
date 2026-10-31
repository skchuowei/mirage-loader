package io.mirage.mixin;

import io.mirage.fabric.runtime.FabricLifecycleBridge;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 服务端生命周期 → Fabric {@code ServerLifecycleEvents}。
 * 注入点对齐 fabric-lifecycle-events-v1 的 MinecraftServerMixin：
 *   SERVER_STARTING  → runServer 中 initServer() 调用前
 *   SERVER_STARTED   → runServer 中 buildServerStatus() 首次调用处（等级已就绪）
 *   SERVER_STOPPING  → stopServer() 入口
 *   SERVER_STOPPED   → stopServer() 结束
 * 集成服务器（客户端进世界时创建）也走 {@code MinecraftServer}，因此放在通用 mixins 列表。
 */
@Mixin(targets = "net.minecraft.server.MinecraftServer", remap = false)
public class FabricServerLifecycleMixin {

    @Inject(
        method = "runServer",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/server/MinecraftServer;initServer()Z"),
        require = 1
    )
    private void mirage$fabricServerStarting(CallbackInfo ci) {
        FabricLifecycleBridge.fireServerStarting((MinecraftServer) (Object) this);
    }

    @Inject(
        method = "runServer",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/server/MinecraftServer;buildServerStatus()Lnet/minecraft/network/protocol/status/ServerStatus;", ordinal = 0),
        require = 1
    )
    private void mirage$fabricServerStarted(CallbackInfo ci) {
        FabricLifecycleBridge.fireServerStarted((MinecraftServer) (Object) this);
    }

    @Inject(method = "stopServer", at = @At("HEAD"), require = 1)
    private void mirage$fabricServerStopping(CallbackInfo ci) {
        FabricLifecycleBridge.fireServerStopping((MinecraftServer) (Object) this);
    }

    @Inject(method = "stopServer", at = @At("TAIL"), require = 1)
    private void mirage$fabricServerStopped(CallbackInfo ci) {
        FabricLifecycleBridge.fireServerStopped((MinecraftServer) (Object) this);
    }
}
