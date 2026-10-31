package io.mirage.ls;

import io.mirage.api.log.MirageLog;

import java.util.ArrayList;
import java.util.List;

/**
 * 生命周期调度器（LS-Interfaces.md §5，2026-10-24 冻结）。
 *
 * <p>调度契约：
 * <ol>
 *   <li>严格串行、按注册顺序；一次 run 内 pipeline 不可变；</li>
 *   <li>每阶段打统一日志 [LS] stage &lt;id&gt; &lt;name&gt; start / done；</li>
 *   <li>FATAL（含阶段抛出的未预期异常）→ 打印 + exit 1，不静默、不重试、不跳过；</li>
 *   <li>阶段永不返回（移交 MC 主循环）视为正常完成路径；</li>
 *   <li>调度器自身不实现任何 mod 逻辑。</li>
 * </ol>
 */
public final class LifecycleScheduler {

    private final List<Stage> stages = new ArrayList<>();
    private boolean running = false;

    /** 仅允许启动装配期调用；run 开始后禁止增删阶段。 */
    public synchronized void add(Stage stage) {
        if (running) {
            throw new IllegalStateException("[LS] 调度器已启动，禁止追加阶段: " + stage.id());
        }
        stages.add(stage);
    }

    public synchronized void run(LifecycleContext ctx) {
        running = true;
        for (Stage stage : stages) {
            // 阶段骨架行保持原样（raw）：LS-Interfaces §5 冻结了 "[LS] stage <id> <name> start/done"
            // 这一格式，历史日志与冒烟脚本按它做管道对比，加级别词会破坏可比性。
            MirageLog.LS.raw("[LS] stage " + stage.id() + " " + stage.name() + " start");
            long start = System.currentTimeMillis();
            StageOutcome outcome;
            try {
                outcome = stage.execute(ctx);
            } catch (Throwable t) {
                // 未预期异常：门面负责展开完整 cause 链（改造前 "+ t" 只有类名，
                // 对 InvocationTargetException / ExceptionInInitializerError 等于没信息）
                MirageLog.LS.fatal(stage.id() + " 阶段抛出未预期异常，启动中止", t);
                System.exit(1);
                return; // 不可达，exit 兜底
            }
            long ms = System.currentTimeMillis() - start;
            switch (outcome.result()) {
                case SUCCESS -> MirageLog.LS.raw(
                        "[LS] stage " + stage.id() + " " + stage.name() + " done (" + ms + " ms)");
                case SKIPPED -> MirageLog.LS.raw(
                        "[LS] stage " + stage.id() + " " + stage.name() + " skipped: " + outcome.detail());
                case FATAL -> {
                    MirageLog.LS.fatal(stage.id() + ": " + outcome.detail());
                    System.exit(1);
                }
            }
        }
        MirageLog.LS.raw("[LS] pipeline finished");
    }
}
