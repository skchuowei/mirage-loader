package io.mirage.ls.stage;

import io.mirage.api.log.MirageLog;
import io.mirage.loader.ModContainer;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * lifecycle-starter（app loader）→ NeoForge 链接期静态态（MCL）的反射桥。
 *
 * <p><b>为什么不直接 import</b>：{@code net.neoforged.*} / {@code io.mirage.neoforge.*}
 * 由 {@code MirageClassLoader} 加载；LS 由 app loader 加载。直接引用会让
 * {@code FMLPaths}/{@code ModList}/{@code ModContainer} 出现两份静态态，mod 侧读不到
 * 加载器写入的值（同 {@code MirageClassLoader} 第 39–58 行的例外规则）。
 *
 * <p>本类只做三件事：初始化（路径/端侧/ModList）、取端侧枚举、按 modId 取容器。
 * 任何失败都交给调用方降级（构造器选择会跳过需要这些参数的签名）。
 */
public final class NeoCompatBridge {

    private static Class<?> bootstrap;
    private static java.lang.reflect.Method containerMethod;
    private static java.lang.reflect.Method distMethod;
    private static java.lang.reflect.Method activeModMethod;

    private NeoCompatBridge() {
    }

    /**
     * 在 mod 类加载之前初始化 NeoForge 静态态。
     *
     * @param mcl      MirageClassLoader
     * @param gameDir  游戏目录
     * @param distName {@code CLIENT} / {@code DEDICATED_SERVER}
     * @param mods     已发现的 mod（S1 结果）
     */
    public static void init(ClassLoader mcl, Path gameDir, String distName, List<ModContainer> mods)
            throws Exception {
        Class<?> bs = Class.forName("io.mirage.neoforge.runtime.NeoCompatBootstrap", true, mcl);
        bs.getMethod("init", Path.class, String.class).invoke(null, gameDir, distName);

        Object bus = modBus(mcl);
        List<String[]> rows = new ArrayList<>();
        for (ModContainer mod : mods) {
            rows.add(new String[]{
                    mod.manifest.id,
                    mod.manifest.version,
                    mod.manifest.name == null ? mod.manifest.id : mod.manifest.name,
                    mod.jarFile == null ? null : mod.jarFile.toString()
            });
        }
        bs.getMethod("registerMods", Object.class, List.class).invoke(null, bus, rows);

        bootstrap = bs;
        containerMethod = bs.getMethod("container", String.class);
        distMethod = bs.getMethod("dist");
        activeModMethod = bs.getMethod("setActiveMod", String.class);
    }

    /** 取 mod bus（经由 MCL 的 {@code FMLJavaModLoadingContext}）。 */
    public static Object modBus(ClassLoader mcl) throws Exception {
        Object ctx = Class.forName("net.neoforged.fml.javafmlmod.FMLJavaModLoadingContext", true, mcl)
                .getMethod("get").invoke(null);
        return ctx.getClass().getMethod("getModEventBus").invoke(ctx);
    }

    /** 当前端侧枚举；未初始化返回 null（构造选择器会自动跳过依赖它的签名）。 */
    public static Object dist(ClassLoader mcl) {
        if (distMethod == null || bootstrap == null || bootstrap.getClassLoader() != mcl) {
            return null;
        }
        try {
            return distMethod.invoke(null);
        } catch (Throwable t) {
            MirageLog.MIRAGE.warn("读取 NeoForge Dist 失败（构造注入降级为无参）", t);
            return null;
        }
    }

    /** 标记当前活跃 modId（写 MCL 那侧的 ModLoadingContext 静态态）。 */
    public static void setActiveMod(ClassLoader mcl, String modId) {
        if (activeModMethod == null || bootstrap == null || bootstrap.getClassLoader() != mcl) {
            return;
        }
        try {
            activeModMethod.invoke(null, modId);
        } catch (Throwable t) {
            MirageLog.MIRAGE.warn("标记活跃 mod 失败: " + modId + "（配置归属可能退化）", t);
        }
    }

    /** 按 modId 取容器；未登记返回 null。 */
    public static Object container(ClassLoader mcl, String modId) {
        if (containerMethod == null || bootstrap == null || bootstrap.getClassLoader() != mcl) {
            return null;
        }
        try {
            return containerMethod.invoke(null, modId);
        } catch (Throwable t) {
            MirageLog.MIRAGE.warn("读取 mod 容器失败: " + modId + "（构造注入降级为无参）", t);
            return null;
        }
    }
}
