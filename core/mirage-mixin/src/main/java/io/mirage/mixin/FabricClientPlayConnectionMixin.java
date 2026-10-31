package io.mirage.mixin;

import io.mirage.fabric.runtime.FabricNetworkingBridge;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.CommonListenerCookie;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 客户端 play 连接事件 → Fabric {@code ClientPlayConnectionEvents}。
 * 注入点对齐 fabric-networking-api-v1 的 ClientPacketListenerMixin：
 *   INIT → ClientPacketListener.&lt;init&gt; RETURN
 *   JOIN → handleLogin RETURN
 * DISCONNECT 不在本类：{@code onDisconnect} 定义在父类 ClientCommonPacketListenerImpl
 * （ClientPacketListener 未覆写，require = 1 会 InjectionError），改由
 * {@link FabricClientDisconnectMixin} 织入父类。
 */
@Mixin(targets = "net.minecraft.client.multiplayer.ClientPacketListener", remap = false)
public class FabricClientPlayConnectionMixin {

    @Inject(method = "<init>", at = @At("RETURN"), require = 1)
    private void mirage$fabricClientPlayInit(Minecraft client, Connection connection, CommonListenerCookie cookie, CallbackInfo ci) {
        FabricNetworkingBridge.fireClientInit((ClientPacketListener) (Object) this);
    }

    @Inject(method = "handleLogin", at = @At("RETURN"), require = 1)
    private void mirage$fabricClientPlayJoin(ClientboundLoginPacket packet, CallbackInfo ci) {
        FabricNetworkingBridge.fireClientJoin((ClientPacketListener) (Object) this);
    }
}
