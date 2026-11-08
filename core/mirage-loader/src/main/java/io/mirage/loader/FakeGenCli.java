package io.mirage.loader;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * P4 生成器的命令行入口（构建期工具，不进运行时路径）。
 *
 * <p>用法：
 * <pre>
 * java -cp &lt;loader classes&gt;:&lt;asm&gt; io.mirage.loader.FakeGenCli \
 *      --ref &lt;compat 编译产物目录&gt; --out &lt;fake pack 目录&gt; \
 *      [--shape &lt;neoforge-universal.jar&gt;] [--report &lt;json&gt;] [--verify] \
 *      &lt;mod jar&gt; [mod jar ...]
 * </pre>
 *
 * <p>{@code --verify} 会用「参考 classpath + 刚生成的 pack」重新算一遍缺口，
 * 要求 {@code net.neoforged.*} 缺口归零。**这是 P4 的验收判据**：
 * 只打印生成了多少个类没有意义，必须证明「补齐后缺口真的消失」。
 *
 * <p>退出码：0 通过；1 参数/IO 错；2 生成失败或 {@code --verify} 未归零。
 */
public final class FakeGenCli {

    private FakeGenCli() {}

    public static void main(String[] args) {
        try {
            int code = run(args);
            System.exit(code);
        } catch (Throwable t) {
            System.out.println("[P4][FAIL] " + t);
            t.printStackTrace(System.out);
            System.exit(1);
        }
    }

