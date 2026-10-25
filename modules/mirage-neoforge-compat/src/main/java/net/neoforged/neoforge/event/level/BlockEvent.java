package net.neoforged.neoforge.event.level;

import java.util.EnumSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.portal.PortalShape;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.ICancellableEvent;

// 兼容 stub：NeoForge 26.x 的方块事件基类及其内部事件。
public abstract class BlockEvent extends Event {
    private final LevelAccessor level;
    private final BlockPos pos;
    private final BlockState state;

    public BlockEvent(LevelAccessor level, BlockPos pos, BlockState state) {
        this.level = level;
        this.pos = pos;
        this.state = state;
    }

    public LevelAccessor getLevel() {
        return this.level;
    }

    public BlockPos getPos() {
        return this.pos;
    }

    public BlockState getState() {
        return this.state;
    }

    public static class EntityPlaceEvent extends BlockEvent implements ICancellableEvent {
        private final Entity entity;
        private final BlockState placedBlock;
        private final BlockState placedAgainst;

        public EntityPlaceEvent(LevelAccessor level, BlockPos pos, BlockState placedBlock, BlockState placedAgainst, Entity entity) {
            super(level, pos, placedBlock);
            this.entity = entity;
            this.placedBlock = placedBlock;
            this.placedAgainst = placedAgainst;
        }

        public Entity getEntity() {
            return this.entity;
        }

        public BlockState getPlacedBlock() {
            return this.placedBlock;
        }

        public BlockState getPlacedAgainst() {
            return this.placedAgainst;
        }
    }

    public static class NeighborNotifyEvent extends BlockEvent implements ICancellableEvent {
        private final EnumSet<Direction> notifiedSides;
        private final boolean forceRedstoneUpdate;

        public NeighborNotifyEvent(Level level, BlockPos pos, BlockState state, EnumSet<Direction> notifiedSides, boolean forceRedstoneUpdate) {
            super(level, pos, state);
            this.notifiedSides = notifiedSides;
            this.forceRedstoneUpdate = forceRedstoneUpdate;
        }

        public EnumSet<Direction> getNotifiedSides() {
            return this.notifiedSides;
        }

        public boolean getForceRedstoneUpdate() {
            return this.forceRedstoneUpdate;
        }
    }

    public static class FluidPlaceBlockEvent extends BlockEvent implements ICancellableEvent {
        private final BlockPos liquidPos;
        private BlockState newState;
        private final BlockState originalState;

        public FluidPlaceBlockEvent(LevelAccessor level, BlockPos pos, BlockPos liquidPos, BlockState state) {
            super(level, pos, state);
            this.liquidPos = liquidPos;
            this.newState = state;
            this.originalState = level.getBlockState(pos);
        }

        public BlockPos getLiquidPos() {
            return this.liquidPos;
        }

        public BlockState getNewState() {
            return this.newState;
        }

        public void setNewState(BlockState state) {
            this.newState = state;
        }

        public BlockState getOriginalState() {
            return this.originalState;
        }
    }

    public static class FarmlandTrampleEvent extends BlockEvent implements ICancellableEvent {
        private final Entity entity;
        private final double fallDistance;

        public FarmlandTrampleEvent(Level level, BlockPos pos, BlockState state, double fallDistance, Entity entity) {
            super(level, pos, state);
            this.entity = entity;
            this.fallDistance = fallDistance;
        }

        public Entity getEntity() {
            return this.entity;
        }

        public double getFallDistance() {
            return this.fallDistance;
        }
    }

    public static class PortalSpawnEvent extends BlockEvent implements ICancellableEvent {
        private final PortalShape size;

        public PortalSpawnEvent(LevelAccessor level, BlockPos pos, BlockState state, PortalShape size) {
            super(level, pos, state);
            this.size = size;
        }

        public PortalShape getPortalSize() {
            return this.size;
        }
    }
}
