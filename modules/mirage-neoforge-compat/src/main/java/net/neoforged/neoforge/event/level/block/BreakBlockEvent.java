package net.neoforged.neoforge.event.level.block;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.ICancellableEvent;
import net.neoforged.neoforge.event.level.BlockEvent;

/**
 * 兼容 stub：玩家开始破坏方块时触发（26.x 取代旧 {@code BlockEvent.BreakEvent}）。
 *
 * <p>取消后 Mirage 侧会让 {@code ServerPlayerGameMode.destroyBlock} 直接返回 false，
 * 方块保持原状。
 */
public class BreakBlockEvent extends BlockEvent implements ICancellableEvent {
    private final Player player;
    private boolean notifyClient;

    public BreakBlockEvent(Level level, BlockPos pos, BlockState state, Player player) {
        super(level, pos, state);
        this.player = player;
    }

    public Player getPlayer() {
        return this.player;
    }

    /** 取消时默认通知客户端方块仍存在，避免幽灵方块。 */
    @Override
    public void setCanceled(boolean canceled) {
        super.setCanceled(canceled);
        if (canceled) {
            this.notifyClient = true;
        }
    }

    public boolean shouldNotifyClient() {
        return this.notifyClient;
    }

    public void setNotifyClient(boolean notifyClient) {
        this.notifyClient = notifyClient;
    }
}
