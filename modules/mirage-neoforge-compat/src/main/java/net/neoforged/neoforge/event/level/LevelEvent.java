package net.neoforged.neoforge.event.level;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.util.random.Weighted;
import net.minecraft.util.random.WeightedList;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.biome.MobSpawnSettings;
import net.minecraft.world.level.storage.ServerLevelData;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.ICancellableEvent;

// 兼容 stub：NeoForge 26.x 的关卡事件基类及其内部事件。
public abstract class LevelEvent extends Event {
    private final LevelAccessor level;

    public LevelEvent(LevelAccessor level) {
        this.level = level;
    }

    public LevelAccessor getLevel() {
        return this.level;
    }

    public static class Load extends LevelEvent {
        public Load(LevelAccessor level) {
            super(level);
        }
    }

    public static class Unload extends LevelEvent {
        public Unload(LevelAccessor level) {
            super(level);
        }
    }

    public static class Save extends LevelEvent {
        public Save(LevelAccessor level) {
            super(level);
        }
    }

    public static class CreateSpawnPosition extends LevelEvent implements ICancellableEvent {
        private final ServerLevelData settings;

        public CreateSpawnPosition(LevelAccessor level, ServerLevelData settings) {
            super(level);
            this.settings = settings;
        }

        public ServerLevelData getSettings() {
            return this.settings;
        }
    }

    public static class PotentialSpawns extends LevelEvent implements ICancellableEvent {
        private final MobCategory mobCategory;
        private final BlockPos pos;
        private final List<Weighted<MobSpawnSettings.SpawnerData>> spawnerData;

        public PotentialSpawns(LevelAccessor level, MobCategory category, BlockPos pos, WeightedList<MobSpawnSettings.SpawnerData> oldList) {
            super(level);
            this.mobCategory = category;
            this.pos = pos;
            this.spawnerData = oldList == null ? new java.util.ArrayList<>() : new java.util.ArrayList<>(oldList.unwrap());
        }

        public MobCategory getMobCategory() {
            return this.mobCategory;
        }

        public BlockPos getPos() {
            return this.pos;
        }

        public List<Weighted<MobSpawnSettings.SpawnerData>> getSpawnerDataList() {
            return this.spawnerData;
        }

        public void addSpawnerData(Weighted<MobSpawnSettings.SpawnerData> data) {
            this.spawnerData.add(data);
        }

        public void removeSpawnerData(Weighted<MobSpawnSettings.SpawnerData> data) {
            this.spawnerData.remove(data);
        }
    }
}
