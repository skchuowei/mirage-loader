package io.mirage.api.mod;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Mod 元数据的公共只读视图。
 *
 * <p>Mod 开发者通过 {@link io.mirage.api.Mirage#getMod(String)} 拿到的就是它。
 * 实现类由加载器内部提供，Mod 不应也不能继承本接口。
 */
public interface ModMetadata {

    /** Mod 唯一 id，正则 [a-z][a-z0-9-_]{1,63}。 */
    String id();

    /** 语义化版本，如 "1.0.0"。 */
    String version();

    /** 展示名；未配置时等于 {@link #id()}。 */
    String displayName();

    /** 主类全限定名；可能为空（纯资源包 Mod）。 */
    Optional<String> mainClass();

    /** 依赖表：modId -> 版本范围字符串。 */
    Map<String, String> dependencies();

    /** 期望运行侧："client" / "server" / "both"。 */
    String side();

    /** 该 Mod 的 jar 文件路径。 */
    Path jarPath();

    /** 作者列表（来自描述符 authors 字段，可能为空）。 */
    List<String> authors();

    /** 描述（来自 description 字段，可能为空）。 */
    Optional<String> description();

    /** 主页 / 源码链接（来源 url 字段，可能为空）。 */
    Optional<String> url();
}
