package net.fabricmc.api;

/**
 * Fabric ModInitializer 兼容接口。
 * Fabric Mod 实现此接口，Mirage 加载时自动调用 onInitialize()。
 */
public interface ModInitializer {
    void onInitialize();
}
