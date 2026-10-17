package net.minecraft.core;

import net.minecraft.resources.ResourceKey;

/**
 * 编译期镜像（compile-stub）：仅让 NeoForge 兼容层 stub 的方法签名能通过编译。
 * <p><b>不打包进产出 jar</b>（build.gradle 的 jar 任务排除 net/minecraft/**）；
 * 运行时由 mcl 从真 MC 26.2 jar 加载同名类。clean-room，只取签名。
 */
public interface Registry<T> {
    ResourceKey<Registry<T>> key();
}
