package io.mirage.loader.masq;

import io.mirage.api.log.MirageLog;
import io.mirage.api.version.VersionMasks;

import io.mirage.loader.ModContainer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MSR P2 · 版本伪装回放的装配器：把 MID 里"这个 mod 期望的版本"变成"这个 mod 看到版本"。
 *
 * <p>为什么需要：MG 不是真 NeoForge，mod 却经常在构造里问"我在哪个版本上跑"
 * （`FMLLoader.versionInfo().neoForgeVersion()` / `mcVersion()`），再按版本走不同分支。
 * 直接报当前版本会把 mod 逼到 MG 尚未验证的路径上，甚至被它主动拒绝。
 * 伪装的做法是**按 mod 上下文报它自己声明过的版本**（数据来自 toml 的
 * `[[dependencies.*]] versionRange`，与 {@code Detected} 同源）。
 *
 * <p>"伪装"到什么程度（`pick` 的规则，纯函数）：
 * <ul>
 *   <li>当前版本落在区间内 → 报当前版本（<b>不算伪装</b>，最诚实）；</li>
 *   <li>当前版本不在区间内 → 报离当前最近的区间端点（这是真正的"伪装"）；</li>
 *   <li>区间缺失/格式不认识 → 报基线，并 debug 一行说明（不猜）。</li>
 * </ul>
 *
 * <p>数据是真的、结论是保守的：本类只影响 mod 读到的版本字符串，不改 MG 的实际行为，
 * 也不新增"版本不匹配就拒绝加载"的判定（那是另一件事，目前全仓没有这个判定）。
 */
public final class LoaderVersionMasquerade {

    /** MG 兼容层自报的 NeoForge 版本（跟随 MC 版本线：26.2 → "26.2"）。 */
    private static final Map<String, Integer> SPOOFED = new ConcurrentHashMap<>();

    /**
     * 安装基线：没有伪装值的 mod（或非 mod 上下文）将看到这两个版本。
     *
     * @param mcVersion 当前 MC 版本，如 "26.2"
     */
    public static void installBaseline(String mcVersion) {
        String v = (mcVersion == null || mcVersion.isBlank()) ? "0.0.0" : mcVersion;
        VersionMasks.setFallback(new VersionMasks.Mask(v, v));
    }

    /**
     * 按 mod 清单批量登记伪装值（在 S6 之前调用一次）。
     *
     * @return 实际登记的 mod 数
     */
    public static int installAll(List<ModContainer> mods, String currentMcVersion) {
        installBaseline(currentMcVersion);
        if (mods == null || mods.isEmpty()) return 0;
        int n = 0;
        int spoofed = 0;
        for (ModContainer m : mods) {
            if (m == null || m.manifest == null || m.manifest.id == null) continue;
            String mcRange = m.mcVersionRange();
            String neoRange = m.neoForgeVersionRange();
            if (isBlank(mcRange) && isBlank(neoRange)) continue;

            VersionMasks.Mask mask = maskFor(mcRange, neoRange, currentMcVersion);
            VersionMasks.put(m.manifest.id, mask);
            n++;
            if (!mask.mcVersion().equals(currentMcVersion)) {
                spoofed++;
                SPOOFED.put(m.manifest.id, 1);
            }
        }
        if (n > 0) {
            MirageLog.of("Mirage:MID").debug("[MID] 版本伪装已装: " + n + " 个 mod"
                    + (spoofed > 0 ? "（其中 " + spoofed + " 个报的版本 ≠ 当前 " + currentMcVersion + "）" : ""));
        }
        return n;
    }

    /** 清空（测试隔离 / 重新装配）。 */
    public static void reset() {
        VersionMasks.clear();
        SPOOFED.clear();
    }

    /**
     * 算一个 mod 应看到的版本。
     *
     * @param mcRange      mod 声明的 MC 版本区间（可空）
     * @param neoRange     mod 声明的 NeoForge 版本区间（可空）
     * @param currentMc    当前 MC 版本
     */
    public static VersionMasks.Mask maskFor(String mcRange, String neoRange, String currentMc) {
        String mc = pick(mcRange, currentMc);
        // NeoForge 版本区间与 MC 版本区间在 NeoForge 里几乎是同一条线（26.x 起），
        // 但语义不同：能取到就给真实声明的下界，取不到跟随 MC 版本。
        String neo = pick(neoRange, currentMc);
        return new VersionMasks.Mask(mc, neo);
    }

