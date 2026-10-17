package io.mirage.api.log;

import io.mirage.api.Mirage;
import io.mirage.api.MirageProvider;

/**
 * 获取 {@link ModLogger} 的工厂入口。
 *
 * <p>用法：
 * <pre>{@code
 * private static final ModLogger LOG = ModLogManager.get("mymod");
 * LOG.info("Hello from {}", "mymod");
 * }</pre>
 */
public final class ModLogManager {

    private ModLogManager() {}

    /** 为指定 modId 取一个 logger；同一 modId 多次调用返回同一实例。 */
    public static ModLogger get(String modId) {
        return Mirage.provider().createLogger(modId);
    }
}
