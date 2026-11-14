/* 本文件由 scripts/msr_w3c_c1_gen.py 生成，请勿手改；改生成器后重跑 --check。 */
package net.neoforged.neoforge.client.event;

/** W3-C1 clean-room：形状与签名取自 real-shape 骨架；未支持方法抛 UOE，不返回编造值。*/
public class RegisterClientCommandsEvent extends net.neoforged.bus.api.Event {

    private com.mojang.brigadier.CommandDispatcher f0;
    private net.minecraft.commands.CommandBuildContext f1;

    public RegisterClientCommandsEvent(com.mojang.brigadier.CommandDispatcher a0, net.minecraft.commands.CommandBuildContext a1) {
        this.f0 = a0;
        this.f1 = a1;
    }

    public com.mojang.brigadier.CommandDispatcher getDispatcher() {
        return this.f0;
    }

    public net.minecraft.commands.CommandBuildContext getBuildContext() {
        return this.f1;
    }

    public RegisterClientCommandsEvent() {
    }

}
