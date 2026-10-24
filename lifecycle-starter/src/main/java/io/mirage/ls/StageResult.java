package io.mirage.ls;

/** 阶段结果三态（LS-Interfaces.md §3）。 */
public enum StageResult {
    /** 正常完成，进入下一阶段。 */
    SUCCESS,
    /** 本阶段按设计跳过（如 NATIVE 模式跳过 AG 转译），继续。 */
    SKIPPED,
    /** 致命错误，调度器立即 exit 1。 */
    FATAL
}
