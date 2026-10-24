package net.neoforged.bus.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 沙盒 fake：NeoForge {@code @SubscribeEvent} 注解占位（RUNTIME 保留）。
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface SubscribeEvent {
    int priority() default 0;

    boolean receiveCancelled() default false;
}
