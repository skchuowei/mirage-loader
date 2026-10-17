package io.mirage.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标注一个类作为 Mod 的入口点。
 *
 * <p>与 {@link Mod} 的关系：
 * <ul>
 *   <li>{@link Mod} 标注的是"主类"，一个 Mod 一个；</li>
 *   <li>{@link Entrypoint} 标注的是"附加入口"，一个 jar 可以有多个，
 *       按 {@link #value()} 区分阶段。</li>
 * </ul>
 *
 * <p>示例：
 * <pre>{@code
 * @Entrypoint(Entrypoint.CLIENT)
 * public class MyClientInit implements ClientModInitializer { ... }
 *
 * @Entrypoint(Entrypoint.SERVER)
 * public class MyServerInit { ... }
 * }</pre>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Entrypoint {

    /** 主端（双端都会跑）。 */
    String MAIN   = "main";
    /** 仅物理客户端。 */
    String CLIENT = "client";
    /** 仅服务端。 */
    String SERVER = "server";

    /** 入口阶段，取值见本类常量。 */
    String value();
}
