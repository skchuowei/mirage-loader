package io.mirage.msr.agent;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.CodeSource;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MSR P3 · mod 归属判定器（2026-11-07）。
 *
 * <p><b>为什么需要它</b>：P3 观测的是<b>游戏侧</b>状态（注册表新增 id、事件订阅），
 * 这些数据本身不带 mod 身份 —— 原版的注册和 mod 的注册在字节码层面长得一样。
 * 要把「基线 vs 录制」的差集归因到具体 mod，就必须能回答
 * 「这条记录是哪个 mod 干的」。
 *
 * <p><b>判定链路（不猜，按可靠性递降）</b>：
 * <ol>
 *   <li><b>栈回溯</b>：用 {@link StackWalker} 找第一个<b>不属于平台包</b>的声明类
 *       （排除 {@code net.minecraft.*} / {@code net.neoforged.*} / {@code java.*} /
 *       {@code jdk.*} / {@code io.mirage.*} / {@code org.lwjgl.*}）；</li>
 *   <li><b>代码源 jar</b>：取该类的 {@link CodeSource} → jar 路径；</li>
 *   <li><b>modId 反查</b>：打开该 jar，读 {@code META-INF/neoforge.mods.toml}
 *       （老式 {@code META-INF/mods.toml} 兜底），取第一个 {@code modId="..."}。</li>
 * </ol>
 *
 * <p><b>分不清就标 unknown</b>：MSR 的一贯原则 —— 宁可标「归属未知」，
 * 也不给一个可能错的 modId（错归属会让 diff 结果被误读成「另一个 mod 的贡献」）。
 *
 * <p><b>合规边界</b>：这里读的是 mod 的<b>元数据</b>（toml 里的 modId 字符串），
 * 不是任何实现代码；NeoForge / MC 本体为 LGPL 2.1，本工具不做其实现的复制或移植。
 */
public final class ModIdResolver {

    /** 平台包前缀：这些包的类不算「mod 侧」。 */
    private static final String[] PLATFORM_PREFIXES = {
            "net.minecraft.", "net.neoforged.", "net.mojang.", "com.mojang.",
            "java.", "javax.", "jdk.", "sun.", "com.sun.",
            "io.mirage.", "org.lwjgl.", "org.slf4j.", "org.apache.logging.",
            "it.unimi.", "org.jetbrains.", "com.google.", "org.lwjgl.",
            "net.fabricmc.", "cpw.mods.", "org.spongepowered.",
    };

    /** modId 正则：{@code modId="xxx"}。 */
    private static final Pattern MOD_ID = Pattern.compile("(?m)^\\s*modId\\s*=\\s*\"([^\"]+)\"");

    /** mod 元数据在 jar 内的候选路径。 */
    private static final String[] META_PATHS = {
            "META-INF/neoforge.mods.toml",
            "META-INF/mods.toml",
            "META-INF/MANIFEST.MF",
    };

    /** jar 路径 → modId 缓存（jar 不会在一次运行里变）。 */
    private static final Map<String, String> CACHE = new ConcurrentHashMap<>();

    /** 负缓存：确认不是 mod 的 jar，避免重复开 jar。 */
    private static final Map<String, String> NOT_MOD = new ConcurrentHashMap<>();

    private ModIdResolver() {}

    /**
     * 判定当前调用点的归属 modId。
     *
     * @param declaredModId 调用方已知的 modId（可为 null —— 此时走栈回溯）
     * @return modId；判定不出时返回 {@code "unknown"}
     */
    public static String resolve(String declaredModId) {
        if (declaredModId != null && !declaredModId.isBlank()) return declaredModId;
        try {
            Class<?> modClass = findModClass();
            if (modClass == null) return "unknown";
            String jar = jarOf(modClass);
            if (jar == null) return "unknown";
            String modId = CACHE.get(jar);
            if (modId != null) return modId;
            String neg = NOT_MOD.get(jar);
            if (neg != null) return "unknown";
            modId = readModId(jar);
            if (modId == null) {
                NOT_MOD.put(jar, "1");
                return "unknown";
            }
            CACHE.put(jar, modId);
            return modId;
        } catch (Throwable t) {
            return "unknown";
        }
    }

    /**
     * 栈回溯找第一个「非平台」的声明类。
     *
     * @return 候选类；找不到返回 null
     */
    private static Class<?> findModClass() {
        return StackWalker.getInstance().walk(frames -> frames
                .map(StackWalker.StackFrame::getDeclaringClass)
                .filter(c -> c != null && !isPlatform(c.getName()))
                .findFirst()
                .orElse(null));
    }

