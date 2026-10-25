package net.neoforged.neoforge.event.entity.player;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.TriState;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.bus.api.ICancellableEvent;

// 兼容 stub：NeoForge 26.x 的玩家交互事件及其内部事件。
public abstract class PlayerInteractEvent extends PlayerEvent {
    private final InteractionHand hand;
    private final BlockPos pos;
    private final Direction face;

    protected PlayerInteractEvent(Player player, InteractionHand hand, BlockPos pos, Direction face) {
        super(player);
        this.hand = hand;
        this.pos = pos;
        this.face = face;
    }

    public static class EntityInteract extends PlayerInteractEvent implements ICancellableEvent {
        private InteractionResult cancellationResult = InteractionResult.PASS;
        private final Entity target;
        private final net.minecraft.world.phys.Vec3 location;

        public EntityInteract(Player player, InteractionHand hand, Entity target, net.minecraft.world.phys.Vec3 location) {
            super(player, hand, target != null ? target.blockPosition() : player.blockPosition(), null);
            this.target = target;
            this.location = location;
        }

        public Entity getTarget() {
            return this.target;
        }

        public net.minecraft.world.phys.Vec3 getLocation() {
            return this.location;
        }

        public InteractionResult getCancellationResult() {
            return this.cancellationResult;
        }

        public void setCancellationResult(InteractionResult result) {
            this.cancellationResult = result;
        }
    }

    public static class RightClickBlock extends PlayerInteractEvent implements ICancellableEvent {
        private InteractionResult cancellationResult = InteractionResult.PASS;
        private TriState useBlock = TriState.DEFAULT;
        private TriState useItem = TriState.DEFAULT;
        private final BlockHitResult hitVec;

        public RightClickBlock(Player player, InteractionHand hand, BlockPos pos, BlockHitResult hitVec) {
            super(player, hand, pos, hitVec != null ? hitVec.getDirection() : null);
            this.hitVec = hitVec;
        }

        public TriState getUseBlock() {
            return this.useBlock;
        }

        public TriState getUseItem() {
            return this.useItem;
        }

        public BlockHitResult getHitVec() {
            return this.hitVec;
        }

        public void setUseBlock(TriState triggerBlock) {
            this.useBlock = triggerBlock;
        }

        public void setUseItem(TriState triggerItem) {
            this.useItem = triggerItem;
        }

        public InteractionResult getCancellationResult() {
            return this.cancellationResult;
        }

        public void setCancellationResult(InteractionResult result) {
            this.cancellationResult = result;
        }
    }

    public static class RightClickItem extends PlayerInteractEvent implements ICancellableEvent {
        private InteractionResult cancellationResult = InteractionResult.PASS;

        public RightClickItem(Player player, InteractionHand hand) {
            super(player, hand, player.blockPosition(), null);
        }

        public InteractionResult getCancellationResult() {
            return this.cancellationResult;
        }

        public void setCancellationResult(InteractionResult result) {
            this.cancellationResult = result;
        }
    }

    public static class RightClickEmpty extends PlayerInteractEvent {
        public RightClickEmpty(Player player, InteractionHand hand) {
            super(player, hand, player.blockPosition(), null);
        }
    }

    public static class LeftClickBlock extends PlayerInteractEvent implements ICancellableEvent {
        private TriState useBlock = TriState.DEFAULT;
        private TriState useItem = TriState.DEFAULT;
        private final Action action;

        public LeftClickBlock(Player player, BlockPos pos, Direction face, Action action) {
            super(player, InteractionHand.MAIN_HAND, pos, face);
            this.action = action;
        }

        public TriState getUseBlock() {
            return this.useBlock;
        }

        public TriState getUseItem() {
            return this.useItem;
        }

        public Action getAction() {
            return this.action;
        }

        public void setUseBlock(TriState triggerBlock) {
            this.useBlock = triggerBlock;
        }

        public void setUseItem(TriState triggerItem) {
            this.useItem = triggerItem;
        }

        public enum Action {
            START,
            STOP,
            ABORT,
            CLIENT_HOLD
        }
    }

    public static class LeftClickEmpty extends PlayerInteractEvent {
        public LeftClickEmpty(Player player) {
            super(player, InteractionHand.MAIN_HAND, player.blockPosition(), null);
        }
    }

    public InteractionHand getHand() {
        return this.hand;
    }

    public ItemStack getItemStack() {
        return this.getEntity().getItemInHand(this.hand);
    }

    public BlockPos getPos() {
        return this.pos;
    }

    public Direction getFace() {
        return this.face;
    }

    public Level getLevel() {
        return this.getEntity().level();
    }
}
