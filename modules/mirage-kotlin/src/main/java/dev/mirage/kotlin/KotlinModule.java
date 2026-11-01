package dev.mirage.kotlin;

import io.mirage.api.log.MirageLog;

/**
 * Kotlin 支持模块的初始化入口（G9 模块懒加载的示例实现）。
 *
 * <p><b>当前是桩</b>：本模块只证明「可选模块可以被按需加载并执行入口」这条链路是通的，
 * 并没有真的把 kotlin-stdlib 带进来（那会让模块体积膨胀到几 MB，与「按需加载省资源」
 * 的初衷相反 —— 将来应当把 kotlin-stdlib 做成**独立的可选 jar**，由本模块在
 * {@code init()} 时再加进类加载器）。
 *
 * <p>包名约定见 {@code dev.mirage.config.ConfigModule} 的类注释：
 * 模块提供的 API <b>不得</b>放在 {@code io.mirage.} 前缀下。
 *
 * <p>入口约定：{@code ModuleRegistry} 依次尝试
 * {@code static init()} → {@code instance init()} → {@code static setup()}。
 */
public final class KotlinModule {

    private KotlinModule() {
    }

    /** 模块被加载时由 {@code ModuleRegistry} 反射调用。 */
    public static void init() {
        MirageLog.MIRAGE.info("Kotlin 支持模块已就绪 (dev.mirage.kotlin, 当前为桩实现)");
    }
}
