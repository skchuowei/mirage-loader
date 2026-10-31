package io.mirage.loader;

import io.mirage.loader.MidCache.Recorded;
import io.mirage.loader.NeoForgeModDetector.Detected;

/**
 * MSR P2 · fake pass 的触发判据（纯函数，无副作用，便于单测）。
 *
 * <p>P1 之前的判据是"缓存里没有 {@code recorded} 就录"，等于**每个** NeoForge mod
 * 首启都额外跑一遍构造函数（沙盒一遍 + 真实一遍），副作用双发、首启最坏 N×2 秒。
 *
 * <p>P2 的判据换成 road map 指定的三条触发条件：
 * <ol>
 *   <li>{@code --sandbox-record=force}：无条件录；</li>
 *   <li>toml 里缺 {@code neoforge} 或 {@code minecraft} 的版本区间：录（伪装/兼容判定需要它）；</li>
 *   <li>其余情况：不录 —— 真实加载时的被动捕获会把 touched 数据补上。</li>
 * </ol>
 *
 * <p><b>为什么"缺区间"仍要录</b>：区间缺失说明该 mod 没有声明它跑在哪个平台上，
 * 沙盒至少能给出"它的主类在这种最小表面上能否构造成功"这个信号，属诊断价值。
 * <b>但要如实说明</b>：fake pass 本身**不产生**版本信息（{@code SandboxRecorder} 只是把
 * 静态解析到的区间抄进 {@code recorded}），所以它救不了缺区间的 mod，只能标记它。
 *
 * <p><b>为什么 {@code recorded != null} 一律不再录（除 force）</b>：
 * 同一个 fake 表面重录只会得到同一份结果（尤其是上次就 {@code failed} 的情况，
 * 会变成每次启动都白跑一遍）。表面若真变了，{@code MidCache.lookup} 的
 * {@code fakeVersion} 校对会先把缓存判为未命中，这里自然看到 {@code prev == null}。
 */
public final class RecordingPolicy {

    /** 决策理由（日志用；名字稳定，便于 grep）。 */
    public enum Reason {
        OFF,              // 开关关闭
        FORCE,            // --sandbox-record=force
        MISSING_RANGES,   // toml 缺 neoforge / minecraft 版本区间
        RANGES_COMPLETE,  // 区间完整，touched 数据交给被动捕获
        ALREADY_RECORDED  // 已有录制结果（含上次失败的结果：重录不会改变结论）
    }

    /** 决策结果。 */
    public record Decision(boolean record, Reason reason) {
        /** 一行人类可读的理由（日志用）。 */
        public String why() {
            return switch (reason) {
                case OFF -> "录制开关关闭";
                case FORCE -> "force 强制重录";
                case MISSING_RANGES -> "toml 缺版本区间";
                case RANGES_COMPLETE -> "版本区间完整，跳过（数据由被动捕获补）";
                case ALREADY_RECORDED -> "已有录制结果，跳过";
            };
        }
    }

    /**
     * @param mode     off / auto / force（非法值按 auto 处理，与 {@code LifecycleContext} 一致）
     * @param detected 静态识别结果（不可为 null）
     * @param prev     缓存里已有的录制结果；null = 没录过 / 已失效
     */
    public static Decision decide(String mode, Detected detected, Recorded prev) {
        if ("off".equalsIgnoreCase(mode)) return new Decision(false, Reason.OFF);
        if ("force".equalsIgnoreCase(mode)) return new Decision(true, Reason.FORCE);
        if (prev != null) return new Decision(false, Reason.ALREADY_RECORDED);
        if (detected == null || !hasCompleteRanges(detected)) {
            return new Decision(true, Reason.MISSING_RANGES);
        }
        return new Decision(false, Reason.RANGES_COMPLETE);
    }

    /** 两个平台区间都非空白才算"完整"。 */
    public static boolean hasCompleteRanges(Detected d) {
        return d != null && notBlank(d.neoForgeVersionRange) && notBlank(d.mcVersionRange);
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private RecordingPolicy() {}
}
