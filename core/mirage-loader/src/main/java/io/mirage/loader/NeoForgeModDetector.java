package io.mirage.loader;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * 检测 jar 中的 NeoForge mod：
 *   1. 找 META-INF/neoforge.mods.toml（或根目录 neoforge.mods.toml）
 *   2. 最小解析 [[mods]] 段内的 modId / version / displayName
 *   3. 用 ASM 扫描 class 找带 @Mod 注解的主类
 *
 * 不解析依赖、不做事件总线，只做最小可行识别。
 */
public class NeoForgeModDetector {

    /** 当前 AG（转译/识别）版本。写入 MID 缓存文件头，AG 升级后旧缓存自动失效重转。 */
    public static final String AG_VERSION = "0.1.1";

    /** 检测结果。字段为 public 非 final，便于 Gson 反序列化。 */
    public static class Detected {
        public String id;
        public String version;
        public String displayName;
        public String mainClass;
        /** mod 声明期望的 NeoForge 版本区间（[[dependencies.*]] modId="neoforge" 的 versionRange）。 */
        public String neoForgeVersionRange;
        /** mod 声明期望的 MC 版本区间（[[dependencies.*]] modId="minecraft" 的 versionRange）。 */
        public String mcVersionRange;

        /** 强制（mandatory）的 mod 间依赖：被依赖 modId -> 版本区间（不含平台依赖 neoforge/minecraft 等）。
         *  供 Loader 的 DependencySorter 拓扑排序；可选依赖不入此表，避免缺失时误 FATAL。 */
        public Map<String, String> depends = new LinkedHashMap<>();

        /** Gson 反序列化用无参构造。 */
        public Detected() {}

        public Detected(String id, String version, String displayName, String mainClass) {
            this.id = id;
            this.version = version;
            this.displayName = displayName;
            this.mainClass = mainClass;
        }
    }

    private static final String NEO_TOML_META = "META-INF/neoforge.mods.toml";
    private static final String NEO_TOML_ROOT = "neoforge.mods.toml";
    private static final String MOD_ANNOTATION_DESC = "Lnet/neoforged/fml/common/Mod;";

    /**
     * 检测 jar 是否为 NeoForge mod。
     * @param jar 已打开的 JarFile（调用方负责 close）
     * @return Detected，或 null（不是 neoforge mod / 描述符无效）
     */
    public static Detected detect(JarFile jar) throws IOException {
        // 1. 找 toml
        JarEntry entry = jar.getJarEntry(NEO_TOML_META);
        if (entry == null) entry = jar.getJarEntry(NEO_TOML_ROOT);
        if (entry == null) return null;

        String toml;
        try (InputStream in = jar.getInputStream(entry)) {
            toml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }

        // 2. 解析 [[mods]] 段内的 modId / version / displayName
        String modId = tomlValueInModsSection(toml, "modId");
        String version = tomlValueInModsSection(toml, "version");
        String displayName = tomlValueInModsSection(toml, "displayName");

        if (modId == null || modId.isEmpty()) return null;

        // 占位符兜底（如 ${file.jarVersion} 未被 Gradle 替换）
        if (version == null || version.isEmpty() || version.contains("${")) {
            version = "1.0.0";
        }

        // 3. 解析 [[dependencies.*]]：平台依赖(neoforge/minecraft)记录版本区间，
        //    mandatory 的 mod 间依赖写入 depends 供拓扑排序
        Detected d = new Detected(modId, version, displayName, null);
        parseDependencies(toml, d);

        // 4. ASM 扫描找 @Mod 主类
        d.mainClass = findModMainClass(jar);
        return d;
    }

    /**
     * 解析全部 {@code [[dependencies.<owningModId>]]} 段：
     *   - 平台依赖（neoforge / minecraft / forge / fml / java / javafml）只记录版本区间供诊断；
     *   - mandatory 的 mod 间依赖（依赖目标 modId）写入 {@code d.depends}，供 DependencySorter 拓扑排序；
     *   - 可选（mandatory=false）依赖不入约束表，避免缺失时误 FATAL。
     * 一个 jar 即一个 mod，所有依赖段都属于该 mod。
     */
    private static void parseDependencies(String toml, Detected d) {
        boolean inDeps = false;
        String curModId = null;
        String curVersionRange = null;
        boolean curMandatory = false;
        for (String raw : toml.split("\r?\n")) {
            String line = raw.trim();
            if (line.startsWith("[[")) {
                if (inDeps) recordDep(d, curModId, curVersionRange, curMandatory);
                inDeps = line.startsWith("[[dependencies.");
                curModId = null;
                curVersionRange = null;
                curMandatory = false;
                continue;
            }
            if (!inDeps) continue;
            if (line.startsWith("#") || line.isEmpty()) continue;
            int eq = line.indexOf('=');
            if (eq < 0) continue;
            String k = line.substring(0, eq).trim();
            String v = stripQuotesAndComment(line.substring(eq + 1).trim());
            switch (k) {
                case "modId" -> curModId = v;
                case "versionRange" -> curVersionRange = v;
                case "mandatory" -> curMandatory = Boolean.parseBoolean(v);
                default -> { /* ordering/side 等忽略 */ }
            }
        }
        if (inDeps) recordDep(d, curModId, curVersionRange, curMandatory);
    }

