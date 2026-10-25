package net.neoforged.neoforge.event;

import net.minecraft.core.HolderGetter;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.storage.loot.LootTable;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.ICancellableEvent;

// 兼容 stub：NeoForge 26.x 的战利品表加载事件。
public class LootTableLoadEvent extends Event implements ICancellableEvent {
    private final HolderGetter.Provider registries;
    private final Identifier name;
    private LootTable table;

    public LootTableLoadEvent(HolderGetter.Provider registries, Identifier name, LootTable table) {
        this.registries = registries;
        this.name = name;
        this.table = table;
    }

    public HolderGetter.Provider getRegistries() {
        return this.registries;
    }

    public Identifier getName() {
        return this.name;
    }

    public LootTable getTable() {
        return this.table;
    }

    public void setTable(LootTable table) {
        this.table = table;
    }
}
