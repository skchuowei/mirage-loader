package io.mirage.api.env;

/**
 * 物理分发端标识。
 *
 * <p>用于在同一份 Mod 代码里区分"我现在跑在客户端还是独立服务端"。
 * 单人游戏内置的集成服务端也属于 {@link #DEDICATED_SERVER} 一侧的逻辑，
 * 但其物理进程仍是客户端进程，此时 {@link Environment#getDist()} 返回
 * {@link #CLIENT}。
 */
public enum Dist {
    /** 物理客户端进程（含单人游戏内置的集成服务端）。 */
    CLIENT,

    /** 独立 Dedicated Server 进程。 */
    DEDICATED_SERVER
}
