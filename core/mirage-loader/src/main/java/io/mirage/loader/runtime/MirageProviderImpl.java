package io.mirage.loader.runtime;

import io.mirage.api.MirageProvider;
import io.mirage.api.bus.EventBusHandle;
import io.mirage.api.env.Dist;
import io.mirage.api.log.ModLogger;
import io.mirage.api.mod.ModMetadata;
import io.mirage.loader.ModContainer;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link MirageProvider} 的加载器默认实现。
 *
 * <p>由 Launcher 在启动早期构造并通过 {@code Mirage.bootstrap(this)} 注入。
 * 持有 gameDir / modsDir / dist / 已发现 mods / 事件总线 等运行时状态。
 */
public final class MirageProviderImpl implements MirageProvider {

    private final Dist dist;
    private final Path gameDir;
    private final Path modsDir;
    private final List<ModMetadata> mods;
    private final Map<String, ModMetadata> byId;
    private final EventBusHandle bus;
    private final String loaderVersion;
    private final Map<String, ModLogger> loggers = new ConcurrentHashMap<>();

    public MirageProviderImpl(Dist dist, Path gameDir, List<ModContainer> discovered,
                              EventBusHandle bus, String loaderVersion) {
        this.dist = dist;
        this.gameDir = gameDir;
        this.modsDir = gameDir.resolve("mods");
        this.bus = bus;
        this.loaderVersion = loaderVersion;

        this.mods = new ArrayList<>(discovered.size());
        this.byId = new ConcurrentHashMap<>();
        for (ModContainer c : discovered) {
            ModMetadata meta = new ModMetadataAdapter(c);
            this.mods.add(meta);
            this.byId.put(meta.id(), meta);
        }
    }

    @Override public Dist getDist()                  { return dist; }
    @Override public List<ModMetadata> getMods()     { return List.copyOf(mods); }

    @Override
    public Optional<ModMetadata> getMod(String modId) {
        return Optional.ofNullable(byId.get(modId));
    }

    @Override public Path getGameDir()  { return gameDir; }
    @Override public Path getModsDir()  { return modsDir; }
    @Override public EventBusHandle getEventBus() { return bus; }
    @Override public String getLoaderVersion()    { return loaderVersion; }

    @Override
    public ModLogger createLogger(String modId) {
        return loggers.computeIfAbsent(modId, ModLoggerImpl::new);
    }
}
