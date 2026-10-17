package net.neoforged.fml.common;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 运行时桥接 stub：NeoForge {@code @Mod} 注解。
 *
 * <p>真实 NeoForge 中它标记 mod 主类并携带 modId。MG 的 {@code NeoForgeModDetector}
 * 在静态扫描阶段已用同一描述符 {@code Lnet/neoforged/fml/common/Mod;} 识别主类；
 * 运行时本类必须存在，否则 mod 主类被链接时会抛 {@code NoClassDefFoundError}。
 *
 * <p>clean-room：仅对齐签名与 RUNTIME 保留策略，不抄 NeoForge 实现。
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Mod {
    String value();
}
