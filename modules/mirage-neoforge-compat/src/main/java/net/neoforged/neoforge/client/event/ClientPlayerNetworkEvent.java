/* 本文件由 scripts/msr_w3c_c1_gen.py 生成，请勿手改；改生成器后重跑 --check。 */
package net.neoforged.neoforge.client.event;

/** W3-C1 clean-room：形状与签名取自 real-shape 骨架；未支持方法抛 UOE，不返回编造值。*/
public abstract class ClientPlayerNetworkEvent extends net.neoforged.bus.api.Event {

    private net.minecraft.client.multiplayer.MultiPlayerGameMode f0;
    private net.minecraft.client.player.LocalPlayer f1;
    private net.minecraft.network.Connection f2;

    public ClientPlayerNetworkEvent(net.minecraft.client.multiplayer.MultiPlayerGameMode a0, net.minecraft.client.player.LocalPlayer a1, net.minecraft.network.Connection a2) {
        this.f0 = a0;
        this.f1 = a1;
        this.f2 = a2;
    }

    public net.minecraft.client.multiplayer.MultiPlayerGameMode getMultiPlayerGameMode() {
        return this.f0;
    }

    public net.minecraft.client.player.LocalPlayer getPlayer() {
        return this.f1;
    }

    public net.minecraft.network.Connection getConnection() {
        return this.f2;
    }

    public ClientPlayerNetworkEvent() {
    }


    /** W3-C1 clean-room：形状与签名取自 real-shape 骨架；未支持方法抛 UOE，不返回编造值。*/
    public static class LoggingIn extends net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent {

        private net.minecraft.client.multiplayer.MultiPlayerGameMode f0;
        private net.minecraft.client.player.LocalPlayer f1;
        private net.minecraft.network.Connection f2;

        public LoggingIn(net.minecraft.client.multiplayer.MultiPlayerGameMode a0, net.minecraft.client.player.LocalPlayer a1, net.minecraft.network.Connection a2) {
            super(a0, a1, a2);
            this.f0 = a0;
            this.f1 = a1;
            this.f2 = a2;
        }

        public LoggingIn() {
        }

    }

    /** W3-C1 clean-room：形状与签名取自 real-shape 骨架；未支持方法抛 UOE，不返回编造值。*/
    public static class LoggingOut extends net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent {

        private net.minecraft.client.multiplayer.MultiPlayerGameMode f0;
        private net.minecraft.client.player.LocalPlayer f1;
        private net.minecraft.network.Connection f2;

        public LoggingOut(net.minecraft.client.multiplayer.MultiPlayerGameMode a0, net.minecraft.client.player.LocalPlayer a1, net.minecraft.network.Connection a2) {
            super(a0, a1, a2);
            this.f0 = a0;
            this.f1 = a1;
            this.f2 = a2;
        }

        public net.minecraft.client.multiplayer.MultiPlayerGameMode getMultiPlayerGameMode() {
            return this.f0;
        }

        public net.minecraft.client.player.LocalPlayer getPlayer() {
            return this.f1;
        }

        public net.minecraft.network.Connection getConnection() {
            return this.f2;
        }

        public LoggingOut() {
        }

    }
}
