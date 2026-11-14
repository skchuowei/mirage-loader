/* 本文件由 scripts/msr_w3c_c1_gen.py 生成，请勿手改；改生成器后重跑 --check。 */
package net.neoforged.neoforge.client.settings;

/** W3-C1 clean-room：形状与签名取自 real-shape 骨架；未支持方法抛 UOE，不返回编造值。*/
public enum KeyConflictContext implements net.neoforged.neoforge.client.settings.IKeyConflictContext {

    UNIVERSAL, GUI, IN_GAME;

    public boolean isActive() {
        throw new UnsupportedOperationException("KeyConflictContext#isActive 未实现（W3-C1 clean-room 部分实现，不返回编造值）");
    }

    public boolean conflicts(net.neoforged.neoforge.client.settings.IKeyConflictContext a0) {
        throw new UnsupportedOperationException("KeyConflictContext#conflicts 未实现（W3-C1 clean-room 部分实现，不返回编造值）");
    }

}
