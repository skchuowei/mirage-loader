package net.neoforged.fml.common;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 运行时桥接 stub：{@code @EventBusSubscriber}。
 *
 * <p>本轮只保证注解类存在、mod 类能加载（缺失的属性会被 JVM 忽略）；
 * 静态监听方法的自动扫描留待后续。
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface EventBusSubscriber {

    String value() default "";

    Bus[] bus() default {Bus.MOD, Bus.GAME};

    enum Bus {
        MOD,
        GAME
    }
}
