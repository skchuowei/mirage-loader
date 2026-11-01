package io.mirage.loader.depends;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Fabric depends 过滤器（纯逻辑，无 IO 无日志依赖，可离线断言）。
 *
 * <p>fabric.mod.json 的 depends 键分三类：
 * <ol>
 *   <li><b>运行时键</b>：{@code minecraft} / {@code java} / {@code fabricloader}——
 *       由加载器本体满足，永远不在 mod 容器集里，直接忽略（不进排序、不告缺失）；</li>
 *   <li><b>真 mod 依赖</b>：key 在已发现 mod id 集里 → 进入 {@code manifest.depends}，
 *       使 {@link io.mirage.loader.DependencySorter} 拓扑排序生效（依赖先加载）；</li>
 *   <li><b>缺失的 mod 依赖</b>：key 不在容器集——可能是真缺失，也可能是
 *       Fabric {@code provides} 聚合语义（如 {@code fabric-lifecycle-events-v1} 由
 *       fabric-api 聚合 jar 提供，本加载器暂不解析 provides）→ 归入 missing，由调用方
 *       WARN 不阻断（硬抛会误杀，等 provides 支持后再议升级）。</li>
 * </ol>
 *
 * <p>value 规范化：String 原样（版本范围，如 {@code ">=1.0 <2.0"}）；List 取第一个
 * String 元素；其他 JSON 标量（Boolean/Number）→ 空串。DependencySorter 只看 key
 * 集合做排序、忽略版本，所以 value 只求不炸、不丢类型信息。
 */
public final class FabricDepends {

    /** 运行时内置键（小写）。这些依赖由加载器/运行环境满足，不参与 mod 间排序。 */
    public static final Set<String> BUILTIN_KEYS = Set.of("minecraft", "java", "fabricloader");

    /** split 结果：resolvable 进 manifest.depends（空则调用方置 null）；missing 由调用方 WARN。 */
    public static final class Split {
        public final Map<String, String> resolvable = new LinkedHashMap<>();
        public final List<String> missing = new ArrayList<>();
        public final List<String> ignoredBuiltins = new ArrayList<>();
    }

    private FabricDepends() {
    }

    /**
     * 把 fabric depends 原始映射切成三桶。modId 只用于调用方日志语境，本方法不判空 modId。
     * rawDepends 为 null/空时返回空 Split。
     */
    public static Split split(String modId, Map<String, Object> rawDepends, Set<String> availableIds) {
        Split out = new Split();
        if (rawDepends == null || rawDepends.isEmpty()) return out;
        // TreeSet 保证输出稳定（日志/断言可复现），不依赖 JSON 顺序
        Set<String> known = availableIds != null ? availableIds : Set.of();
        for (String key : new TreeSet<>(rawDepends.keySet())) {
            if (key == null || key.isEmpty()) continue;
            String lower = key.toLowerCase(java.util.Locale.ROOT);
            if (BUILTIN_KEYS.contains(lower)) {
                out.ignoredBuiltins.add(key);
                continue;
            }
            if (known.contains(key)) {
                out.resolvable.put(key, normalizeValue(rawDepends.get(key)));
            } else {
                out.missing.add(key);
            }
        }
        return out;
    }

    /** value 规范化：String 原样；List 取第一个 String；其他 → 空串。 */
    static String normalizeValue(Object v) {
        if (v instanceof String s) return s;
        if (v instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof String s) return s;
            }
            return "";
        }
        return "";
    }
}
