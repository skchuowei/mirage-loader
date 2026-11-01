package io.mirage.loader.module;

import io.mirage.api.log.MirageLog;
import io.mirage.loader.MirageClassLoader;
import io.mirage.loader.ModContainer;
import io.mirage.loader.ModManifest;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 可选模块的发现与按需加载（AG 总计划 G9「模块懒加载」）。
 *
 * <p><b>目标</b>：可选模块（配置系统 / Kotlin 支持 / 网络层 / 将来的 KubeJS 等重依赖）
 * 默认<b>不进 fat jar、不参与启动期扫描</b>；只有当某个 mod 在
 * {@code META-INF/mirage.mod.json} 里显式声明 {@code "requires": ["<模块 id>"]} 时，
 * 该模块的 jar 才被 {@link MirageClassLoader#addJar(File)} 动态加入类加载器，
 * 并执行其初始化入口。
 *
 * <p><b>模块仓库</b>：默认 {@code <gameDir>/modules/}，可用系统属性
 * {@code mirage.modules.dir} 覆盖。目录不存在 → 视为「没有可选模块」，静默跳过
 * （<b>不是</b>错误：绝大多数安装都没有可选模块）。
 *
 * <p><b>为什么这样切分</b>：类加载是本项目最敏感的区域——历史上
 * {@code io.mirage.*} 前缀委派问题反复引发 {@code LinkageError: loader constraint violation}
 * （见 {@code docs/经验与踩坑.md} C11 / C25）。因此模块加载刻意做成
 * 「<b>加 URL</b>」而不是「<b>建 ClassLoader</b>」：
 * 模块的类与 mod 的类由<b>同一个</b> {@code MirageClassLoader} 加载，
 * 不存在两份 {@code net.minecraft.*} 的风险，也不需要给模块单开例外白名单。
 *
 * <p><b>包名约定</b>：模块提供的公共 API 类不得放在 {@code io.mirage.} 包下
 * （原因见 {@link ModuleDescriptor} 类注释）。模块若要用 Mirage 自有能力
 * （如 {@code DeferredRegister}，位于 {@code io.mirage.api.*}），那些类随 fat jar
 * 走 app loader，模块由 mcl 加载后引用它们时拿到的是同一份，安全。
 *
 * <p><b>失败一律降级不阻断</b>：模块是可选增强。缺失模块 → WARN 并继续；
 * 模块入口抛异常 → WARN 并继续；描述符非法 → WARN 并跳过该 jar。
 * 唯一会中断的是「类加载器本身出问题」——那属于加载器 bug，不是模块的问题。
 */
public final class ModuleRegistry {

    /** 模块仓库目录的系统属性名（覆盖 {@code <gameDir>/modules/}）。 */
    public static final String DIR_PROPERTY = "mirage.modules.dir";

    /** 默认仓库目录名（相对 gameDir）。 */
    public static final String DEFAULT_DIR_NAME = "modules";

    private ModuleRegistry() {
    }

    /**
     * 一次加载的结果（放进 {@code ctx.properties()} 的 {@code "S3.modules"}）。
     */
    public static final class Result {
        /** 实际加载的模块 id（按加载顺序）。 */
        public final List<String> loaded = new ArrayList<>();
        /** 仓库里存在但无人声明 requires 的模块 id。 */
        public final List<String> skipped = new ArrayList<>();
        /** mod 声明了但仓库里没有的模块 id。 */
        public final List<String> missing = new ArrayList<>();
        /** 模块 id → 需求来源的 mod id（用于日志与调试）。 */
        public final Map<String, String> requestedBy = new LinkedHashMap<>();

        public boolean isLoaded(String moduleId) {
            return loaded.contains(moduleId);
        }

        @Override
        public String toString() {
            return "loaded=" + loaded + ", skipped=" + skipped + ", missing=" + missing;
        }
    }

    /**
     * 扫描模块仓库、解析需求、计算闭包、按需加载。
     *
     * @param gameDir  游戏目录（仓库默认在它下面）
     * @param mcl      目标类加载器（模块 jar 会被加到它上面）
     * @param mods     已发现的 mod 列表（它们的 {@code requires} 构成需求集合）
     * @return 加载结果；任何单点失败都不抛出，只在结果里如实记录
     */
    public static Result load(Path gameDir, MirageClassLoader mcl, Collection<ModContainer> mods) {
        Result result = new Result();

        Path repoDir = resolveRepoDir(gameDir);
        Map<String, ModuleDescriptor> available = scanRepository(repoDir);
        if (available.isEmpty()) {
            return result;
        }
        MirageLog.MIRAGE.debug("模块仓库 " + repoDir + " 发现 " + available.size() + " 个模块");

        // ---- 需求集合：所有 mod 声明的 requires ----
        Map<String, String> requested = ModuleGraph.collectRequests(manifestsOf(mods));
        result.requestedBy.putAll(requested);

        // ---- 闭包：需要的模块 + 它自己依赖的模块（递归） ----
        Set<String> needed = ModuleGraph.neededModules(available, requested);

        // ---- 缺失：mod 要了但仓库里没有 ----
        for (Map.Entry<String, String> e : requested.entrySet()) {
            if (!available.containsKey(e.getKey())) {
                result.missing.add(e.getKey());
                MirageLog.MIRAGE.warn("mod " + e.getValue() + " 声明 requires 模块 "
                        + e.getKey() + "，但模块仓库里没有——该模块的功能不可用（游戏继续启动）");
            }
        }

        // ---- 加载闭包内的模块 ----
        for (String id : needed) {
            ModuleDescriptor d = available.get(id);
            if (d == null) {
                continue;
            }
            Path jar = moduleJar(repoDir, d);
            if (jar == null) {
                result.missing.add(id);
                MirageLog.MIRAGE.warn("模块 " + id + " 的 jar 不在模块仓库里——跳过（游戏继续启动）");
                continue;
            }
            try {
                mcl.addJar(jar.toFile());
            } catch (Throwable t) {
                MirageLog.MIRAGE.warn("模块 " + id + " 加入类加载器失败——该模块的功能不可用（游戏继续启动）", t);
                result.missing.add(id);
                continue;
            }
            result.loaded.add(id);
            String source = requested.getOrDefault(id, "(间接依赖)");
            MirageLog.MIRAGE.info("[LS] 模块已加载: " + id + " v" + d.version
                    + " (jar=" + jar.getFileName() + ", 需求来源: " + source + ")");
            invokeEntrypoint(d, mcl);
        }

        // ---- 跳过：仓库里有但无人声明 requires ----
        // 用 info 而非 debug：验收标准要求「未声明则不加载」这件事在日志里**看得见**，
        // 否则用户无法区分「模块被跳过了」和「模块根本没进仓库」。带 [LS] 前缀便于 grep。
        for (Map.Entry<String, ModuleDescriptor> e : available.entrySet()) {
            if (!needed.contains(e.getKey())) {
                result.skipped.add(e.getKey());
                MirageLog.MIRAGE.info("[LS] 模块跳过: " + e.getKey() + " v" + e.getValue().version
                        + " (无 mod 声明 requires)");
            }
        }

        if (!result.loaded.isEmpty() || !result.skipped.isEmpty() || !result.missing.isEmpty()) {
            MirageLog.MIRAGE.info("模块装载完成: 加载 " + result.loaded.size()
                    + " 个 / 跳过 " + result.skipped.size()
                    + " 个 / 缺失 " + result.missing.size() + " 个");
        }
        return result;
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    /** 解析模块仓库目录：系统属性优先，其次 {@code <gameDir>/modules/}。 */
    public static Path resolveRepoDir(Path gameDir) {
        String override = System.getProperty(DIR_PROPERTY);
        if (override != null && !override.isBlank()) {
            return Path.of(override);
        }
        if (gameDir == null) {
            return Path.of(DEFAULT_DIR_NAME);
        }
        return gameDir.resolve(DEFAULT_DIR_NAME);
    }

    /**
     * 扫描仓库目录里的 jar，解析出模块描述符表。
     *
     * @return 模块 id → 描述符；非法描述符只告警并跳过，不影响其他模块
     */
    public static Map<String, ModuleDescriptor> scanRepository(Path repoDir) {
        Map<String, ModuleDescriptor> out = new LinkedHashMap<>();
        if (repoDir == null || !Files.isDirectory(repoDir)) {
            return out;
        }
        List<Path> jars = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(repoDir, "*.jar")) {
            for (Path p : ds) {
                jars.add(p);
            }
        } catch (Exception e) {
            MirageLog.MIRAGE.debug("扫描模块仓库失败（无可选模块）: " + repoDir, e);
            return out;
        }
        jars.sort(Path::compareTo);
        for (Path jar : jars) {
            String json = readDescriptorJson(jar);
            if (json == null) {
                continue;   // 普通 jar，不是模块
            }
            ModuleDescriptor d = ModuleDescriptor.parse(json);
            if (d == null) {
                MirageLog.MIRAGE.warn("模块 jar " + jar.getFileName()
                        + " 的 " + ModuleDescriptor.RESOURCE_PATH + " 无效——该 jar 被跳过");
                continue;
            }
            if (d.usesReservedPackage()) {
                MirageLog.MIRAGE.warn("模块 " + d.id + " 的 provides 使用了保留前缀 io.mirage."
                        + "——该模块无法被加载（模块类会被委派给 app loader 而找不到）");
                continue;
            }
            if (out.containsKey(d.id)) {
                MirageLog.MIRAGE.warn("模块 id 重复: " + d.id + "（" + jar.getFileName()
                        + " 与先注册的那个冲突）——后一个被跳过");
                continue;
            }
            out.put(d.id, d);
        }
        return out;
    }

    /** 读取 jar 内的模块描述符 JSON；不是模块则返回 null。 */
    static String readDescriptorJson(Path jar) {
        try (ZipFile zf = new ZipFile(jar.toFile())) {
            ZipEntry e = zf.getEntry(ModuleDescriptor.RESOURCE_PATH);
            if (e == null) {
                return null;
            }
            try (InputStream in = zf.getInputStream(e);
                 InputStreamReader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                StringBuilder sb = new StringBuilder();
                char[] buf = new char[4096];
                int n;
                while ((n = r.read(buf)) != -1) {
                    sb.append(buf, 0, n);
                }
                return sb.toString();
            }
        } catch (Exception e) {
            MirageLog.MIRAGE.debug("读取模块描述符失败: " + jar.getFileName(), e);
            return null;
        }
    }

    /**
     * 找出描述符对应的 jar 路径。
     *
     * <p>约定：文件名以模块 id 开头（{@code mirage-config-0.1.0.jar}）。
     * 找不到就返回 null —— 此时该模块无法被加载。
     */
    static Path moduleJar(Path repoDir, ModuleDescriptor d) {
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(repoDir, "*.jar")) {
            for (Path p : ds) {
                String name = p.getFileName().toString();
                if (name.equals(d.id + ".jar") || name.startsWith(d.id + "-")) {
                    return p;
                }
            }
        } catch (Exception ignored) {
            // 目录不存在等问题交给调用方处理
        }
        return null;
    }

    /** 收集所有 mod 声明的模块需求：模块 id → 首个声明它的 mod id。 */
    static Map<String, String> collectRequests(Collection<ModContainer> mods) {
        return ModuleGraph.collectRequests(manifestsOf(mods));
    }

    /** 把 mod 列表转成 manifest 列表（跳过 null mod 与 null manifest）。 */
    private static java.util.List<ModManifest> manifestsOf(Collection<ModContainer> mods) {
        java.util.List<ModManifest> out = new ArrayList<>();
        if (mods == null) {
            return out;
        }
        for (ModContainer mod : mods) {
            if (mod != null && mod.manifest != null) {
                out.add(mod.manifest);
            }
        }
        return out;
    }

    /**
     * 递归展开依赖闭包。
     *
     * <p>已迁到 {@link ModuleGraph#collectClosure}（纯逻辑，可离线回归）；
     * 这里保留一层薄壳，避免调用方散落在各处、各自写一遍闭包算法。
     */
    static void collectClosure(Map<String, ModuleDescriptor> available, String id, Set<String> out) {
        ModuleGraph.collectClosure(available, id, out);
    }

    /**
     * 调用模块初始化入口。
     *
     * <p>约定：入口类需要有 {@code static void init()} 或实例方法 {@code void init()}；
     * 找不到任何一种 → 只打 debug（模块可能本来就不需要初始化）。
     */
    static void invokeEntrypoint(ModuleDescriptor d, MirageClassLoader mcl) {
        if (d.entrypoint == null || d.entrypoint.isBlank()) {
            return;
        }
        try {
            Class<?> cls = Class.forName(d.entrypoint, true, mcl);
            try {
                Method m = cls.getMethod("init");
                if (java.lang.reflect.Modifier.isStatic(m.getModifiers())) {
                    m.invoke(null);
                } else {
                    m.invoke(cls.getDeclaredConstructor().newInstance());
                }
                MirageLog.MIRAGE.info("模块 " + d.id + " 初始化入口已执行: " + d.entrypoint);
                return;
            } catch (NoSuchMethodException ignored) {
                // 退到 setup()
            }
            Method setup = cls.getMethod("setup");
            setup.invoke(null);
            MirageLog.MIRAGE.info("模块 " + d.id + " 初始化入口已执行: " + d.entrypoint + ".setup()");
        } catch (Throwable t) {
            // 模块初始化失败不该让整个游戏起不来——模块是可选增强
            MirageLog.MIRAGE.warn("模块 " + d.id + " 的初始化入口 " + d.entrypoint
                    + " 执行失败——该模块的功能可能不完整（游戏继续启动）", t);
        }
    }

    /** 供调试/文档使用：不可变视图。 */
    public static Map<String, ModuleDescriptor> emptyRegistry() {
        return Collections.emptyMap();
    }
}
