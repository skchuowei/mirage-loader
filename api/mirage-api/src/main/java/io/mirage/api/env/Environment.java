package io.mirage.api.env;

import io.mirage.api.Mirage;
import io.mirage.api.MirageProvider;

/**
 * 运行环境工具。
 *
 * <p>所有方法都是静态门面，转发到当前 {@link MirageProvider}。
 * 在单元测试或尚未启动 Mirage 的环境下调用，会抛出
 * {@link IllegalStateException}，提示运行时未初始化。
 */
public final class Environment {

    private Environment() {}

    /** 当前物理端。 */
    public static Dist getDist() {
        return Mirage.provider().getDist();
    }

    /** 是否跑在物理客户端进程。 */
    public static boolean isClient() {
        return getDist() == Dist.CLIENT;
    }

    /** 是否跑在独立 Dedicated Server 进程。 */
    public static boolean isDedicatedServer() {
        return getDist() == Dist.DEDICATED_SERVER;
    }
}
