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
        ALREADY_RECORDED, // 已有录制结果（含上次失败的结果：重录不会改变结论）
        /**
         * 上次录制因为「加载器能力不足」失败（fake 缺口 / stub 缺方法），
         * 且加载器侧没有变化 ⇒ 重录必然同样失败，不浪费一次 fake pass（MSR M4）。
         */
        PREVIOUSLY_UNSUPPORTED
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
                case PREVIOUSLY_UNSUPPORTED -> "上次因加载器能力不足失败且加载器未变，重录无意义";
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
        if (prev != null) {
            // MSR M4：上次因为「加载器能力不足」失败（fake 缺口 / stub 缺方法），
            // 而加载器侧（fake 表面 + compat stub）没变的话，重录必然同样失败 ——
            // 白白付一次 fake pass 的钱，还多一条误导性的失败日志。
            // 只在「失败原因与加载器无关」时（mod 自己坏了）才允许重录。
            if (isUnsupportedByLoader(prev) && !loaderCapabilityChanged()) {
                return new Decision(false, Reason.PREVIOUSLY_UNSUPPORTED);
            }
            return new Decision(false, Reason.ALREADY_RECORDED);
        }
        if (detected == null || !hasCompleteRanges(detected)) {
            return new Decision(true, Reason.MISSING_RANGES);
        }
        return new Decision(false, Reason.RANGES_COMPLETE);
    }

    /**
     * 上次失败是否属于「加载器能力不足」（而非 mod 自身问题）。
     *
     * <p>判据看 {@code Recorded.error} 的分类前缀（MSR M3 写进去的）：
     * {@code missing_fake:} / {@code stub_incomplete:} ⇒ 加载器的锅；
     * {@code ctor_failed:} ⇒ mod 的锅，允许重录（mod 可能已被修）。
     *
     * <p>老缓存（0.4 之前、没有分类前缀）一律视为「mod 的锅」⇒ 允许重录一次，
     * 这是保守选择：宁可多录一次，也不要把「现在能录了」的 mod 永久跳过。
     */
    public static boolean isUnsupportedByLoader(Recorded prev) {
        if (prev == null || !prev.failed) return false;
        String err = prev.error;
        if (err == null || err.isEmpty()) return false;
        return err.startsWith("missing_fake:") || err.startsWith("stub_incomplete:");
    }

    /**
     * 加载器能力（fake 表面 + compat stub）自上次录制以来是否变过。
     *
     * <p>fake 表面的变化由 {@code .mid-cache} 的 {@code fakeVersion} 校对负责
     * （表面变了 ⇒ 缓存判为未命中 ⇒ 这里根本走不到本分支）。
     * 但 <b>compat stub 的变化不在 fakeVersion 覆盖范围内</b>（stub 是模块源码，不是 fake 资源），
     * 所以这里额外比对：compat stub 字节码的指纹变了 ⇒ 允许重录。
     *
     * <p>实现方式：取 compat 模块 classpath 上 stub 类的资源指纹。
     * 取不到（开发期目录布局不同）⇒ 返回 true（允许重录，保守）。
     */
    private static boolean loaderCapabilityChanged() {
        String now = compatStubFingerprint();
        if (now == null || now.isEmpty()) return true;   // 取不到 → 允许重录（保守）
        String prev = LAST_STUB_FINGERPRINT.get();
        if (prev == null) {
            LAST_STUB_FINGERPRINT.set(now);
            return true;                                   // 首次：认为变了，允许录一次
        }
        return !prev.equals(now);
    }

    /** 上次观测到的 compat stub 指纹（进程内单值，够用：一次启动内 stub 不会变）。 */
    private static final java.util.concurrent.atomic.AtomicReference<String> LAST_STUB_FINGERPRINT =
            new java.util.concurrent.atomic.AtomicReference<>(null);

    /**
     * compat stub 的指纹：取若干代表性 stub 类的资源长度 + 最后修改时间。
     *
     * <p>用「类是否存在 + 字节长度」而不是内容哈希，是为了避免每次启动都把整个
     * compat 模块的 class 读一遍（那不便宜，而这里只需要「变了没有」这个信号）。
     */
    private static String compatStubFingerprint() {
        String[] probes = {
            "net/neoforged/api/distmarker/Dist.class",
            "net/neoforged/fml/ModList.class",
            "net/neoforged/fml/loading/FMLPaths.class",
            "net/neoforged/fml/loading/FMLEnvironment.class",
            "net/neoforged/fml/ModContainer.class",
            "net/neoforged/fml/event/IModBusEvent.class",
        };
        ClassLoader cl = RecordingPolicy.class.getClassLoader();
        StringBuilder sb = new StringBuilder();
        for (String p : probes) {
            sb.append(p).append('=');
            try (java.io.InputStream in = cl.getResourceAsStream(p)) {
                if (in == null) { sb.append("absent"); continue; }
                long len = 0;
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) != -1) len += n;
                sb.append(len);
            } catch (Exception e) {
                sb.append("err");
            }
            sb.append(';');
        }
        return sb.toString();
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