    /** 判断类名是否属于平台包（平台包的类不算 mod 侧）。 */
    static boolean isPlatform(String className) {
        if (className == null) return true;
        for (String p : PLATFORM_PREFIXES) {
            if (className.startsWith(p)) return true;
        }
        // 匿名内部类 / lambda 的合成类名（Foo$1 / Foo$$Lambda$0）本身也带外层前缀，
        // 这里不再特判 —— 它们的 ProtectionDomain 与外层类一致。
        return false;
    }

    /**
     * 取类的代码源 jar 路径。
     *
     * @return jar 路径；不是 jar（目录 / 无来源）返回 null
     */
    static String jarOf(Class<?> cls) {
        try {
            CodeSource cs = cls.getProtectionDomain().getCodeSource();
            if (cs == null) return null;
            URI uri = cs.getLocation().toURI();
            Path p = Paths.get(uri);
            if (!Files.exists(p)) return null;
            if (Files.isDirectory(p)) {
                // 开发态（classes 目录）：取上级目录名当线索，但不当 modId
                return null;
            }
            return p.toAbsolutePath().toString();
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 读 jar 里的 modId。
     *
     * @param jarPath jar 绝对路径
     * @return modId；不是 mod jar 或读不到返回 null
     */
    static String readModId(String jarPath) {
        try (JarFile jar = new JarFile(jarPath)) {
            for (String meta : META_PATHS) {
                JarEntry entry = jar.getJarEntry(meta);
                if (entry == null) continue;
                try (InputStream in = jar.getInputStream(entry)) {
                    String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                    Matcher m = MOD_ID.matcher(text);
                    if (m.find()) {
                        String id = m.group(1).trim();
                        if (!id.isEmpty()) return id;
                    }
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // 读不到就当不是 mod jar
        }
        return null;
    }

    /**
     * 判定一次<b>注册</b>的归属 modId —— 以 id 的 namespace 为准。
     *
     * <p><b>为什么不用栈回溯</b>：注册走到 {@code MappedRegistry#register} 时，
     * 调用栈上只有平台帧（{@code DeferredRegister} 的 lambda、{@code EventBus} 派发），
     * mod 自己的栈帧<b>早就不在了</b>（supplier 在 enter 时就已返回）——
     * 本轮实测 7153 条注册全部落成 {@code unknown}。
     *
     * <p>而 {@code namespace}（形如 {@code msrprobe:probe_block} 里的 {@code msrprobe}）
     * 才是 NeoForge 的注册身份约定：{@code namespace == modId}。
     * 这既是更可靠的归属信号，也是加载器回放时真正要用的键。
     *
     * <p>{@code minecraft:} 命名空间没有归属信息（原版内容，或 mod 借原版命名空间注册），
     * 此时回退栈回溯；仍判不出就是 {@code unknown} —— 不给可能错的归属。
     *
     * @param namespacedId 形如 {@code namespace:path} 的注册 id
     * @return modId；判不出返回 {@code "unknown"}
     */
    public static String resolveByRegistrationId(String namespacedId) {
        String ns = namespaceOf(namespacedId);
        if (ns != null && !ns.isEmpty() && !"minecraft".equals(ns)) {
            return ns;
        }
        return resolve(null);
    }

    /** 取 {@code namespace:path} 的 namespace 段；不合法返回 null。 */
    static String namespaceOf(String id) {
        if (id == null) return null;
        int idx = id.indexOf(':');
        if (idx <= 0) return null;
        return id.substring(0, idx);
    }

    /** 便捷：直接判定一个实例所属的 modId（用于事件监听器归属）。 */
    public static String resolveForInstance(Object instance) {
        if (instance == null) return "unknown";
        try {
            String jar = jarOf(instance.getClass());
            if (jar == null) return "unknown";
            String cached = CACHE.get(jar);
            if (cached != null) return cached;
            String neg = NOT_MOD.get(jar);
            if (neg != null) return "unknown";
            String modId = readModId(jar);
            if (modId == null) {
                NOT_MOD.put(jar, "1");
                return "unknown";
            }
            CACHE.put(jar, modId);
            return modId;
        } catch (Throwable t) {
            return "unknown";
        }
    }

    /** 缓存统计（自检用）。 */
    public static Map<String, String> cacheSnapshot() {
        return new LinkedHashMap<>(CACHE);
    }

    /** 规范化 jar 路径（用于缓存键，避免大小写 / 分隔符差异）。 */
    static String normalize(String path) {
        return path == null ? null : path.toLowerCase(Locale.ROOT).replace('\\', '/');
    }
}
