package net.neoforged.bus.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 运行时桥接 stub：NeoForge {@code @SubscribeEvent} 注解。
 *
 * <p>带此注解的方法由 {@link io.mirage.neoforge.runtime.NeoEventBusImpl} 在
 * {@code bus.register(obj)} 时扫描入桶；与 MG 自家的
 * {@code io.mirage.api.SubscribeEvent} 是两套独立注解，互不影响。
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface SubscribeEvent {
    /** 事件优先级（第一版忽略，对齐默认值 0）。 */
    int priority() default 0;

    /** 是否接收已取消事件（第一版忽略）。 */
    boolean receiveCancelled() default false;
}
