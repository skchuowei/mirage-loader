package io.mirage.ls;

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
            System.out.println("[LS] stage " + stage.id() + " " + stage.name() + " start");
            long start = System.currentTimeMillis();
            StageOutcome outcome;
            try {
                outcome = stage.execute(ctx);
            } catch (Throwable t) {
                System.err.println("[LS] FATAL " + stage.id() + ": " + t);
                t.printStackTrace();
                System.exit(1);
                return; // 不可达，exit 兜底
            }
            long ms = System.currentTimeMillis() - start;
            switch (outcome.result()) {
                case SUCCESS -> System.out.println(
                        "[LS] stage " + stage.id() + " " + stage.name() + " done (" + ms + " ms)");
                case SKIPPED -> System.out.println(
                        "[LS] stage " + stage.id() + " " + stage.name() + " skipped: " + outcome.detail());
                case FATAL -> {
                    System.err.println("[LS] FATAL " + stage.id() + ": " + outcome.detail());
                    System.exit(1);
                }
            }
        }
        System.out.println("[LS] pipeline finished");
    }
}
