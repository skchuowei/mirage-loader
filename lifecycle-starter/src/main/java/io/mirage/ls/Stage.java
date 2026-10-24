package io.mirage.ls;

/**
 * 生命周期阶段接口（LS-Interfaces.md §2，2026-10-24 冻结）。
 *
 * <p>契约：
 * <ul>
 *   <li>{@code execute} 同步执行；S7 等移交类阶段允许永不返回（MC 主循环阻塞在内），
 *       调度器不得因此视为异常；</li>
 *   <li>阶段内可恢复错误按 MG-Lifecycle.md 各阶段失败回退表处理；</li>
 *   <li>阶段之间只通过 {@link LifecycleContext} 传状态，禁止静态变量传状态；</li>
 *   <li>阶段顺序 S1→S7 由 MG-Lifecycle.md 冻结，禁止运行期重排。</li>
 * </ul>
 */
public interface Stage {

    /** 阶段 id："S1".."S7"；内建非标准阶段用描述性 id（如 "legacy"）。 */
    String id();

    /** 人类可读名（日志用）。 */
    String name();

    StageOutcome execute(LifecycleContext ctx);
}
