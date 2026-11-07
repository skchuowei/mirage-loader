package net.neoforged.fml;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforgespi.language.IModInfo;

/**
 * 运行时桥接 stub：mod 容器（clean-room）。
 *
 * <p>真实 NeoForge 的 {@code ModContainer} 由加载器创建；这里提供最小只读表面，
 * 供真实 mod 的 {@code (IEventBus, ModContainer)} 构造器与 {@code getModInfo()} 调用。
 * 由 {@code NeoCompatBootstrap.registerMods} 统一登记到 {@link ModList}。
 */
public final class ModContainer {

    private final IModInfo modInfo;
    private final IEventBus eventBus;

    public ModContainer(IModInfo modInfo, IEventBus eventBus) {
        this.modInfo = modInfo;
        this.eventBus = eventBus;
    }

    public String getModId() {
        return modInfo.getModId();
    }

    public IModInfo getModInfo() {
        return modInfo;
    }

    public IEventBus getEventBus() {
        return eventBus;
    }

    @Override
    public String toString() {
        return "ModContainer[" + getModId() + "]";
    }
}
