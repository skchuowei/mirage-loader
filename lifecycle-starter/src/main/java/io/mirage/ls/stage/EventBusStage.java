package io.mirage.ls.stage;

import io.mirage.ls.LifecycleContext;
import io.mirage.ls.Stage;
import io.mirage.ls.StageOutcome;

/**
 * S5 事件总线初始化（LS-Interfaces.md §7；语义见 MG-Lifecycle.md S5）。
 *
 * <p><b>phase 2 保序决定（重要）</b>：MG-Lifecycle 把「EventBus 创建 + Mirage.bootstrap
 * 门面」定位在 S5，但拆分前 Launcher 的真实时序在 S1 与 S3 <em>之间</em>（mods 发现后、
 * 类加载器创建前）。为保冒烟等价（mod 可观察到的 API 就绪时机不变），phase 2 将
 * EventBus 创建与门面装配落入 {@link ParseStage S2}，本阶段不退回到「再建一份总线」——
 * 那会造出两个 EventBus 实例、事件双投或漏投。
 *
 * <p>因此 S5 的 phase 2 实际职责 = <b>不变量检查点</b>：校验 S1/S2 已把就绪的总线与
 * mods 列表放进 ctx，缺失即 FATAL（pipeline 接线错误，禁止带着空总线进 S6）。
 * mod 实例的 register/subscribe 仍按加载图顺序串行发生在 S6 构造循环内
 * （拆分前同为循环内联，拆开会改变跨 mod 的事件次序）。
 */
public final class EventBusStage implements Stage {

    @Override
    public String id() {
        return "S5";
    }

    @Override
    public String name() {
        return "EventBusInit";
    }

    @Override
    public StageOutcome execute(LifecycleContext ctx) {
        Object bus = ctx.properties().get("S2.eventBus");
        Object mods = ctx.properties().get("S1.mods");
        Object mcl = ctx.properties().get("S3.classLoader");
        if (bus == null || mods == null || mcl == null) {
            return StageOutcome.fatal("S5 事件总线检查点失败：pipeline 状态缺失"
                    + "（eventBus=" + (bus != null) + ", mods=" + (mods != null)
                    + ", classLoader=" + (mcl != null) + "）——阶段接线错误");
        }
        return StageOutcome.success();
    }
}
