package io.mirage.mixin;

import io.mirage.fabric.runtime.FabricNetworkingBridge;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.DisconnectionDetails;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 客户端 DISCONNECT 事件 → Fabric {@code ClientPlayConnectionEvents.DISCONNECT}。
 *
 * <p>{@code onDisconnect(DisconnectionDetails)} 并不定义在 {@code ClientPacketListener} 上
 * （javap 核对 26.2：该方法由父类 {@code ClientCommonPacketListenerImpl} 实现，子类未覆写），
 * 所以不能写进 {@link FabricClientPlayConnectionMixin}——那里 {@code require = 1} 会因为
 * 找不到方法直接 InjectionError。改为 mixin 父类，并在调用点按 instanceof 过滤出 play 阶段
 * 的 listener，语义与 fabric-networking-api-v1 的 ClientPacketListenerMixin 一致。
 */
@Mixin(targets = "net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl", remap = false)
public class FabricClientDisconnectMixin {

    @Inject(method = "onDisconnect", at = @At("HEAD"), require = 1)
    private void mirage$fabricClientDisconnect(DisconnectionDetails details, CallbackInfo ci) {
        // configuration 阶段（ClientConfigurationPacketListenerImpl）也会走父类实现，只处理 play 阶段。
        if ((Object) this instanceof ClientPacketListener playListener) {
            FabricNetworkingBridge.fireClientDisconnect(playListener);
        }
    }
}
