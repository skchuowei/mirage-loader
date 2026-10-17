package io.mirage.api.event;

/**
 * Mod 初始化事件。
 *
 * <p>时机：在 {@link ModPreInitEvent} 之后，此时应完成绝大多数游戏对象注册。
 *
 * <p>典型用途：注册物品、方块、生物群系、网络通道等。
 */
public final class ModInitEvent extends Event {

    private final String modId;

    public ModInitEvent(String modId) {
        this.modId = modId;
    }

    public String getModId() {
        return modId;
    }
}