    /**
     * 从 Maven 风格版本区间里挑一个具体版本：优先当前版本，其次最近端点。
     *
     * <p>支持的写法（NeoForge/Forge toml 里实际出现过的形态）：
     * {@code [26.1,27)}、{@code [26.1,)}、{@code (,27)}、{@code [26.1]}、{@code 26.1}（裸版本）。
     * 多条用 {@code ||} 分隔时只看第一条（够用即可，不做完整 Maven 语义）。
     */
    public static String pick(String range, String fallback) {
        if (isBlank(range)) return fallback;
        String r = range.trim();
        int or = r.indexOf("||");
        if (or > 0) r = r.substring(0, or).trim();

        char first = r.charAt(0);
        if (first != '[' && first != '(') {
            // 裸版本号（如 "26.1"）：这就是 mod 期望的唯一版本
            return r.isEmpty() ? fallback : r;
        }
        int close = Math.max(r.lastIndexOf(']'), r.lastIndexOf(')'));
        if (close <= 0) return fallback;
        boolean loInclusive = first == '[';
        boolean hiInclusive = r.charAt(close) == ']';
        String body = r.substring(1, close);
        int comma = body.indexOf(',');
        String lo, hi;
        if (comma < 0) {
            // [26.1] = 单点
            lo = body.trim();
            hi = lo;
        } else {
            lo = body.substring(0, comma).trim();
            hi = body.substring(comma + 1).trim();
        }
        if (lo.isEmpty() && hi.isEmpty()) return fallback;
        if (lo.isEmpty()) return hi.isEmpty() ? fallback : hi;
        if (hi.isEmpty()) return lo;

        if (inRange(fallback, lo, loInclusive, hi, hiInclusive)) return fallback;
        // 不在区间：取离当前较近的端点（两端都比当前大→取下界；都比当前小→取上界）
        return compare(fallback, lo) < 0 ? lo : hi;
    }

    /** 版本是否落在区间内；版本号无法比较时按"不在区间"处理（保守）。 */
    static boolean inRange(String v, String lo, boolean loInc, String hi, boolean hiInc) {
        if (v == null) return false;
        int cLo = compare(v, lo);
        if (loInc ? cLo < 0 : cLo <= 0) return false;
        int cHi = compare(v, hi);
        return hiInc ? cHi <= 0 : cHi < 0;
    }

    /**
     * 版本比较：按 {@code . - +} 切段，数字段按数值、非数字段按字典序。
     * 足够应付 {@code 26.2}、{@code 26.1.0.1-beta} 这类形态；解析不动的段一律当 0（不抛）。
     */
    static int compare(String a, String b) {
        String[] pa = segments(a);
        String[] pb = segments(b);
        int n = Math.max(pa.length, pb.length);
        for (int i = 0; i < n; i++) {
            String x = i < pa.length ? pa[i] : "0";
            String y = i < pb.length ? pb[i] : "0";
            boolean nx = isNumber(x);
            boolean ny = isNumber(y);
            int c;
            if (nx && ny) {
                c = Long.compare(parseLong(x), parseLong(y));
            } else if (nx != ny) {
                // 有数字的段比非数字段"大"（26.2 > 26.2-beta）
                c = nx ? 1 : -1;
            } else {
                c = x.compareTo(y);
            }
            if (c != 0) return c;
        }
        return 0;
    }

    private static String[] segments(String v) {
        if (v == null || v.isBlank()) return new String[0];
        return v.trim().split("[.\\-+]");
    }

    private static boolean isNumber(String s) {
        if (s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) return false;
        }
        return true;
    }

    private static long parseLong(String s) {
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /** 诊断：当前已登记的伪装 mod 数。 */
    public static int installedCount() {
        return SPOOFED.size();
    }

    /** 诊断：已伪装的 modId 列表。 */
    public static List<String> spoofedMods() {
        return new ArrayList<>(SPOOFED.keySet());
    }

    private LoaderVersionMasquerade() {}
}
