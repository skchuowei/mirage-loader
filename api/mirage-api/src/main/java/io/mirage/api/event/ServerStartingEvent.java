package io.mirage.api.event;

/**
 * 服务端启动事件。
 *
 * <p>时机：Dedicated Server 正在加载世界、注册命令，
 * 但尚未开始接受玩家连接。
 *
 * <p>注意：集成服务端（单人游戏内置的服务端）也会触发本事件。
 */
public final class ServerStartingEvent extends Event {
}
