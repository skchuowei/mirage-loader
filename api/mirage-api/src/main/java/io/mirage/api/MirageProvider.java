package io.mirage.api;

import io.mirage.api.bus.EventBusHandle;
import io.mirage.api.env.Dist;
import io.mirage.api.log.ModLogger;
import io.mirage.api.mod.ModMetadata;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Mirage 运行时 SPI。
 *
 * <p>这是<strong>加载器内部</strong>实现的接口，Mod 开发者不应直接实现或调用。
 * 加载器在启动早期通过 {@link java.util.ServiceLoader} 注册唯一实现；
 * Mod 代码请走 {@link Mirage} 静态门面。
 *
 * <p>把它放在 API 包而不是 core 包，是为了让 {@link Mirage} 门面能引用到类型，
 * 同时 core 模块只需 {@code implements} 本接口即可完成对接。
 */
public interface MirageProvider {

    /** 当前物理端。 */
    Dist getDist();

    /** 已加载的全部 Mod（按依赖拓扑顺序）。 */
    List<ModMetadata> getMods();

    /** 按 id 查 Mod；未加载时返回 empty。 */
    Optional<ModMetadata> getMod(String modId);

    /** 游戏根目录（.minecraft / 服务端目录）。 */
    Path getGameDir();

    /** mods 目录，通常为 {@code gameDir/mods}。 */
    Path getModsDir();

    /** 全局事件总线。 */
    EventBusHandle getEventBus();

    /** 为指定 modId 创建日志门面。 */
    ModLogger createLogger(String modId);

    /** Mirage Loader 自身版本，如 "0.4.0"。 */
    String getLoaderVersion();
}
