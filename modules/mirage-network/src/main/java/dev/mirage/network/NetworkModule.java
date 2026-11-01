package dev.mirage.network;

import io.mirage.api.log.MirageLog;

/**
 * 网络层模块的初始化入口（G9 模块懒加载的示例实现）。
 *
 * <p><b>当前是桩</b>：本模块只证明「可选模块可以被按需加载并执行入口」这条链路是通的，
 * 真正的网络能力（跨维度同步、服务端广播等）尚未实现。
 *
 * <p>包名约定见 {@code dev.mirage.config.ConfigModule} 的类注释：
 * 模块提供的 API <b>不得</b>放在 {@code io.mirage.} 前缀下。
 *
 * <p>入口约定：{@code ModuleRegistry} 依次尝试
 * {@code static init()} → {@code instance init()} → {@code static setup()}。
 */
public final class NetworkModule {

    private NetworkModule() {
    }

    /** 模块被加载时由 {@code ModuleRegistry} 反射调用。 */
    public static void init() {
        MirageLog.MIRAGE.info("网络模块已就绪 (dev.mirage.network, 当前为桩实现)");
    }
}
