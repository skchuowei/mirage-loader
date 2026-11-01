package io.mirage.loader.mixin;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * mod mixin config 收集器（纯逻辑，零依赖，可离线断言）。
 *
 * <p>背景（2026-11-01，字节码取证）：Sponge Mixin 0.8.7 的 {@code Mixins.addConfiguration(name)}
 * → {@code Config.create(name,...)} → {@code getResourceAsStream(name)} 对传入名<strong>原样</strong>查资源，
 * 查不到抛 {@code IllegalArgumentException} 且被 {@code createConfiguration} 静默吞掉
 * （"Error encountered reading mixin config ..."，走 Mixin 自家 logger，不进 Mirage 日志）。
 * 因此注册名必须是 jar 内<strong>完整资源路径</strong>（带 .json 后缀，可含子目录）；
 * 此前 TransformStage 传的是去掉后缀的名字，mod mixin config 从初始提交起就一个都没解析成功过。
 *
 * <p>收集策略：
 * <ol>
 *   <li><strong>声明优先</strong>：fabric.mod.json "mixins" 字段声明的路径（权威来源，可含子目录），
 *       规范化（不以 .json 结尾 → 补 .json）后在 jar 全部 entry 中核验存在性；
 *   <li><strong>根目录盲扫兜底</strong>：jar 根目录的 {@code *.mixin.json}（保持既有行为，
 *       兼容不声明字段的 mod）；被声明覆盖的不重复收；
 *   <li><strong>缺失告警</strong>：声明了但 jar 里找不到的路径进 {@link Result#missing}，由调用方 WARN 指名；
 *   <li><strong>去重保序</strong>：单 mod 内与全局合并都保序去重。
 * </ol>
 */
public final class ModMixinConfigs {

    private ModMixinConfigs() {
    }

    /** 单个 mod 的收集结果。 */
    public static final class Result {
        /** 本 mod 要注册的完整资源路径（带 .json）。 */
        public final List<String> configs;
        /** 声明了但在 jar 里找不到的路径（调用方 WARN 用）。 */
        public final List<String> missing;

        public Result(List<String> configs, List<String> missing) {
            this.configs = configs;
            this.missing = missing;
        }
    }

    /**
     * 收集单个 mod 要注册的 mixin config 资源路径。
     *
     * @param modLabel   日志/告警用 mod 标识（id 或文件名）
     * @param declared   fabric.mod.json "mixins" 声明的路径（可空/空 = 未声明）
     * @param rootEntries jar 根目录下的 {@code *.mixin.json} entry 名集合（如 "foo.mixins.json"）
     * @param allEntries  jar 全部 entry 名集合（用于核验声明路径的子目录形式）
     * @return 收集结果（configs / missing），永不返回 null
     */
    public static Result collectOne(String modLabel,
                                    List<String> declared,
                                    Set<String> rootEntries,
                                    Set<String> allEntries) {
        Set<String> found = new LinkedHashSet<>();
        List<String> missing = new ArrayList<>();
        Set<String> declaredNormalized = new LinkedHashSet<>();

        if (declared != null) {
            for (String raw : declared) {
                if (raw == null || raw.isEmpty()) continue;
                String path = normalize(raw);
                declaredNormalized.add(path);
                if (allEntries != null && allEntries.contains(path)) {
                    found.add(path);
                } else if (rootEntries != null && rootEntries.contains(path)) {
                    found.add(path);
                } else {
                    missing.add(path);
                }
            }
        }

        // 根目录盲扫兜底：未被声明覆盖的照收（兼容不声明 mixins 字段的 mod）
        if (rootEntries != null) {
            for (String entry : rootEntries) {
                if (!declaredNormalized.contains(entry)) {
                    found.add(entry);
                }
            }
        }

        return new Result(new ArrayList<>(found), missing);
    }

    /**
     * 合并多个 mod 的收集结果为全局注册列表（保序去重——后注册同名 config 会被 Mixin 视为重复，先到先得）。
     *
     * @param perMod modLabel → Result
     * @return 全局要注册的资源路径列表
     */
    public static List<String> collectAll(Map<String, Result> perMod) {
        Set<String> merged = new LinkedHashSet<>();
        if (perMod != null) {
            for (Result r : perMod.values()) {
                if (r != null && r.configs != null) {
                    merged.addAll(r.configs);
                }
            }
        }
        return new ArrayList<>(merged);
    }

    /**
     * 规范化声明路径：不以 .json 结尾 → 补 .json；正斜杠统一（jar entry 用 '/'）。
     */
    public static String normalize(String raw) {
        String p = raw.replace('\\', '/');
        if (!p.endsWith(".json")) {
            p = p + ".json";
        }
        return p;
    }

    /** 便捷重载：entry 名集合从 Collection 构造。 */
    public static Result collectOne(String modLabel,
                                    List<String> declared,
                                    Collection<String> rootEntries,
                                    Collection<String> allEntries) {
        return collectOne(modLabel, declared,
                rootEntries == null ? null : new LinkedHashSet<>(rootEntries),
                allEntries == null ? null : new LinkedHashSet<>(allEntries));
    }
}
