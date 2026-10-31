package io.mirage.mixin;

import io.mirage.fabric.runtime.FabricNetworkingBridge;
import net.minecraft.network.Connection;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 服务端 play 连接事件 → Fabric {@code ServerPlayConnectionEvents}。
 * 注入点对齐 fabric-networking-api-v1 的 PlayerListMixin（placeNewPlayer）：
 *   INIT      → placeNewPlayer HEAD（连接进入 PLAY 状态，可登记 receiver）
 *   JOIN      → placeNewPlayer TAIL（listener 就绪，可发包）
 * DISCONNECT 见 {@link FabricServerPayloadMixin}（onDisconnect）。
 */
@Mixin(targets = "net.minecraft.server.players.PlayerList", remap = false)
public class FabricServerPlayConnectionMixin {

    @Inject(method = "placeNewPlayer", at = @At("HEAD"), require = 1)
    private void mirage$fabricServerPlayInit(Connection connection, ServerPlayer player, CommonListenerCookie cookie, CallbackInfo ci) {
        FabricNetworkingBridge.fireServerInit(player.connection, ((PlayerList) (Object) this).getServer());
    }

    @Inject(method = "placeNewPlayer", at = @At("TAIL"), require = 1)
    private void mirage$fabricServerPlayJoin(Connection connection, ServerPlayer player, CommonListenerCookie cookie, CallbackInfo ci) {
        FabricNetworkingBridge.fireServerJoin(player.connection, ((PlayerList) (Object) this).getServer());
    }
}
