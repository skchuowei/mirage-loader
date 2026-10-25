package net.neoforged.neoforge.network;

/**
 * Mirage NeoForge 兼容层 —— 网络方向枚举（旧 API NetworkEvent.Context 语义）。
 */
public enum NetworkDirection {
    /** 客户端 -> 服务端。 */
    PLAY_TO_SERVER,
    /** 服务端 -> 客户端。 */
    PLAY_TO_CLIENT
}
