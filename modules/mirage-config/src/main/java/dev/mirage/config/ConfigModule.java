package dev.mirage.config;

import io.mirage.api.log.MirageLog;

/**
 * 配置模块的初始化入口（G9 模块懒加载的示例实现）。
 *
 * <p><b>为什么这个类放在 {@code dev.mirage.config} 而不是 {@code io.mirage.config}</b>：
 * {@code MirageClassLoader.loadClass} 把整个 {@code io.mirage.} 前缀无条件委派给
 * {@code SystemClassLoader}（见该类第 54 行），而模块 jar 只加在 {@code MirageClassLoader}
 * 上、不在 app loader 的 classpath 里 —— 若模块 API 落在 {@code io.mirage.} 下，
 * 委派之后必然 {@code NoClassDefFoundError}。
 *
 * <p>模块**想用** Mirage 自有能力时没有这个问题：那些类在 {@code io.mirage.api.*} 下、
 * 随 fat jar 走 app loader，模块由 mcl 加载后引用它们时委派给 app loader，
 * 拿到的是同一份。
 *
 * <p><b>入口约定</b>：{@code ModuleRegistry} 依次尝试
 * {@code static init()} → {@code instance init()} → {@code static setup()}。
 * 这里用静态 {@code init()}。
 */
public final class ConfigModule {

    private ConfigModule() {
    }

    /** 模块被加载时由 {@code ModuleRegistry} 反射调用。 */
    public static void init() {
        MirageLog.MIRAGE.info("配置模块已就绪 (dev.mirage.config)");
    }
}
