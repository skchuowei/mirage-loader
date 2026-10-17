package io.mirage.loader.runtime;

import io.mirage.api.mod.ModMetadata;
import io.mirage.loader.ModContainer;
import io.mirage.loader.ModManifest;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 把 core 内部的 {@link ModContainer} 适配成公共 API 的 {@link ModMetadata}。
 *
 * <p>Mod 代码拿到的 ModMetadata 永远是这个实现；
 * 不直接暴露 ModContainer / ModManifest 是为了让 core 内部 POJO 可以自由演化。
 */
public final class ModMetadataAdapter implements ModMetadata {

    private final ModContainer container;

    public ModMetadataAdapter(ModContainer container) {
        this.container = container;
    }

    public ModContainer unwrap() {
        return container;
    }

    @Override public String id()        { return container.manifest.id; }
    @Override public String version()  { return container.manifest.version; }

    @Override
    public String displayName() {
        ModManifest m = container.manifest;
        return (m.name == null || m.name.isEmpty()) ? m.id : m.name;
    }

    @Override
    public Optional<String> mainClass() {
        String mc = container.manifest.mainClass;
        return (mc == null || mc.isEmpty()) ? Optional.empty() : Optional.of(mc);
    }

    @Override
    public Map<String, String> dependencies() {
        return container.manifest.depends == null ? Map.of() : container.manifest.depends;
    }

    @Override public String side() { return container.manifest.side == null ? "both" : container.manifest.side; }
    @Override public Path jarPath() { return container.jarFile; }

    @Override public List<String> authors()      { return List.of(); }
    @Override public Optional<String> description() { return Optional.empty(); }
    @Override public Optional<String> url()      { return Optional.empty(); }

    @Override
    public String toString() {
        return "ModMetadata[" + id() + " v" + version() + " (" + displayName() + ")]";
    }
}
