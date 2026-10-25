package net.neoforged.fml.config;

/**
 * 类型化配置数据访问（NeoForge 兼容 stub）。
 *
 * <p>底层为 TOML 解析出的嵌套 Map；{@code getXxx(path)} 支持点分路径
 * （如 {@code "general.max_count"}）。
 */
public interface ConfigData {

    boolean contains(String path);

    String getString(String path);

    int getInt(String path);

    long getLong(String path);

    double getDouble(String path);

    boolean getBoolean(String path);

    /** 原始 Object（不存在时返回 null）。 */
    Object get(String path);
}
