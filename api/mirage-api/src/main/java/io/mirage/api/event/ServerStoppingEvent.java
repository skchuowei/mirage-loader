package io.mirage.api.event;

/**
 * 服务端关闭事件。
 *
 * <p>时机：服务端即将保存世界并退出。
 * 监听器应在此处flush 自己的数据，禁止阻塞超过数秒。
 */
public final class ServerStoppingEvent extends Event {
}
