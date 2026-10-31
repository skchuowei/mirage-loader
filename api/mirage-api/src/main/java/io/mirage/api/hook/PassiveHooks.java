package io.mirage.api.hook;

import io.mirage.api.log.MirageLog;

import java.util.function.Consumer;

/**
 * 被动捕获钩子（MSR P2）。
 *
 * <p>MSR 的"录制"要从"额外跑一遍构造函数（fake pass）"改成"真实加载的副产品"，
 * 就必须在真实加载路径上留观测点。这些观测点分散在 api / eventbus / compat 三个模块里，
 * 而收集器在 loader 模块 —— 依赖方向是 api ← eventbus/compat/loader，不能反向 import。
 *
 * <p>所以本类只做一件事：三个 {@code volatile} 汇点。loader 侧
 * （{@code io.mirage.loader.passive.PassiveRecorder}）在第一次开窗口时装上实现；
 * 没装（或窗口外）时全部 no-op，对正常启动零影响。
 *
 * <p><b>为什么不放在 {@code Sink}</b>：{@code Sink} 是沙盒 fake 表面专用的（fake 字节码直接引用它，
 * 且由沙盒线程独占），被动捕获与它生命周期、线程模型都不同，混用会把两条链路绑死。
 *
 * <p><b>钩子绝不能抛</b>：它们跑在 mod 初始化路径上，抛出去等于把 mod 加载搞挂。
 * 因此每个入口都吞掉汇点抛出的 {@link Throwable}，并且只在第一次失败时记一行 debug
 * （逐条记会刷屏，彻底静默又违背"不静默"的规矩）。
 */
public final class PassiveHooks {

    /** API 触达汇点（{@code 类名.方法名}）。null = 未安装。 */
    public static volatile Consumer<String> apiSink;

    /** 事件监听注册汇点（事件类全限定名）。null = 未安装。 */
    public static volatile Consumer<String> eventSink;

    /** DeferredRegister 创建汇点。null = 未安装。 */
    public static volatile Runnable deferredRegisterSink;

    /** 汇点抛错只报一次，避免刷屏。 */
    private static volatile boolean reported;

    public static void api(String api) {
        Consumer<String> s = apiSink;
        if (s == null || api == null) return;
        try {
            s.accept(api);
        } catch (Throwable t) {
            reportOnce("api", t);
        }
    }

    public static void event(String eventClass) {
        Consumer<String> s = eventSink;
        if (s == null || eventClass == null || eventClass.isEmpty()) return;
        try {
            s.accept(eventClass);
        } catch (Throwable t) {
            reportOnce("event", t);
        }
    }

    public static void deferredRegister() {
        Runnable s = deferredRegisterSink;
        if (s == null) return;
        try {
            s.run();
        } catch (Throwable t) {
            reportOnce("deferredRegister", t);
        }
    }

    /** 卸载全部汇点（窗口关闭时用；也供测试断言"窗口外是 no-op"）。 */
    public static void uninstall() {
        apiSink = null;
        eventSink = null;
        deferredRegisterSink = null;
    }

    private static void reportOnce(String kind, Throwable t) {
        if (reported) return;
        reported = true;
        MirageLog.of("Mirage:Hook").debug("被动捕获汇点(" + kind + ")抛错，本进程内后续错误不再重复报告", t);
    }

    private PassiveHooks() {}
}
