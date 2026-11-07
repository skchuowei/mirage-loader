package net.neoforged.neoforgespi.language;

import java.util.Optional;

/**
 * 运行时桥接 stub：可读配置元素的对象（clean-room）。
 *
 * <p>真实签名是 {@code getConfigElement(String... keys)}；编译后描述符为
 * {@code ([Ljava/lang/String;)Ljava/util/Optional;}，与 mod 的调用一致。
 * 当前实现一律返回空，不去猜 mod 自定义 TOML 段的语义。
 */
public interface IConfigurable {

    Optional<Object> getConfigElement(String... keys);
}
