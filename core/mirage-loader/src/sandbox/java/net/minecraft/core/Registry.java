package net.minecraft.core;

import net.minecraft.resources.ResourceKey;

/**
 * 沙盒 fake：MC {@code Registry} 接口最小占位。
 * 只提供 {@link #key()}，供 {@code DeferredRegister.create(Registry, String)} 签名链接。
 */
public interface Registry<T> {
    ResourceKey<? extends Registry<T>> key();
}
