package net.neoforged.neoforge.event.level;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.ICancellableEvent;

// 兼容 stub：NeoForge 26.x 的方块掉落事件（经验值不再由附魔逻辑计算）。
public class BlockDropsEvent extends BlockEvent implements ICancellableEvent {
    private final BlockEntity blockEntity;
    private final List<ItemEntity> drops;
    private final Entity breaker;
    private final ItemStack tool;
    private int experience;

    public BlockDropsEvent(ServerLevel level, BlockPos pos, BlockState state, BlockEntity blockEntity, List<ItemEntity> drops,
            Entity breaker, ItemStack tool) {
        super(level, pos, state);
        this.blockEntity = blockEntity;
        this.drops = drops;
        this.breaker = breaker;
        this.tool = tool;
        this.experience = 0;
    }

    public List<ItemEntity> getDrops() {
        return this.drops;
    }

    public BlockEntity getBlockEntity() {
        return this.blockEntity;
    }

    public Entity getBreaker() {
        return this.breaker;
    }

    public ItemStack getTool() {
        return this.tool;
    }

    @Override
    public ServerLevel getLevel() {
        return (ServerLevel) super.getLevel();
    }

    public int getDroppedExperience() {
        return this.experience;
    }

    public void setDroppedExperience(int experience) {
        this.experience = Math.max(0, experience);
    }
}
