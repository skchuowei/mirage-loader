package io.mirage.mixin;

import io.mirage.fabric.runtime.FabricNetworkingBridge;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 服务端 play 网络：自定义 payload 派发 + 断开事件 → Fabric networking API。
 *   DISCONNECT                          → ServerPlayConnectionEvents.DISCONNECT（onDisconnect HEAD）
 *   handleCustomPayload(Serverbound…)   → ServerPlayNetworking 已登记的 receiver；消费则 cancel 原版处理
 * 注入点均经 javap 在 26.2.jar 核实存在。
 */
@Mixin(targets = "net.minecraft.server.network.ServerGamePacketListenerImpl", remap = false)
public class FabricServerPayloadMixin {

    @Inject(method = "onDisconnect", at = @At("HEAD"), require = 1)
    private void mirage$fabricServerPlayDisconnect(DisconnectionDetails details, CallbackInfo ci) {
        FabricNetworkingBridge.fireServerDisconnect((ServerGamePacketListenerImpl) (Object) this);
    }

    @Inject(method = "handleCustomPayload", at = @At("HEAD"), require = 1, cancellable = true)
    private void mirage$fabricServerCustomPayload(ServerboundCustomPayloadPacket packet, CallbackInfo ci) {
        if (FabricNetworkingBridge.onServerCustomPayload((ServerGamePacketListenerImpl) (Object) this, packet)) {
            ci.cancel();
        }
    }
}
