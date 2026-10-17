package io.mirage.api.event;

/**
 * Mod 后初始化事件。
 *
 * <p>时机：所有 Mod 完成 {@link ModInitEvent} 之后。
 *
 * <p>典型用途：跨 Mod 交互（读取其他 Mod 注册的内容）、
 * 做最终校验、启动后台线程。
 */
public final class ModPostInitEvent extends Event {

    private final String modId;

    public ModPostInitEvent(String modId) {
        this.modId = modId;
    }

    public String getModId() {
        return modId;
    }
}
