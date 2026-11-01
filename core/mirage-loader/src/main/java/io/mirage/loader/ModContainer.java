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
     * 脚本 mod 的根目录（含 `scripts/*.mgs` 与描述符）。非脚本 mod 为 null。
     *
     * <p>目录形态是 MGScript 的「零工具链」入口：不需要 JDK、不需要 Gradle、不需要打 jar，
     * 在 mods 下建一个文件夹写脚本即可。S6 见到本字段非 null 就走脚本分支，不再找 Java 主类。
     */
    public final Path scriptRoot;

    /** 是否来自 NeoForge mod（neoforge.mods.toml + @Mod 检测分支）。
     * Launcher 据此允许 {@code MyMod(IEventBus)} 注入式构造回退。
     */
    public boolean fromNeoForge;

    /**
     * fabric.mod.json "mixins" 字段声明的 mixin config 路径（原样，可含子目录；可能不带 .json 后缀）。
     * 非 fabric 来源为 null。S3 收集 mixin config 时作为权威来源，根目录盲扫只做兜底。
     */
    public List<String> fabricMixinConfigs;

    /**
     * fabric.mod.json "depends" 字段原始映射（key 可能含运行时键 minecraft/java/fabricloader，
     * value 可能是 String / List / 其他 JSON 类型）。非 fabric 来源为 null。
     * S3 发现收尾时经 {@link io.mirage.loader.depends.FabricDepends#split} 过滤，
     * 可解析项写入 {@code manifest.depends} 供拓扑排序，缺失项 WARN。
     */
    public Map<String, Object> fabricDependsRaw;

    /**
     * NeoForge 识别结果（id/version/mainClass/版本区间）。非 NeoForge 来源为 null。
     * MSR P2：S6 的被动捕获要把录制结果写回 `.mid-cache`，而 {@link MidCache#storePassive}
     * 需要这份元数据（缓存文件按 detected.id 定位、detected 本身也要保留）。
     */
    public NeoForgeModDetector.Detected detected;

    /** 版本伪装用：该 mod 应看到的版本区间（NeoForge / MC），非 NeoForge mod 为 null。 */
    public String neoForgeVersionRange() {
        return detected != null ? detected.neoForgeVersionRange : null;
    }

    public String mcVersionRange() {
        return detected != null ? detected.mcVersionRange : null;
    }

    public ModContainer(ModManifest manifest, Path jarFile) {
        this(manifest, jarFile, null, null, null);
    }

    public ModContainer(ModManifest manifest, Path jarFile, Map<String, List<String>> fabricEntrypoints) {
        this(manifest, jarFile, fabricEntrypoints, null, null);
    }

    public ModContainer(ModManifest manifest, Path jarFile,
                        Map<String, List<String>> fabricEntrypoints,
                        IrDescriptorParser.IrResult ir) {
        this(manifest, jarFile, fabricEntrypoints, ir, null);
    }

    public ModContainer(ModManifest manifest, Path jarFile,
                        Map<String, List<String>> fabricEntrypoints,
                        IrDescriptorParser.IrResult ir,
                        Path scriptRoot) {
        this.manifest = manifest;
        this.jarFile = jarFile;
        this.fabricEntrypoints = fabricEntrypoints;
        this.ir = ir;
        this.scriptRoot = scriptRoot;
    }

    @Override
    public String toString() {
        return manifest.id + " v" + manifest.version
                + " (" + (manifest.name != null ? manifest.name : manifest.id) + ")"
                + " [" + jarFile.getFileName() + "]"
                + (ir != null ? " [IR " + ir.specVersion + "]" : " [legacy]");
    }
}
