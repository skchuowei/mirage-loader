package net.neoforged.fml.common;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 沙盒 fake：NeoForge {@code @Mod} 注解占位（RUNTIME 保留）。
 * 只让 mod 主类字节码里的注解元数据可被 {@code defineClass} 解析，不做任何行为。
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Mod {
    String value();
}
