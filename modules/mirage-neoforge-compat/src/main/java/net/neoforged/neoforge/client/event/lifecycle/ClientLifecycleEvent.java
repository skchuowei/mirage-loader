/* 本文件由 scripts/msr_w3c_c1_gen.py 生成，请勿手改；改生成器后重跑 --check。 */
package net.neoforged.neoforge.client.event.lifecycle;

/** W3-C1 clean-room：形状与签名取自 real-shape 骨架；未支持方法抛 UOE，不返回编造值。*/
public abstract class ClientLifecycleEvent extends net.neoforged.bus.api.Event {

    private net.minecraft.client.Minecraft f0;

    public ClientLifecycleEvent(net.minecraft.client.Minecraft a0) {
        this.f0 = a0;
    }

    public net.minecraft.client.Minecraft getClient() {
        return this.f0;
    }

    public ClientLifecycleEvent() {
    }

}
