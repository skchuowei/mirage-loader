package io.mirage.loader.sandbox;

/**
 * fake 类的备用来源（MSR M2，2026-11-07）。
 *
 * <p><b>为什么需要</b>：MSR 原来的 fake 表面是<b>手工枚举的固定集</b>（{@code mirage/sandbox-fake/}
 * 下只有 19 个类）。实测把它喂真实 NeoForge mod 时
 * <b>100% 失败</b>：JEI 引用 92 个 {@code net/neoforged/*} 类、Jade 需要
 * {@code net/minecraft/core/HolderLookup$Provider}，全部落进「fake 表面未覆盖」的 CNFE，
 * 产出 {@code apis=0}。
 *
 * <p><b>解法</b>：不另造一套 fake 类，而是<b>复用本项目自己的 clean-room 兼容层 stub</b>
 * （{@code modules/mirage-neoforge-compat} 的 {@code net.neoforged.*} stub）。
 * 那些 stub 本来就是「有签名、能加载、方法缺失时抛 UnsupportedOperationException」的最小实现，
 * 放进沙盒当 fake 正合适：链接期补一次 stub，MSR 的 fake 覆盖面同步扩大，零重复维护。
 *
 * <p><b>接口存在的原因</b>：{@link SandboxClassLoader} 在 {@code io.mirage.loader.sandbox} 包里，
 * 而 compat stub 的字节码在 {@code modules/mirage-neoforge-compat} 模块里。
 * 让 loader 直接 import compat 会造成 core → modules 的反向依赖（AGENTS 规则 3 的分层意图）。
 * 因此这里只定义一个窄接口，由上层（{@link SandboxRecorder}）注入实现。
 *
 * <p><b>边界</b>：实现方只应<b>读取</b>字节码，不得修改 compat 源码，也不得引入
 * NeoForge 的字节码（clean-room 红线，见 AGENTS 合规边界）。
 */
@FunctionalInterface
public interface FakeProvider {

    /**
     * 找 fake 类的字节码。
     *
     * @param name 全限定类名，如 {@code net.neoforged.api.distmarker.Dist}
     * @return 字节码；没有则返回 {@code null}（调用方据此继续找下一个来源）
     */
    byte[] findFakeBytes(String name);
}
