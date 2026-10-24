package io.mirage.loader.sandbox;

/**
 * fake API 表面与 {@link SandboxRecorder} 之间的静态桥。
 *
 * <p>fake 类（{@code net.neoforged.*} / {@code net.minecraft.*} 占位）由
 * {@link SandboxClassLoader} 用 {@code defineClass} 定义，它们的字节码里直接引用本类；
 * 本类始终由 app classloader（fat jar）加载，身份唯一，避免跨 loader 状态撕裂。
 *
 * <p>录制窗口外（{@code current == null}）所有方法都是 no-op，保证 fake 表面被
 * 误调用时也不会抛 NPE。
 */
public final class Sink {

    /** 当前录制汇点；录制窗口外为 null。 */
    public static volatile RecordingSink current;

    public static void touch(String api) {
        RecordingSink s = current;
        if (s != null) s.touch(api);
    }

    public static void registerEvent(String eventClass) {
        RecordingSink s = current;
        if (s != null) s.registerEvent(eventClass);
    }

    public static void deferredRegisterCreated() {
        RecordingSink s = current;
        if (s != null) s.deferredRegisterCreated();
    }

    private Sink() {}
}
