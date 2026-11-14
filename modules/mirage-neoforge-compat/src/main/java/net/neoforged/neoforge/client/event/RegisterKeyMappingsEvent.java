/* 本文件由 scripts/msr_w3c_c1_gen.py 生成，请勿手改；改生成器后重跑 --check。 */
package net.neoforged.neoforge.client.event;

/** W3-C1 clean-room：形状与签名取自 real-shape 骨架；未支持方法抛 UOE，不返回编造值。*/
public class RegisterKeyMappingsEvent extends net.neoforged.bus.api.Event implements net.neoforged.fml.event.IModBusEvent {

    private net.minecraft.client.Options f0;

    public RegisterKeyMappingsEvent(net.minecraft.client.Options a0) {
        this.f0 = a0;
    }

    public void register(net.minecraft.client.KeyMapping a0) {
        throw new UnsupportedOperationException("RegisterKeyMappingsEvent#register 未实现（W3-C1 clean-room 部分实现，不返回编造值）");
    }

    public void registerCategory(net.minecraft.client.KeyMapping.Category a0) {
        throw new UnsupportedOperationException("RegisterKeyMappingsEvent#registerCategory 未实现（W3-C1 clean-room 部分实现，不返回编造值）");
    }

    public void sortAndStoreCategories(java.util.List a0) {
        throw new UnsupportedOperationException("RegisterKeyMappingsEvent#sortAndStoreCategories 未实现（W3-C1 clean-room 部分实现，不返回编造值）");
    }

    public RegisterKeyMappingsEvent() {
    }

}
