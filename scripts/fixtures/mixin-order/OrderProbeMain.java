package orderfixture;

import io.mirage.loader.MirageClassLoader;
import io.mirage.mixin.ModMixinTargets;
import io.mirage.mixin.WeavePriority;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 织入优先级门禁的行为层探针（{@code scripts/verify_mixin_order.py} 调用）。
 *
 * <p>三个阶段，各自独立进程：
 * <ul>
 *   <li>{@code logic} —— {@link WeavePriority#decide(String, Set)} 纯函数判定表（不碰 MC、不碰静态态以外的 I/O）；</li>
 *   <li>{@code collect} —— {@link ModMixinTargets#collect(java.util.function.Function, Iterable)} 用内存资源跑
 *       「JSON → mixin 类名 → @Mixin 注解读取」，含 mirage.mixins.json 排除、坏 JSON / 缺类降级；</li>
 *   <li>{@code endtoend} —— 真 {@link MirageClassLoader} + 真 {@code MirageMixinBootstrap.init}，加载
 *       {@code net.minecraft.world.item.CreativeModeTab}，用反射看 MG 自研 ASM 追加的无参
 *       {@code builder()} 还在不在（在 = MG 织入了；不在 = 让路了），并检查 WARN 文案。</li>
 * </ul>
 *
 * <p>输出一律 ASCII KEY=VALUE / {@code [MO-CHECK] PASS|FAIL}，最后由脚本统计；
 * 中文只在进程内比较（{@code describe()} 的文案），不打印，避免控制台编码干扰判据。
 */
public final class OrderProbeMain {

    private static final String TAB = "net.minecraft.world.item.CreativeModeTab";
    private static final String SHARED = "net.minecraft.SharedConstants";
    private static final String PACK = "net.minecraft.server.packs.VanillaPackResourcesBuilder";
    private static final String ITEM = "net.minecraft.world.item.Item";

    private static int pass;
    private static int fail;

    private OrderProbeMain() {
    }

    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : "";
        switch (mode) {
            case "logic":
                logic();
                break;
            case "collect":
                collect();
                break;
            case "endtoend":
                endToEnd(args);
                break;
            default:
                System.out.println("[MO-CHECK] FAIL mode.unknown -- " + mode);
                fail++;
        }
        System.out.println("RESULT " + (fail == 0 ? "PASS" : "FAIL"));
        System.out.flush();
        if (fail != 0) {
            System.exit(1);
        }
    }

    private static void check(String label, boolean ok, String detail) {
        if (ok) {
            pass++;
            System.out.println("[MO-CHECK] PASS " + label);
        } else {
            fail++;
            System.out.println("[MO-CHECK] FAIL " + label + " -- " + detail);
        }
    }

    // ------------------------------------------------------------------ logic

    private static void logic() {
        check("logic.mg-target-count", WeavePriority.mgTargets().size() == 7,
                "MG 硬编码目标应为 7 个，实际 " + WeavePriority.mgTargets().size());

        WeavePriority.Decision dNo = WeavePriority.decide(TAB, Set.of());
        check("logic.no-conflict.apply", dNo.applyMgWeaving && !dNo.modMixinConflict
                        && "mg-target".equals(dNo.reason),
                "MG 目标 + mod 未织入 ⇒ 应照旧执行，reason=mg-target，实际 " + dNo.reason);

        WeavePriority.Decision dHit = WeavePriority.decide(TAB, Set.of(TAB));
        check("logic.conflict.let-way", !dHit.applyMgWeaving && dHit.modMixinConflict
                        && "mod-mixin-conflict".equals(dHit.reason),
                "MG 目标 + mod 命中 ⇒ 必须让路，实际 apply=" + dHit.applyMgWeaving + " reason=" + dHit.reason);

        String desc = dHit.describe();
        check("logic.conflict.warn-has-class", desc.contains(TAB), "让路 WARN 必须点名类: " + desc);
        check("logic.conflict.warn-has-let-way", desc.contains("让路"), "让路 WARN 必须出现「让路」: " + desc);
        check("logic.conflict.warn-has-purpose",
                dHit.mgPurpose != null && !dHit.mgPurpose.isEmpty() && desc.contains(dHit.mgPurpose),
                "让路 WARN 必须说明放弃了哪项 MG 功能: " + desc);

        WeavePriority.Decision dOther = WeavePriority.decide("net.fabricmc.api.ModInitializer",
                Set.of("net.fabricmc.api.ModInitializer"));
        check("logic.non-mg-target.apply", dOther.applyMgWeaving && !dOther.modMixinConflict
                        && "not-mg-target".equals(dOther.reason),
                "非 MG 目标（哪怕 mod 织入了）不影响 MG 织入判定，实际 " + dOther.reason);

        check("logic.null-targets.apply", WeavePriority.decide(TAB, null).applyMgWeaving,
                "mod 目标集合为 null（收集失败降级）⇒ 不让路、保持老行为");
        check("logic.unknown-class.apply", WeavePriority.decide("x.y.Z", Set.of("x.y.Z")).applyMgWeaving,
                "未知类不产生让路");

        // 7 个目标逐个自洽：空集执行、自命中让路，且都有非空功能说明
        boolean allOk = true;
        StringBuilder bad = new StringBuilder();
        for (Map.Entry<String, String> e : WeavePriority.mgTargets().entrySet()) {
            WeavePriority.Decision a = WeavePriority.decide(e.getKey(), Set.of());
            WeavePriority.Decision b = WeavePriority.decide(e.getKey(), Set.of(e.getKey()));
            boolean ok = a.applyMgWeaving && !a.modMixinConflict
                    && !b.applyMgWeaving && b.modMixinConflict
                    && e.getValue() != null && !e.getValue().isEmpty()
                    && b.describe().contains(e.getValue());
            if (!ok) {
                allOk = false;
                bad.append(e.getKey()).append(' ');
            }
        }
        check("logic.all-7-targets-consistent", allOk, "不一致的目标: " + bad);

        check("logic.conflicts-order-size",
                WeavePriority.conflicts(Set.of(TAB, SHARED, ITEM)).size() == 2,
                "交集只算 MG 目标: " + WeavePriority.conflicts(Set.of(TAB, SHARED, ITEM)));
        check("logic.conflicts-mg-order",
                WeavePriority.conflicts(Set.of(TAB, SHARED)).equals(List.of(SHARED, TAB)),
                "交集按 MG 目标表顺序: " + WeavePriority.conflicts(Set.of(TAB, SHARED)));

        // 静态快照：防御性拷贝 + 生效 + 可复原
        Set<String> mutable = new HashSet<>();
        mutable.add(TAB);
        WeavePriority.setModMixinTargets(mutable);
        mutable.add(SHARED);
        check("logic.snapshot-defensive-copy", WeavePriority.modMixinTargets().size() == 1,
                "灌入后修改调用方集合不得影响快照: " + WeavePriority.modMixinTargets());
        check("logic.snapshot-affects-decide", !WeavePriority.decide(TAB).applyMgWeaving,
                "快照里命中 TAB 后，单参 decide 必须让路");
        WeavePriority.setModMixinTargets(null);
        check("logic.snapshot-reset", WeavePriority.modMixinTargets().isEmpty()
                        && WeavePriority.decide(TAB).applyMgWeaving,
                "清空快照后恢复不让路");
    }

    // ---------------------------------------------------------------- collect

    private static void collect() throws IOException {
        Map<String, byte[]> res = new HashMap<>();
        // 正向：三个数组都覆盖（mixins 用类字面量，client 用字符串 targets）
        res.put("probe-conflict.mixins.json", utf8("{\"package\":\"orderfixture\","
                + "\"mixins\":[\"OrderTabMixin\"],\"client\":[\"OrderItemMixin\"]}"));
        res.put("orderfixture/OrderTabMixin.class", readOwn("orderfixture/OrderTabMixin.class"));
        res.put("orderfixture/OrderItemMixin.class", readOwn("orderfixture/OrderItemMixin.class"));
        res.put("orderfixture/OrderPackMixin.class", readOwn("orderfixture/OrderPackMixin.class"));
        // MG 自有 config：必须被排除（否则 MG 自研织入会被自己的 mixin 目标挤掉）
        res.put("mirage.mixins.json", utf8("{\"package\":\"orderfixture\",\"mixins\":[\"OrderPackMixin\"]}"));
        // 坏 JSON 与缺失 mixin 类：只降级，不中断整轮
        res.put("broken.mixins.json", utf8("{ this is not json"));
        res.put("missing.mixins.json", utf8("{\"package\":\"orderfixture\",\"mixins\":[\"NoSuchMixin\"]}"));

        Set<String> targets = ModMixinTargets.collect(res::get, List.of(
                "probe-conflict.mixins.json", "mirage.mixins.json", "broken.mixins.json",
                "missing.mixins.json", "absent.mixins.json"));

        System.out.println("COLLECTED=" + targets);
        check("collect.class-literal", targets.contains(TAB), "@Mixin(value=Class) → 目标进集合: " + targets);
        check("collect.targets-string", targets.contains(ITEM), "@Mixin(targets=\"...\") → 目标进集合: " + targets);
        check("collect.client-array", targets.contains(ITEM), "client 数组同样被扫描: " + targets);
        check("collect.self-config-ignored", !targets.contains(PACK),
                "mirage.mixins.json 的 mixin 目标不得进 mod 目标集合（否则自噬）: " + targets);
        check("collect.broken-json-tolerated", targets.size() == 2,
                "坏 JSON / 缺类 / 缺 config 只降级，正例仍收集到 2 个目标: " + targets);

        // 包前缀补全（相对名 vs 已带前缀名 vs 斜杠分隔）
        List<String> names = ModMixinTargets.mixinClassNames(
                "{\"package\":\"a.b\",\"mixins\":[\"C\",\"a.b.D\",\"e/F\"],\"client\":[\"G\"],\"server\":[]}");
        check("collect.package-prefix",
                names.equals(List.of("a.b.C", "a.b.D", "a.b.e.F", "a.b.G")),
                "包前缀补全 + 斜杠转点后应保持输入顺序: " + names);
        check("collect.no-package",
                ModMixinTargets.mixinClassNames("{\"mixins\":[\"x.Y\"]}").equals(List.of("x.Y")),
                "无 package 字段时原样保留");
        check("collect.own-config-matcher",
                ModMixinTargets.isMgOwnConfig("mirage.mixins.json")
                        && ModMixinTargets.isMgOwnConfig("sub/mirage.mixins.json")
                        && !ModMixinTargets.isMgOwnConfig("other.mixins.json"),
                "MG 自有 config 判定（含子目录形态）");

        // 注解里没有目标时不瞎猜
        check("collect.empty-mixin", ModMixinTargets.targetsOf(readOwn("orderfixture/OrderProbeMain.class")).isEmpty(),
                "无 @Mixin 注解的类应返回空集");
    }

    // -------------------------------------------------------------- endtoend

    /**
     * @param args [0]=endtoend [1]=config 资源名 [2]=期望冲突(yes/no) [3]=26.2.jar [4]=brigadier
     *             [5]=fastutil [6]=夹具目录（config 与 mixin 类的资源根）
     */
    private static void endToEnd(String[] args) throws Exception {
        String config = args[1];
        boolean expectConflict = "yes".equalsIgnoreCase(args[2]);
        List<URL> urls = new ArrayList<>();
        for (int i = 3; i <= 6; i++) {
            urls.add(new java.io.File(args[i]).toURI().toURL());
        }
        MirageClassLoader mcl = new MirageClassLoader(urls.toArray(new URL[0]),
                ClassLoader.getPlatformClassLoader());

        PrintStream oldErr = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        boolean hasBuilder0;
        Set<String> collected;
        String errText;
        try {
            System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
            // 真生产链路：init 内部会收集 mod @Mixin 目标并灌进 WeavePriority
            io.mirage.mixin.MirageMixinBootstrap.init(mcl, List.of(config));
            collected = WeavePriority.modMixinTargets();
            Class<?> tab = mcl.loadClass(TAB);
            hasBuilder0 = false;
            for (Method m : tab.getDeclaredMethods()) {
                if ("builder".equals(m.getName()) && m.getParameterCount() == 0) {
                    hasBuilder0 = true;
                }
            }
        } finally {
            System.setErr(oldErr);
            errText = captured.toString(StandardCharsets.UTF_8);
        }

        System.out.println("CONFIG=" + config);
        System.out.println("COLLECTED=" + collected);
        System.out.println("BUILDER_0ARG=" + (hasBuilder0 ? "yes" : "no"));
        System.out.println("WARN_LET_WAY=" + (errText.contains("让路") ? "yes" : "no"));
        System.out.println("ERR_LEN=" + errText.length());
        if (fail != 0) {
            System.out.println("---- captured stderr ----");
            System.out.println(errText);
        }

        check("e2e.collected-nonempty", !collected.isEmpty(), "mod 目标集合不该为空: " + collected);
        check("e2e.mg-weaving-" + (expectConflict ? "skipped" : "applied"),
                hasBuilder0 == !expectConflict,
                (expectConflict ? "冲突 ⇒ MG 无参 builder() 不该存在" : "无冲突 ⇒ MG 无参 builder() 必须存在")
                        + "，实际 BUILDER_0ARG=" + hasBuilder0);
        check("e2e.warn-" + (expectConflict ? "present" : "absent"),
                errText.contains("让路") == expectConflict,
                (expectConflict ? "让路必须有 WARN（禁止无声放弃）" : "无冲突不该出现让路 WARN")
                        + "，WARN_LET_WAY=" + errText.contains("让路"));
        if (expectConflict) {
            check("e2e.warn-names-class", errText.contains(TAB), "让路 WARN 必须点名 " + TAB);
            check("e2e.warn-names-purpose", errText.contains(WeavePriority.mgTargets().get(TAB)),
                    "让路 WARN 必须说明放弃的功能: " + WeavePriority.mgTargets().get(TAB));
        }
    }

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] readOwn(String resource) throws IOException {
        try (InputStream in = OrderProbeMain.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IOException("资源缺失: " + resource);
            }
            return in.readAllBytes();
        }
    }
}
