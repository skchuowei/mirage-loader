package net.minecraft.world.level.block.state;

import net.minecraft.world.level.block.Block;

/**
 * 沙盒 fake：MC {@code BlockBehaviour} 最小占位。
 * 26.x 中 Block 继承自 BlockBehaviour，mod 静态初始化可能触达此类。
 */
public class BlockBehaviour extends Block {
    public BlockBehaviour() {}
}
