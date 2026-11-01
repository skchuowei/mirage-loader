import io.mirage.loader.mixin.ModMixinConfigs;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * ModMixinConfigs 纯逻辑回归检查（verify_mod_mixin_config.ps1 的跑批程序）。
 * 每条断言打一行 [MMC-CHECK] PASS/FAIL ...，脚本统计；非零 FAIL 时退出码 1。
 *
 * 变异检验指南（改 ModMixinConfigs.java 后至少做一次）：
 *   M1 丢缺失检测：missing.add(path) → found.add(path)   ⇒ "declared-missing" 组必须红
 *   M2 去掉 .json 补全：p = p + ".json" → p = p           ⇒ "normalize"/"no-suffix" 组必须红
 *   M3 去掉根目录兜底：!declaredNormalized.contains(entry) → false ⇒ "root-fallback" 组必须红
 *   M4 collectAll 丢合并：merged.addAll(r.configs) → if (merged.isEmpty()) merged.addAll(r.configs)
 *                                                          ⇒ "collectAll" 组必须红
 */
public class ModMixinConfigCheck {

    static int pass = 0;
    static int fail = 0;

    static void check(String group, boolean ok, String detail) {
        if (ok) {
            pass++;
            System.out.println("[MMC-CHECK] PASS " + group);
        } else {
            fail++;
            System.out.println("[MMC-CHECK] FAIL " + group + " -- " + detail);
        }
    }

    public static void main(String[] args) throws Exception {
        // ---- normalize ----
        check("normalize.add-suffix",
                ModMixinConfigs.normalize("foo.mixins").equals("foo.mixins.json"),
                "foo.mixins 应补成 foo.mixins.json");
        check("normalize.keep-json",
                "foo.json".equals(ModMixinConfigs.normalize("foo.json")),
                "已带 .json 不应再加");
        check("normalize.backslash",
                "mixins/deep.json".equals(ModMixinConfigs.normalize("mixins\\deep")),
                "反斜杠应转正斜杠并补后缀");

        // ---- collectOne：声明命中 ----
        Set<String> root = setOf("mymod.mixins.json", "other.mixins.json");
        Set<String> all = setOf("mymod.mixins.json", "other.mixins.json",
                "mixins/deep.json", "com/example/Foo.class", "fabric.mod.json");

        ModMixinConfigs.Result r1 = ModMixinConfigs.collectOne("m1",
                List.of("mymod.mixins.json"), root, all);
        check("declared.exact",
                r1.configs.equals(List.of("mymod.mixins.json", "other.mixins.json")) && r1.missing.isEmpty(),
                "声明命中 + 未声明项兜底，got " + r1.configs + " missing=" + r1.missing);

        // 声明不带后缀 → 补后缀后命中（本 bug 的主战场）
        ModMixinConfigs.Result r2 = ModMixinConfigs.collectOne("m2",
                List.of("mymod.mixins"), root, all);
        check("declared.no-suffix",
                r2.configs.equals(List.of("mymod.mixins.json", "other.mixins.json")) && r2.missing.isEmpty(),
                "声明无后缀应补 .json 命中（含兜底项），got " + r2.configs + " missing=" + r2.missing);

        // 声明子目录形式
        ModMixinConfigs.Result r3 = ModMixinConfigs.collectOne("m3",
                List.of("mixins/deep"), root, all);
        check("declared.subdir",
                r3.configs.contains("mixins/deep.json") && r3.missing.isEmpty(),
                "子目录声明应命中，got " + r3.configs + " missing=" + r3.missing);

        // 声明但 jar 里不存在 → missing
        ModMixinConfigs.Result r4 = ModMixinConfigs.collectOne("m4",
                List.of("nope.json"), root, all);
        check("declared-missing.reported",
                r4.missing.equals(List.of("nope.json")) && !r4.configs.contains("nope.json"),
                "缺失声明应进 missing 且不进 configs，got " + r4.configs + " missing=" + r4.missing);

        // ---- collectOne：根目录兜底 ----
        ModMixinConfigs.Result r5 = ModMixinConfigs.collectOne("m5",
                List.of(), root, all);
        check("root-fallback.collect",
                r5.configs.equals(List.of("mymod.mixins.json", "other.mixins.json")) && r5.missing.isEmpty(),
                "未声明时盲扫根目录，got " + r5.configs);

        // 声明覆盖后不重复收
        ModMixinConfigs.Result r6 = ModMixinConfigs.collectOne("m6",
                List.of("mymod.mixins.json"), root, all);
        check("root-fallback.no-dup",
                r6.configs.equals(List.of("mymod.mixins.json", "other.mixins.json")),
                "声明的项不应被兜底重复收，got " + r6.configs);

        // 声明子目录 + 根目录兜底并存
        ModMixinConfigs.Result r7 = ModMixinConfigs.collectOne("m7",
                List.of("mixins/deep.json"), root, all);
        check("declared+fallback.combined",
                r7.configs.equals(List.of("mixins/deep.json", "mymod.mixins.json", "other.mixins.json")),
                "声明在前、兜底在后，got " + r7.configs);

        // ---- collectOne：健壮性 ----
        ModMixinConfigs.Result r8 = ModMixinConfigs.collectOne("m8", null, null, null);
        check("null-safe",
                r8.configs.isEmpty() && r8.missing.isEmpty(),
                "全 null 输入应返回空结果而非 NPE");

        ModMixinConfigs.Result r9 = ModMixinConfigs.collectOne("m9",
                Arrays.asList(null, "", "  ", "a.mixins.json"), root, all);
        check("declared.skip-blank",
                !r9.configs.contains("") && !r9.missing.contains("")
                        && r9.missing.contains("a.mixins.json"),
                "空/空白声明应跳过（有效声明正常参与收集，a.mixins.json 不在 jar 中 → missing），"
                        + "got " + r9.configs + " missing=" + r9.missing);

        // 顺序保持：声明顺序优先
        ModMixinConfigs.Result r10 = ModMixinConfigs.collectOne("m10",
                List.of("other.mixins.json", "mixins/deep.json"), root, all);
        check("order.preserved",
                r10.configs.indexOf("other.mixins.json") < r10.configs.indexOf("mixins/deep.json"),
                "声明顺序应保持，got " + r10.configs);

        // ---- collectAll ----
        Map<String, ModMixinConfigs.Result> perMod = new LinkedHashMap<>();
        perMod.put("modA", new ModMixinConfigs.Result(
                List.of("a.mixins.json", "shared.mixins.json"), List.of()));
        perMod.put("modB", new ModMixinConfigs.Result(
                List.of("shared.mixins.json", "b.mixins.json"), List.of()));
        List<String> merged = ModMixinConfigs.collectAll(perMod);
        check("collectAll.dedup-order",
                merged.equals(List.of("a.mixins.json", "shared.mixins.json", "b.mixins.json")),
                "跨 mod 合并应去重保序，got " + merged);

        check("collectAll.null-safe",
                ModMixinConfigs.collectAll(null).isEmpty()
                        && ModMixinConfigs.collectAll(new LinkedHashMap<>()).isEmpty(),
                "null/空输入应返回空表");

        // ---- 汇总 ----
        System.out.println("[MMC-CHECK] TOTAL pass=" + pass + " fail=" + fail);
        if (fail > 0) {
            System.exit(1);
        }
    }

    static Set<String> setOf(String... items) {
        return new HashSet<>(Arrays.asList(items));
    }
}
