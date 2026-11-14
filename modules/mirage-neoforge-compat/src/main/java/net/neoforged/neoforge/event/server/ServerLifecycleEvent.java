/* 本文件由 scripts/msr_w3c_c1_gen.py 生成，请勿手改；改生成器后重跑 --check。 */
package net.neoforged.neoforge.event.server;

/** W3-C1 clean-room：形状与签名取自 real-shape 骨架；未支持方法抛 UOE，不返回编造值。*/
public abstract class ServerLifecycleEvent extends net.neoforged.bus.api.Event {

    private net.minecraft.server.MinecraftServer f0;

    public ServerLifecycleEvent(net.minecraft.server.MinecraftServer a0) {
        this.f0 = a0;
    }

    public net.minecraft.server.MinecraftServer getServer() {
        return this.f0;
    }

    public ServerLifecycleEvent() {
    }

}
