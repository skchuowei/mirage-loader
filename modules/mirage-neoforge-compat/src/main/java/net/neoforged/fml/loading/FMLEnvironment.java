package net.neoforged.fml.loading;

import net.neoforged.api.distmarker.Dist;

/**
 * 运行时桥接 stub：NeoForge 运行环境（clean-room）。
 *
 * <p>真实 mod 会调 {@code FMLEnvironment.getDist()} 决定是否注册客户端逻辑
 * （Cloth Config 的静态初始化就这么做）。值由加载器注入，不写死。
 */
public final class FMLEnvironment {

    private static volatile Dist dist = Dist.CLIENT;
    private static volatile boolean production = false;

    private FMLEnvironment() {
    }

    public static void init(Dist d) {
        if (d != null) {
            dist = d;
        }
    }

    public static Dist getDist() {
        return dist;
    }

    public static boolean isProduction() {
        return production;
    }

    public static String getJavaVersion() {
        return System.getProperty("java.version");
    }
}
