package io.mirage.loader;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * 运行时 Mod 容器：manifest + jar 路径 + entrypoints 分派表 +（可选）IR 原始结果。
 */
public class ModContainer {
    public final ModManifest manifest;
    public final Path jarFile;

    /**
     * entrypoints 分派表：side("main"/"client"/"server") -> 类名列表。
     * fabric.mod.json 与 mod.mirage.json(IR) 都填这张表；纯 legacy META-INF/mirage.mod.json 为 null。
     */
    public final Map<String, List<String>> fabricEntrypoints;

    /**
     * IR v0.1（mod.mirage.json）解析结果。非 IR 来源（legacy mirage.mod.json / fabric.mod.json）为 null。
     * transforms/events/registries/namespaces 等已接住，暂未消费。
     */
    public final IrDescriptorParser.IrResult ir;

    /**
     * 是否来自 NeoForge mod（neoforge.mods.toml + @Mod 检测分支）。
     * Launcher 据此允许 {@code MyMod(IEventBus)} 注入式构造回退。
     */
    public boolean fromNeoForge;

    public ModContainer(ModManifest manifest, Path jarFile) {
        this(manifest, jarFile, null, null);
    }

    public ModContainer(ModManifest manifest, Path jarFile, Map<String, List<String>> fabricEntrypoints) {
        this(manifest, jarFile, fabricEntrypoints, null);
    }

    public ModContainer(ModManifest manifest, Path jarFile,
                        Map<String, List<String>> fabricEntrypoints,
                        IrDescriptorParser.IrResult ir) {
        this.manifest = manifest;
        this.jarFile = jarFile;
        this.fabricEntrypoints = fabricEntrypoints;
        this.ir = ir;
    }

    @Override
    public String toString() {
        return manifest.id + " v" + manifest.version
                + " (" + (manifest.name != null ? manifest.name : manifest.id) + ")"
                + " [" + jarFile.getFileName() + "]"
                + (ir != null ? " [IR " + ir.specVersion + "]" : " [legacy]");
    }
}
