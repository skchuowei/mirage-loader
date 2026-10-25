package io.mirage.ls.stage;

import io.mirage.ls.LifecycleContext;
import io.mirage.ls.StageOutcome;

/**
 * 阶段公共支撑（phase 2 保序迁移的公共部分）。
 */
public final class StageSupport {

    private StageSupport() {}

    /**
     * 把阶段体包进「Mirage 式致命错误」外壳——与拆分前 Launcher 单体 try/catch 行为一致：
     * 打印 {@code [Mirage] 启动 Minecraft 时发生致命错误:} + 堆栈，产出 FATAL
     * （调度器统一 {@code [LS] FATAL} + exit 1，不存在跳过重试）。
     */
    public static StageOutcome guard(ThrowingStage body) {
        try {
            body.run();
            return StageOutcome.success();
        } catch (Throwable t) {
            System.err.println("[Mirage] 启动 Minecraft 时发生致命错误:");
            t.printStackTrace();
            return StageOutcome.fatal(t.toString());
        }
    }

    public interface ThrowingStage {
        void run() throws Throwable;
    }

    /** 读取跨阶段共享状态；缺失 = pipeline 接线错误，抛异常由 guard 转 FATAL。 */
    @SuppressWarnings("unchecked")
    public static <T> T get(LifecycleContext ctx, String key) {
        Object v = ctx.properties().get(key);
        if (v == null) {
            throw new IllegalStateException("[LS] pipeline 状态缺失: " + key + "（阶段接线错误）");
        }
        return (T) v;
    }
}
