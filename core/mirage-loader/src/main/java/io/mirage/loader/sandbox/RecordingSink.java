package io.mirage.loader.sandbox;

/**
 * 沙盒录制汇点：fake NeoForge / MC API 表面在"被调用"时，把触达信息写进来。
 * 由 {@link SandboxRecorder} 在录制窗口内通过 {@link Sink#current} 注入一个实现。
 */
public interface RecordingSink {

    /** 记一条 API 触达（全限定类名 + 方法名，如 {@code net.neoforged.neoforge.registries.DeferredRegister.create}）。 */
    void touch(String api);

    /** 记一个被注册的事件监听器类型（全限定类名，来自 {@code @SubscribeEvent} 方法的第一个参数）。 */
    void registerEvent(String eventClass);

    /** 记一次 DeferredRegister 创建（create / createBlocks / createItems）。 */
    void deferredRegisterCreated();
}
