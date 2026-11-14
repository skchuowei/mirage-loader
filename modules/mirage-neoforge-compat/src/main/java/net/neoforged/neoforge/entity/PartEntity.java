/* 本文件由 scripts/msr_w3c_c1_gen.py 生成，请勿手改；改生成器后重跑 --check。 */
package net.neoforged.neoforge.entity;

/** W3-C1 clean-room：形状与签名取自 real-shape 骨架；未支持方法抛 UOE，不返回编造值。*/
public abstract class PartEntity {

    private net.minecraft.world.entity.Entity f0;

    public PartEntity(net.minecraft.world.entity.Entity a0) {
        this.f0 = a0;
    }

    public net.minecraft.world.entity.Entity getParent() {
        return this.f0;
    }

    public net.minecraft.network.protocol.Packet getAddEntityPacket(net.minecraft.server.level.ServerEntity a0) {
        throw new UnsupportedOperationException("PartEntity#getAddEntityPacket 未实现（W3-C1 clean-room 部分实现，不返回编造值）");
    }

    public PartEntity() {
    }

}
