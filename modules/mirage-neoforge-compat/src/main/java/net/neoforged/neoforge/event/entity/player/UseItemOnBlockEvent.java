package net.neoforged.neoforge.event.entity.player;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.ICancellableEvent;

// 兼容 stub：NeoForge 26.x 的玩家对方块使用物品事件。
public class UseItemOnBlockEvent extends Event implements ICancellableEvent {
    private final Level level;
    private final Player player;
    private final InteractionHand hand;
    private final ItemStack heldItem;
    private final BlockPos pos;
    private final Direction face;
    private final UseOnContext context;
    private final UsePhase usePhase;
    private InteractionResult cancellationResult = InteractionResult.TRY_WITH_EMPTY_HAND;

    public UseItemOnBlockEvent(UseOnContext context, UsePhase usePhase) {
        this.level = context.getLevel();
        this.player = context.getPlayer();
        this.heldItem = context.getItemInHand();
        this.hand = context.getHand();
        this.pos = context.getClickedPos();
        this.face = context.getClickedFace();
        this.context = context;
        this.usePhase = usePhase;
    }

    public Player getPlayer() {
        return this.player;
    }

    public InteractionHand getHand() {
        return this.hand;
    }

    public ItemStack getItemStack() {
        return this.heldItem;
    }

    public BlockPos getPos() {
        return this.pos;
    }

    public Direction getFace() {
        return this.face;
    }

    public Level getLevel() {
        return this.level;
    }

    public UseOnContext getUseOnContext() {
        return this.context;
    }

    public UsePhase getUsePhase() {
        return this.usePhase;
    }

    public void cancelWithResult(InteractionResult result) {
        this.setCancellationResult(result);
        this.setCanceled(true);
    }

    public InteractionResult getCancellationResult() {
        return this.cancellationResult;
    }

    public void setCancellationResult(InteractionResult result) {
        this.cancellationResult = result;
    }

    public enum UsePhase {
        ITEM_BEFORE_BLOCK,
        BLOCK,
        ITEM_AFTER_BLOCK
    }
}
