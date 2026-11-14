/* 本文件由 scripts/msr_w3c_c1_gen.py 生成，请勿手改；改生成器后重跑 --check。 */
package net.neoforged.neoforge.client.event;

/** W3-C1 clean-room：形状与签名取自 real-shape 骨架；未支持方法抛 UOE，不返回编造值。*/
public abstract class CustomizeGuiOverlayEvent extends net.neoforged.bus.api.Event {

    private com.mojang.blaze3d.platform.Window f0;
    private net.minecraft.client.gui.GuiGraphicsExtractor f1;
    private net.minecraft.client.DeltaTracker f2;

    public CustomizeGuiOverlayEvent(com.mojang.blaze3d.platform.Window a0, net.minecraft.client.gui.GuiGraphicsExtractor a1, net.minecraft.client.DeltaTracker a2) {
        this.f0 = a0;
        this.f1 = a1;
        this.f2 = a2;
    }

    public com.mojang.blaze3d.platform.Window getWindow() {
        return this.f0;
    }

    public net.minecraft.client.gui.GuiGraphicsExtractor getGuiGraphics() {
        return this.f1;
    }

    public net.minecraft.client.DeltaTracker getPartialTick() {
        return this.f2;
    }

    public CustomizeGuiOverlayEvent() {
    }

}
