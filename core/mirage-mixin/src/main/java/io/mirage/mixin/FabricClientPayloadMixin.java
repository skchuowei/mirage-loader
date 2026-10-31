package io.mirage.mixin;

import io.mirage.fabric.runtime.FabricNetworkingBridge;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 客户端 play 网络：自定义 payload 派发 → {@code ClientPlayNetworking} 已登记的 receiver；
 * 消费则 cancel 原版处理（对齐 fabric-networking-api-v1 的 ClientboundCustomPayloadPacketMixin 语义）。
 * 注入点 handleCustomPayload(CustomPacketPayload) 经 javap 在 26.2.jar 核实存在。
 */
@Mixin(targets = "net.minecraft.client.multiplayer.ClientPacketListener", remap = false)
public class FabricClientPayloadMixin {

    @Inject(method = "handleCustomPayload", at = @At("HEAD"), require = 1, cancellable = true)
    private void mirage$fabricClientCustomPayload(CustomPacketPayload payload, CallbackInfo ci) {
        if (FabricNetworkingBridge.onClientCustomPayload((ClientPacketListener) (Object) this, payload)) {
            ci.cancel();
        }
    }
}
