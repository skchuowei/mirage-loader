package net.neoforged.bus.api;

/**
 * 沙盒 fake：NeoForge 事件顶层基类占位。
 */
public abstract class Event {
    public boolean isCancelable() {
        return false;
    }
}
