package net.neoforged.fml.loading;

import net.neoforged.fml.loading.moddiscovery.ModInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * 运行时桥接 stub：加载期 mod 列表（clean-room）。
 *
 * <p>{@code FMLLoader.getCurrent().getLoadingModList().getMods()} 的真实调用链
 * 见于 FerriteCore。列表由 {@code NeoCompatBootstrap.registerMods} 填充。
 */
public final class LoadingModList {

    private static final LoadingModList INSTANCE = new LoadingModList();
    private static final List<ModInfo> MODS = new ArrayList<>();

    private LoadingModList() {
    }

    public static LoadingModList get() {
        return INSTANCE;
    }

    public static void clear() {
        MODS.clear();
    }

    public static void add(ModInfo info) {
        if (info != null) {
            MODS.add(info);
        }
    }

    public List<ModInfo> getMods() {
        return List.copyOf(MODS);
    }
}
