/* 本文件由 scripts/msr_w3c_c1_gen.py 生成，请勿手改；改生成器后重跑 --check。 */
package net.neoforged.neoforge.client.event;

/** W3-C1 clean-room：形状与签名取自 real-shape 骨架；未支持方法抛 UOE，不返回编造值。*/
public abstract class RenderGuiEvent extends net.neoforged.bus.api.Event {

    private net.minecraft.client.gui.GuiGraphicsExtractor f0;
    private net.minecraft.client.DeltaTracker f1;

    public RenderGuiEvent(net.minecraft.client.gui.GuiGraphicsExtractor a0, net.minecraft.client.DeltaTracker a1) {
        this.f0 = a0;
        this.f1 = a1;
    }

    public net.minecraft.client.gui.GuiGraphicsExtractor getGuiGraphics() {
        return this.f0;
    }

    public net.minecraft.client.DeltaTracker getPartialTick() {
        return this.f1;
    }

    public RenderGuiEvent() {
    }


    /** W3-C1 clean-room：形状与签名取自 real-shape 骨架；未支持方法抛 UOE，不返回编造值。*/
    public static class Post extends net.neoforged.neoforge.client.event.RenderGuiEvent {

        private net.minecraft.client.gui.GuiGraphicsExtractor f0;
        private net.minecraft.client.DeltaTracker f1;

        public Post(net.minecraft.client.gui.GuiGraphicsExtractor a0, net.minecraft.client.DeltaTracker a1) {
            super(a0, a1);
            this.f0 = a0;
            this.f1 = a1;
        }

        public net.minecraft.client.gui.GuiGraphicsExtractor getGuiGraphics() {
            return this.f0;
        }

        public net.minecraft.client.DeltaTracker getPartialTick() {
            return this.f1;
        }

        public Post() {
        }

    }
}
