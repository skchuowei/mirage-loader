package io.mirage.neoforge.runtime;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.ModLoadingContext;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.fml.loading.LoadingModList;
import net.neoforged.fml.loading.moddiscovery.ModInfo;

import java.nio.file.Path;
import java.util.List;

/**
 * NeoForge 链接期静态态的初始化入口（clean-room）。
 *
 * <p><b>为什么单独有这个类</b>：{@code net.neoforged.*} 与 {@code io.mirage.neoforge.*}
 * 在本项目里由 {@code MirageClassLoader}（MCL）加载，而 lifecycle-starter 由 app loader 加载
 * （见 {@code MirageClassLoader} 第 78–84 行的委派规则）。如果 LS 直接 import 这些类，
 * 会得到 app loader 的另一份静态态——{@code FMLPaths}/{@code ModList}/{@code ModLoadingContext}
 * 各两份，mod 侧读到的永远是未初始化的那份。因此 LS 只通过反射调用本类（见
 * {@code io.mirage.ls.stage.NeoCompatBridge}），保证静态态只有 MCL 这一份。
 */
public final class NeoCompatBootstrap {

    private NeoCompatBootstrap() {
    }

    /**
     * 在任何 mod 类被加载/构造之前调用：注入路径与端侧、清空上一轮列表。
     *
     * @param gameDir  游戏目录（S1 已解析，不能依赖 CWD）
     * @param distName {@code CLIENT} / {@code DEDICATED_SERVER}
     */
    public static void init(Path gameDir, String distName) {
        FMLPaths.init(gameDir);
        FMLEnvironment.init("DEDICATED_SERVER".equalsIgnoreCase(distName)
                ? Dist.DEDICATED_SERVER : Dist.CLIENT);
        // 配置目录也必须写进 MCL 这一份 ModLoadingContext；LS 直接写会写到 app loader 的另一份。
        ModLoadingContext.setConfigDirectory(FMLPaths.CONFIGDIR.get());
        ModList.clear();
        LoadingModList.clear();
    }

    /**
     * 把加载器已发现的 mod 登记进 {@code ModList} / {@code LoadingModList}，
     * 供 {@code (IEventBus, ModContainer)} 构造与 {@code ModList.get()} 查询使用。
     *
     * @param bus  真实 mod bus（由 LS 通过反射从 {@code FMLJavaModLoadingContext} 取到）
     * @param rows 每行 {@code [modId, version, displayName, jarPath]}
     */
    public static void registerMods(Object bus, List<String[]> rows) {
        IEventBus eventBus = (IEventBus) bus;
        if (rows == null) {
            return;
        }
        for (String[] row : rows) {
            if (row == null || row.length < 1 || row[0] == null || row[0].isEmpty()) {
                continue;
            }
            String modId = row[0];
            String version = row.length > 1 && row[1] != null ? row[1] : "";
            String displayName = row.length > 2 && row[2] != null ? row[2] : modId;
            Path jar = row.length > 3 && row[3] != null && !row[3].isEmpty()
                    ? Path.of(row[3]) : null;
            ModList.register(new ModContainer(new NeoModInfo(modId, version, displayName, jar), eventBus));
            LoadingModList.add(new ModInfo(modId, version, displayName));
        }
    }

    /** 取某个 mod 的容器；未登记返回 {@code null}（构造选择器会跳过需要容器的签名）。 */
    public static ModContainer container(String modId) {
        return ModList.get().getModContainerById(modId).orElse(null);
    }

    /** 当前端侧，供构造选择器传 {@code Dist} 参数。 */
    public static Dist dist() {
        return FMLEnvironment.getDist();
    }

    /**
     * 标记当前正在构造的 modId，供 {@code ModLoadingContext.registerConfig} 推断归属。
     * 必须走 MCL 这一份静态态（LS 侧写 app 副本对 mod 不可见）。
     */
    public static void setActiveMod(String modId) {
        ModLoadingContext.setActiveMod(modId);
    }
}
