package io.mirage.api.event;

/**
 * 客户端启动事件。
 *
 * <p>时机：客户端类已加载、资源包即将刷新，
 * 但玩家尚未进入任何世界。仅在物理客户端触发。
 *
 * <p>典型用途：注册自定义 Screen、HUD 元素、按键绑定。
 */
public final class ClientStartingEvent extends Event {
}