    private static int run(String[] args) throws IOException {
        List<Path> refs = new ArrayList<>();
        List<Path> shapeSrc = new ArrayList<>();
        List<Path> mods = new ArrayList<>();
        Path out = null;
        Path report = null;
        boolean verify = false;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--ref" -> refs.add(Paths.get(args[++i]));
                case "--shape" -> shapeSrc.addAll(FakeStubGenerator.FileShapeSource.expand(Paths.get(args[++i])));
                case "--out" -> out = Paths.get(args[++i]);
                case "--report" -> report = Paths.get(args[++i]);
                case "--verify" -> verify = true;
                case "-h", "--help" -> {
                    System.out.println("用法: --ref <dir|jar> --out <dir> [--shape <jar|dir>] "
                            + "[--report <json>] [--verify] <mod jar>...");
                    return 0;
                }
                default -> mods.add(Paths.get(args[i]));
            }
        }
        if (refs.isEmpty() || out == null || mods.isEmpty()) {
            System.out.println("[P4][FAIL] 缺少必要参数（--ref / --out / mod jar）");
            return 1;
        }

        // ---- 参考 classpath（compat 编译产物）----
        URL[] refUrls = new URL[refs.size()];
        for (int i = 0; i < refs.size(); i++) refUrls[i] = refs.get(i).toUri().toURL();
        ClassLoader reference = new URLClassLoader(refUrls, ClassLoader.getPlatformClassLoader());

        Set<String> missing = new TreeSet<>();
        Map<String, List<FakeGapAnalyzer.Member>> uses = new LinkedHashMap<>();
        List<FakeGapAnalyzer.MemberGap> gaps = new ArrayList<>();
        for (Path mod : mods) {
            FakeGapAnalyzer.MemberGap g = FakeGapAnalyzer.analyzeMembers(mod, reference);
            if (g == null) {
                System.out.println("[P4] " + mod.getFileName() + ": 无 fake 域引用，跳过");
                continue;
            }
            gaps.add(g);
            missing.addAll(g.missing());
            mergeUses(uses, g.memberUses());
            System.out.println("[P4] " + mod.getFileName() + " ref=" + g.referenced().size()
                    + " miss=" + g.missing().size()
                    + " (neo=" + countPrefix(g.missing(), FakeStubGenerator.GENERATED_PREFIX)
                    + " mc=" + countPrefix(g.missing(), "net.minecraft.") + ")"
                    + " cov=" + String.format("%.1f%%", g.coverage() * 100));
        }
        if (gaps.isEmpty()) {
            System.out.println("[P4][FAIL] 没有可分析的 mod");
            return 1;
        }

        // ---- 生成 ----
        long t0 = System.currentTimeMillis();
        FakeStubGenerator.Result res;
        try (FakeStubGenerator.FileShapeSource shapes =
                     shapeSrc.isEmpty() ? null : new FakeStubGenerator.FileShapeSource(shapeSrc)) {
            res = FakeStubGenerator.generate(missing, uses, shapes, out);
        }
        long ms = System.currentTimeMillis() - t0;
        System.out.println("[P4] generated=" + res.generated().size()
                + " skipped=" + res.skipped().size()
                + " failed=" + res.failed().size()
                + " bytes=" + res.bytes() + " (" + ms + " ms, shape=" + shapeSrc.size() + ")");
        for (String f : res.failed()) System.out.println("[P4][FAIL] 生成失败: " + f);

        // ---- 验收：补齐后 neo 缺口必须归零 ----
        int verifyFails = 0;
        List<String> afterLines = new ArrayList<>();
        if (verify) {
            List<Path> all = new ArrayList<>(refs);
            all.add(out);
            URL[] allUrls = new URL[all.size()];
            for (int i = 0; i < all.size(); i++) allUrls[i] = all.get(i).toUri().toURL();
            ClassLoader withPack = new URLClassLoader(allUrls, ClassLoader.getPlatformClassLoader());
            for (Path mod : mods) {
                FakeGapAnalyzer.MemberGap before = null;
                for (FakeGapAnalyzer.MemberGap g : gaps) {
                    if (g.modId().equals(mod.getFileName().toString())) before = g;
                }
                FakeGapAnalyzer.MemberGap after = FakeGapAnalyzer.analyzeMembers(mod, withPack);
                int beforeNeo = before == null ? 0
                        : countPrefix(before.missing(), FakeStubGenerator.GENERATED_PREFIX);
                int afterNeo = after == null ? 0
                        : countPrefix(after.missing(), FakeStubGenerator.GENERATED_PREFIX);
                String line = mod.getFileName() + " neoMissing " + beforeNeo + " -> " + afterNeo
                        + "（剩余 mc=" + (after == null ? 0
                        : countPrefix(after.missing(), "net.minecraft.")) + "）";
                System.out.println("[P4] verify " + line);
                afterLines.add(line);
                if (afterNeo != 0) verifyFails++;
            }
        }

        if (report != null) {
            writeReport(report, refs, out, shapeSrc, gaps, res, afterLines);
            System.out.println("[P4] 报告: " + report.toAbsolutePath());
        }
        if (!res.failed().isEmpty()) return 2;
        return verifyFails == 0 ? 0 : 2;
    }

    private static void mergeUses(Map<String, List<FakeGapAnalyzer.Member>> into,
                                  Map<String, List<FakeGapAnalyzer.Member>> from) {
        for (Map.Entry<String, List<FakeGapAnalyzer.Member>> en : from.entrySet()) {
            Set<FakeGapAnalyzer.Member> set = new LinkedHashSet<>(into.getOrDefault(en.getKey(), List.of()));
            set.addAll(en.getValue());
            into.put(en.getKey(), List.copyOf(new TreeSet<>(set)));
        }
    }

    private static int countPrefix(List<String> names, String prefix) {
        int n = 0;
        for (String s : names) if (s.startsWith(prefix)) n++;
        return n;
    }

    private static void writeReport(Path file, List<Path> refs, Path out, List<Path> shapes,
                                    List<FakeGapAnalyzer.MemberGap> gaps,
                                    FakeStubGenerator.Result res, List<String> verifyLines)
            throws IOException {
        StringBuilder sb = new StringBuilder(4096);
        sb.append("{\n  \"schema\": \"mirage.msr.fakepack/1\",\n");
        sb.append("  \"ref\": ").append(str(refs.toString())).append(",\n");
        sb.append("  \"out\": ").append(str(out.toAbsolutePath().toString())).append(",\n");
        sb.append("  \"shapes\": ").append(str(shapes.toString())).append(",\n");
        sb.append("  \"mods\": [\n");
        for (int i = 0; i < gaps.size(); i++) {
            FakeGapAnalyzer.MemberGap g = gaps.get(i);
            sb.append("    {\"jar\": ").append(str(g.modId()))
              .append(", \"referenced\": ").append(g.referenced().size())
              .append(", \"missing\": ").append(g.missing().size())
              .append(", \"missingNeo\": ").append(countPrefix(g.missing(), FakeStubGenerator.GENERATED_PREFIX))
              .append(", \"missingMc\": ").append(countPrefix(g.missing(), "net.minecraft."))
              .append(", \"classes\": ").append(g.classCount())
              .append(", \"coverage\": ").append(String.format("%.4f", g.coverage()))
              .append('}');
            sb.append(i + 1 < gaps.size() ? ",\n" : "\n");
        }
        sb.append("  ],\n");
        sb.append("  \"generated\": ").append(arr(res.generated())).append(",\n");
        sb.append("  \"skipped\": ").append(arr(res.skipped())).append(",\n");
        sb.append("  \"failed\": ").append(arr(res.failed())).append(",\n");
        sb.append("  \"bytes\": ").append(res.bytes()).append(",\n");
        sb.append("  \"verify\": ").append(arr(verifyLines)).append("\n");
        sb.append("}\n");
        Files.createDirectories(file.toAbsolutePath().getParent());
        Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
    }

    private static String arr(List<String> items) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(str(items.get(i)));
        }
        return sb.append(']').toString();
    }

    /** 最小 JSON 字符串转义（够报告用，不引依赖）。 */
    private static String str(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 2).append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.append('"').toString();
    }
}
