package net.neoforged.neoforge.event.entity.player;

import java.io.File;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.ICancellableEvent;
import net.neoforged.neoforge.event.entity.living.LivingEvent;

// 兼容 stub：NeoForge 26.x 的玩家事件基类及其内部事件。
public abstract class PlayerEvent extends LivingEvent {
    private final Player player;

    public PlayerEvent(Player player) {
        super(player);
        this.player = player;
    }

    @Override
    public Player getEntity() {
        return this.player;
    }

    public static class HarvestCheck extends PlayerEvent {
        private final BlockState state;
        private final BlockGetter level;
        private final BlockPos pos;
        private boolean success;

        public HarvestCheck(Player player, BlockState state, BlockGetter level, BlockPos pos, boolean success) {
            super(player);
            this.state = state;
            this.level = level;
            this.pos = pos;
            this.success = success;
        }

        public BlockState getTargetBlock() {
            return this.state;
        }

        public BlockGetter getLevel() {
            return this.level;
        }

        public BlockPos getPos() {
            return this.pos;
        }

        public boolean canHarvest() {
            return this.success;
        }

        public void setCanHarvest(boolean success) {
            this.success = success;
        }
    }

    public static class BreakSpeed extends PlayerEvent implements ICancellableEvent {
        private final BlockState state;
        private final float originalSpeed;
        private float newSpeed;
        private final Optional<BlockPos> pos;

        public BreakSpeed(Player player, BlockState state, float original, BlockPos pos) {
            super(player);
            this.state = state;
            this.originalSpeed = original;
            this.newSpeed = original;
            this.pos = Optional.ofNullable(pos);
        }

        public BlockState getState() {
            return this.state;
        }

        public float getOriginalSpeed() {
            return this.originalSpeed;
        }

        public float getNewSpeed() {
            return this.newSpeed;
        }

        public void setNewSpeed(float newSpeed) {
            this.newSpeed = newSpeed;
        }

        public Optional<BlockPos> getPosition() {
            return this.pos;
        }
    }

    public static class NameFormat extends PlayerEvent {
        private final Component username;
        private Component displayname;

        public NameFormat(Player player, Component username) {
            super(player);
            this.username = username;
            this.displayname = username;
        }

        public Component getUsername() {
            return this.username;
        }

        public Component getDisplayname() {
            return this.displayname;
        }

        public void setDisplayname(Component displayname) {
            this.displayname = displayname;
        }
    }

    public static class TabListNameFormat extends PlayerEvent {
        private Component displayName;

        public TabListNameFormat(Player player) {
            super(player);
        }

        public Component getDisplayName() {
            return this.displayName;
        }

        public void setDisplayName(Component displayName) {
            this.displayName = displayName;
        }
    }

    public static class Clone extends PlayerEvent {
        private final Player original;
        private final boolean wasDeath;

        public Clone(Player newPlayer, Player oldPlayer, boolean wasDeath) {
            super(newPlayer);
            this.original = oldPlayer;
            this.wasDeath = wasDeath;
        }

        public Player getOriginal() {
            return this.original;
        }

        public boolean isWasDeath() {
            return this.wasDeath;
        }
    }

    public static class StartTracking extends PlayerEvent {
        private final Entity target;

        public StartTracking(Player player, Entity target) {
            super(player);
            this.target = target;
        }

        public Entity getTarget() {
            return this.target;
        }
    }

    public static class StopTracking extends PlayerEvent {
        private final Entity target;

        public StopTracking(Player player, Entity target) {
            super(player);
            this.target = target;
        }

        public Entity getTarget() {
            return this.target;
        }
    }

    public static class LoadFromFile extends PlayerEvent {
        private final File playerDirectory;
        private final String playerUUID;

        public LoadFromFile(Player player, File originDirectory, String playerUUID) {
            super(player);
            this.playerDirectory = originDirectory;
            this.playerUUID = playerUUID;
        }

        public File getPlayerFile(String suffix) {
            return new File(this.playerDirectory, this.playerUUID + "." + suffix);
        }

        public File getPlayerDirectory() {
            return this.playerDirectory;
        }

        public String getPlayerUUID() {
            return this.playerUUID;
        }
    }

    public static class SaveToFile extends PlayerEvent {
        private final File playerDirectory;
        private final String playerUUID;

        public SaveToFile(Player player, File originDirectory, String playerUUID) {
            super(player);
            this.playerDirectory = originDirectory;
            this.playerUUID = playerUUID;
        }

        public File getPlayerFile(String suffix) {
            return new File(this.playerDirectory, this.playerUUID + "." + suffix);
        }

        public File getPlayerDirectory() {
            return this.playerDirectory;
        }

        public String getPlayerUUID() {
            return this.playerUUID;
        }
    }

    public static class ItemCraftedEvent extends PlayerEvent {
        private final ItemStack crafting;
        private final Container craftMatrix;

        public ItemCraftedEvent(Player player, ItemStack crafting, Container craftMatrix) {
            super(player);
            this.crafting = crafting;
            this.craftMatrix = craftMatrix;
        }

        public ItemStack getCrafting() {
            return this.crafting;
        }

        public Container getInventory() {
            return this.craftMatrix;
        }
    }

    public static class ItemSmeltedEvent extends PlayerEvent {
        private final ItemStack smelting;
        private final int amountRemoved;

        public ItemSmeltedEvent(Player player, ItemStack crafting, int amountRemoved) {
            super(player);
            this.smelting = crafting;
            this.amountRemoved = amountRemoved;
        }

        public ItemStack getSmelting() {
            return this.smelting;
        }

        public int getAmountRemoved() {
            return this.amountRemoved;
        }
    }

    public static class PlayerLoggedInEvent extends PlayerEvent {
        public PlayerLoggedInEvent(Player player) {
            super(player);
        }
    }

    public static class PlayerLoggedOutEvent extends PlayerEvent {
        public PlayerLoggedOutEvent(Player player) {
            super(player);
        }
    }

    public static class PlayerRespawnEvent extends PlayerEvent {
        private final boolean endConquered;

        public PlayerRespawnEvent(Player player, boolean endConquered) {
            super(player);
            this.endConquered = endConquered;
        }

        public boolean isEndConquered() {
            return this.endConquered;
        }
    }

    public static class PlayerChangedDimensionEvent extends PlayerEvent {
        private final ResourceKey<Level> fromDim;
        private final ResourceKey<Level> toDim;

        public PlayerChangedDimensionEvent(Player player, ResourceKey<Level> fromDim, ResourceKey<Level> toDim) {
            super(player);
            this.fromDim = fromDim;
            this.toDim = toDim;
        }

        public ResourceKey<Level> getFrom() {
            return this.fromDim;
        }

        public ResourceKey<Level> getTo() {
            return this.toDim;
        }
    }

    public static class PlayerChangeGameModeEvent extends PlayerEvent implements ICancellableEvent {
        private final GameType currentGameMode;
        private GameType newGameMode;

        public PlayerChangeGameModeEvent(Player player, GameType currentGameMode, GameType newGameMode) {
            super(player);
            this.currentGameMode = currentGameMode;
            this.newGameMode = newGameMode;
        }

        public GameType getCurrentGameMode() {
            return this.currentGameMode;
        }

        public GameType getNewGameMode() {
            return this.newGameMode;
        }

        public void setNewGameMode(GameType newGameMode) {
            this.newGameMode = newGameMode;
        }
    }
}
