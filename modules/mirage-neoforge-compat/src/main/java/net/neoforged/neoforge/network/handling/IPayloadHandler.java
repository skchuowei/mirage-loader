package net.neoforged.neoforge.network.handling;

import net.neoforged.neoforge.network.NetworkEvent;

/**
 * Mirage NeoForge 兼容层 —— 新 API payload 处理器（函数式）。
 *
 * <p>对应真实 NeoForge 的
 * {@code net.neoforged.neoforge.network.handling.IPayloadHandler}。</p>
 */
@FunctionalInterface
public interface IPayloadHandler<T> {
    void handle(T payload, NetworkEvent.Context context);
}
