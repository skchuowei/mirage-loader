package io.mirage.loader.depends;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * FabricDepends 纯 JVM 断言程序（P1-14）。
 * 由 verify_mod_depends.ps1 现场编译运行；[MDC-CHECK] PASS/FAIL 逐条打点。
 * 与被测类同包（io.mirage.loader.depends），可直接触达包私有 normalizeValue。
 */
public class FabricDependsCheck {

    private static int pass = 0;
    private static int fail = 0;

    private static void check(String name, boolean ok, String why) {
        if (ok) {
            pass++;
            System.out.println("[MDC-CHECK] PASS " + name);
        } else {
            fail++;
            System.out.println("[MDC-CHECK] FAIL " + name + " -- " + why);
        }
    }

    public static void main(String[] args) {
        Set<String> ids = Set.of("fabric-api", "cloth-config", "modmenu");

        // ---- 1. 运行时内置键过滤 ----
        Map<String, Object> builtin = new HashMap<>();
        builtin.put("minecraft", ">=1.21");
        builtin.put("java", ">=17");
        builtin.put("fabricloader", ">=0.16.0");
        FabricDepends.Split s1 = FabricDepends.split("m", builtin, ids);
        check("builtin.all-ignored",
                s1.ignoredBuiltins.containsAll(Arrays.asList("minecraft", "java", "fabricloader"))
                        && s1.missing.isEmpty() && s1.resolvable.isEmpty(),
                "三个运行时键应全部进 ignoredBuiltins，got missing=" + s1.missing + " resolvable=" + s1.resolvable.keySet());

        // 大小写不敏感
        Map<String, Object> caseUp = new HashMap<>();
        caseUp.put("Minecraft", "x");
        FabricDepends.Split s2 = FabricDepends.split("m", caseUp, ids);
        check("builtin.case-insensitive",
                s2.ignoredBuiltins.size() == 1 && s2.missing.isEmpty(),
                "Minecraft 应按大小写不敏感匹配内置键，got ignored=" + s2.ignoredBuiltins + " missing=" + s2.missing);

        // ---- 2. 可解析依赖 ----
        Map<String, Object> mixed = new HashMap<>();
        mixed.put("fabric-api", ">=0.100.0");
        mixed.put("cloth-config", Arrays.asList(">=11.0", ">=10.0"));
        mixed.put("unknown-dep", true);
        FabricDepends.Split s3 = FabricDepends.split("m", mixed, ids);
        check("resolvable.simple",
                s3.resolvable.keySet().containsAll(Arrays.asList("fabric-api", "cloth-config")),
                "容器集内的依赖应进 resolvable，got " + s3.resolvable.keySet());
        check("value.string-preserved",
                ">=0.100.0".equals(s3.resolvable.get("fabric-api")),
                "String value 应原样保留，got " + s3.resolvable.get("fabric-api"));
        check("value.list-first-string",
                ">=11.0".equals(s3.resolvable.get("cloth-config")),
                "List value 应取第一个 String 元素，got " + s3.resolvable.get("cloth-config"));
        check("missing.reported",
                s3.missing.equals(List.of("unknown-dep")),
                "容器集外的依赖应进 missing 且不含其他项，got " + s3.missing);

        // ---- 3. normalizeValue 直测（同包）----
        check("value.nonstring-empty",
                "".equals(FabricDepends.normalizeValue(Boolean.TRUE))
                        && "".equals(FabricDepends.normalizeValue(42)),
                "非 String 非 List 的标量应归一为空串");
        check("value.list-empty-or-nonstring",
                "".equals(FabricDepends.normalizeValue(Arrays.asList())),
                "空 List 应归一为空串");
        check("value.null-empty",
                "".equals(FabricDepends.normalizeValue(null)),
                "null value 应归一为空串");

        // ---- 4. 边界输入 ----
        check("null-depends-empty-split",
                FabricDepends.split("m", null, ids).resolvable.isEmpty()
                        && FabricDepends.split("m", null, ids).missing.isEmpty(),
                "null depends 应返回空 Split");
        check("empty-depends-empty-split",
                FabricDepends.split("m", new HashMap<>(), ids).missing.isEmpty(),
                "空 depends 应返回空 Split");
        check("null-availableIds-all-missing",
                FabricDepends.split("m", Map.of("a", ""), null).missing.equals(List.of("a")),
                "availableIds 为 null 时非内置键应全部进 missing");

        // 空键跳过
        Map<String, Object> blankKey = new HashMap<>();
        blankKey.put("", "x");
        FabricDepends.Split s5 = FabricDepends.split("m", blankKey, ids);
        check("blank-key-skipped",
                s5.missing.isEmpty() && s5.resolvable.isEmpty() && s5.ignoredBuiltins.isEmpty(),
                "空字符串键应被跳过，got " + s5.missing + "/" + s5.resolvable.keySet() + "/" + s5.ignoredBuiltins);

        // ---- 5. 输出稳定性 ----
        Map<String, Object> multi = new HashMap<>();
        multi.put("zzz", "");
        multi.put("aaa", "");
        multi.put("bbb", "");
        FabricDepends.Split s6 = FabricDepends.split("m", multi, ids);
        check("missing.order-stable",
                s6.missing.equals(Arrays.asList("aaa", "bbb", "zzz")),
                "missing 应按字典序稳定输出（TreeSet），got " + s6.missing);

        // ---- 6. resolvable 优先级：同一键不会同时进两桶 ----
        check("buckets.disjoint",
                !s3.resolvable.containsKey("unknown-dep") && !s3.missing.contains("fabric-api"),
                "任一依赖键只能落一个桶");

        System.out.println("[MDC-CHECK] summary pass=" + pass + " fail=" + fail);
        if (fail > 0) System.exit(1);
    }
}
