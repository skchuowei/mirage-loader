package io.mirage.api.event;

/**
 * Mod 构造完成事件：Mod 主类已实例化，准备注册内容。
 */
public class ModConstructEvent {
    public final String modId;
    public ModConstructEvent(String modId) { this.modId = modId; }
}