    private static void recordDep(Detected d, String modId, String versionRange, boolean mandatory) {
        if (modId == null || modId.isEmpty() || modId.equals(d.id)) return; // 跳过自身
        if (PLATFORM_MODS.contains(modId)) {
            if (modId.equals("neoforge")) d.neoForgeVersionRange = versionRange;
            else if (modId.equals("minecraft")) d.mcVersionRange = versionRange;
            return;
        }
        if (mandatory) {
            d.depends.put(modId, versionRange == null ? "" : versionRange);
        }
    }

    /** 非排序目标的平台/加载器依赖（不进 mod 间拓扑约束）。 */
    private static final Set<String> PLATFORM_MODS = Set.of(
            "neoforge", "minecraft", "forge", "fml", "java", "javafml");

    /** 去行尾注释 + 去首尾成对双引号。最小 toml 处理，够 dependency 段用。 */
    private static String stripQuotesAndComment(String v) {
        int hash = v.indexOf('#');
        if (hash >= 0) v = v.substring(0, hash).trim();
        if (v.startsWith("\"") && v.endsWith("\"") && v.length() >= 2) {
            v = v.substring(1, v.length() - 1);
        }
        return v;
    }

    /**
     * 只在 [[mods]] ... 下一段 [[...]] 之间提取 key = "value"。
     * 最小 toml 解析：双引号字符串、# 行尾注释、跳过 ''' 多行字符串行。
     */
    private static String tomlValueInModsSection(String toml, String key) {
        boolean inMods = false;
        for (String raw : toml.split("\r?\n")) {
            String line = raw.trim();
            if (line.startsWith("[[")) {
                // 进入新段：只有 [[mods]] 才算，其余段（dependencies / mixins）跳过
                // 注意行尾可能有注释（如 [[mods]] #mandatory），用 startsWith 而非 equals
                inMods = line.startsWith("[[mods]]");
                continue;
            }
            if (!inMods) continue;
            if (line.startsWith("#") || line.isEmpty()) continue;
            // 跳过多行字符串的内容行（''' 开头或纯文本行）
            if (line.startsWith("'''") || line.equals("'''")) continue;
            int eq = line.indexOf('=');
            if (eq < 0) continue;
            String k = line.substring(0, eq).trim();
            if (k.equals(key)) {
                String v = line.substring(eq + 1).trim();
                // 先去行尾注释（如 modId="jei" #mandatory）
                int hash = v.indexOf('#');
                if (hash >= 0) v = v.substring(0, hash).trim();
                // 再去引号
                if (v.startsWith("\"") && v.endsWith("\"") && v.length() >= 2) {
                    v = v.substring(1, v.length() - 1);
                }
                return v;
            }
        }
        return null;
    }

    /**
     * 用 ASM 扫描 jar 里的 class，找类级注解 @Mod（Lnet/neoforged/fml/common/Mod;）。
     * 跳过内部类（含 $），命中第一个即返回。
     */
    private static String findModMainClass(JarFile jar) throws IOException {
        Enumeration<JarEntry> entries = jar.entries();
        while (entries.hasMoreElements()) {
            JarEntry e = entries.nextElement();
            String name = e.getName();
            if (!name.endsWith(".class")) continue;
            if (name.contains("$")) continue; // 跳过内部类
            try (InputStream in = jar.getInputStream(e)) {
                ClassReader cr = new ClassReader(in);
                final boolean[] found = {false};
                cr.accept(new ClassVisitor(Opcodes.ASM9) {
                    @Override
                    public org.objectweb.asm.AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                        if (MOD_ANNOTATION_DESC.equals(descriptor)) {
                            found[0] = true;
                        }
                        return null;
                    }
                }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                if (found[0]) {
                    // ".class" 后缀长度为 6（. c l a s s），之前误写为 7 会吃掉类名最后一个字符
                    // （例如 ModMainForge.class -> ModMainForg），导致后续 ClassNotFoundException。
                    return name.substring(0, name.length() - ".class".length()).replace('/', '.');
                }
            } catch (Exception ignored) {
                // 跳过无法解析的 class
            }
        }
        return null;
    }
}
