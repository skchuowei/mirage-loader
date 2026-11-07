package net.neoforged.fml;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 运行时桥接 stub：已加载 mod 列表（clean-room）。
 *
 * <p>真实签名：{@code ModList.get().getModContainerById(id) -> Optional<ModContainer>}、
 * {@code isLoaded(id)}、{@code getMods()}。静态 {@code register/clear} 只给加载器用，
 * 保证 mod 侧看到的是同一份表（由 MCL 加载，见 {@code NeoCompatBootstrap}）。
 */
public final class ModList {

    private static final ModList INSTANCE = new ModList();
    private static final Map<String, ModContainer> BY_ID = new LinkedHashMap<>();

    private ModList() {
    }

    public static ModList get() {
        return INSTANCE;
    }

    /** 加载器在 S6 之前清空上一轮（同进程重载防护）。 */
    public static void clear() {
        BY_ID.clear();
    }

    public static void register(ModContainer container) {
        if (container != null && container.getModId() != null) {
            BY_ID.put(container.getModId(), container);
        }
    }

    public Optional<ModContainer> getModContainerById(String modId) {
        if (modId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(BY_ID.get(modId));
    }

    public boolean isLoaded(String modId) {
        return modId != null && BY_ID.containsKey(modId);
    }

    public List<ModContainer> getMods() {
        return List.copyOf(BY_ID.values());
    }

    public int size() {
        return BY_ID.size();
    }
}
