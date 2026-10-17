package io.mirage.api.event;

/**
 * Mod 预初始化事件。
 *
 * <p>时机：所有 Mod 主类已实例化、依赖拓扑完成，
 * 但尚未注册任何游戏对象（物品 / 方块 / 实体等）。
 *
 * <p>典型用途：读取配置、准备数据结构、注册 {@code DeferredRegister}。
 */
public final class ModPreInitEvent extends Event {

    private final String modId;

    public ModPreInitEvent(String modId) {
        this.modId = modId;
    }

    public String getModId() {
        return modId;
    }
}
