package net.neoforged.fml.event.lifecycle;

import net.neoforged.bus.api.Event;
import net.neoforged.fml.config.ModConfig;

/**
 * 配置事件基类。NeoForge 在配置加载/重载时 post 其嵌套子类
 * {@link Loading} / {@link Reloading}。MG 为运行时桥接 stub。
 */
public class ModConfigEvent extends Event {

    private final ModConfig config;

    public ModConfigEvent(ModConfig config) {
        this.config = config;
    }

    /** 反射无参构造（Launcher.postNeoLifecycle 用），config 为 null。 */
    public ModConfigEvent() {
        this.config = null;
    }

    public ModConfig getConfig() {
        return config;
    }

    /** 配置加载完成事件（首次加载）。 */
    public static class Loading extends ModConfigEvent {
        public Loading(ModConfig config) { super(config); }
        public Loading() { super(); }
    }

    /** 配置重载事件（运行时 / 命令触发）。 */
    public static class Reloading extends ModConfigEvent {
        public Reloading(ModConfig config) { super(config); }
        public Reloading() { super(); }
    }
}
