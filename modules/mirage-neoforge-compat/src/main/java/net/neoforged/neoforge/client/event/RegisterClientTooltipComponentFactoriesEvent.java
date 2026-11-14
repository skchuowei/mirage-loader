/* 本文件由 scripts/msr_w3c_c1_gen.py 生成，请勿手改；改生成器后重跑 --check。 */
package net.neoforged.neoforge.client.event;

/** W3-C1 clean-room：形状与签名取自 real-shape 骨架；未支持方法抛 UOE，不返回编造值。*/
public class RegisterClientTooltipComponentFactoriesEvent extends net.neoforged.bus.api.Event implements net.neoforged.fml.event.IModBusEvent {

    private java.util.Map f0;

    public RegisterClientTooltipComponentFactoriesEvent(java.util.Map a0) {
        this.f0 = a0;
    }

    public void register(java.lang.Class a0, java.util.function.Function a1) {
        throw new UnsupportedOperationException("RegisterClientTooltipComponentFactoriesEvent#register 未实现（W3-C1 clean-room 部分实现，不返回编造值）");
    }

    public RegisterClientTooltipComponentFactoriesEvent() {
    }

}
