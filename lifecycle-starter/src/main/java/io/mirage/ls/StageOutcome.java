package io.mirage.ls;

/**
 * 阶段执行结果（LS-Interfaces.md §3，2026-10-24 冻结）。
 *
 * <p>FATAL 由调度器统一处理：打印后立即 exit 1，不执行后续阶段；
 * 阶段内未预期异常不要自己吞，直接抛出，调度器转 FATAL。
 */
public record StageOutcome(StageResult result, String detail) {

    public static StageOutcome success() {
        return new StageOutcome(StageResult.SUCCESS, null);
    }

    public static StageOutcome skipped(String reason) {
        return new StageOutcome(StageResult.SKIPPED, reason);
    }

    public static StageOutcome fatal(String message) {
        return new StageOutcome(StageResult.FATAL, message);
    }
}
