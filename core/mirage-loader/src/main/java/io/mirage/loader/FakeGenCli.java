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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

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
 * 要求 {@code net.neoforged.*} 缺口归零（全量模式），或白名单里的每个类都可解析（按需模式）。
 * <b>这是 P4 的验收判据</b>：只打印生成了多少个类没有意义，必须证明「补齐后缺口真的消失」。
 *
 * <p><b>按需模式（MSR P4b，2026-11-11）</b>：默认是「全缺口一把梭」——把 mod 字节码里所有
 * 缺失的 {@code net.neoforged.*} 类全生成。但真实价值在「按 P3 录到的 MID 故障清单驱动」：
 * 只生成那些录制时真的以 {@code missing_fake} 倒下的类，避免把一堆 mod 根本用不到的空架子
 * 塞进 classpath。两种入口：
 * <ul>
 *   <li>{@code --only-file <list.txt>}：每行一个类（点分或内部名，{@code #} 注释），
 *       生成器只生成白名单里的类；</li>
 *   <li>{@code --from-mid <midCacheDir>}：直接读 {@code .mid-cache} 的 JSON，从
 *       {@code recorded.error == "missing_fake: NoClassDefFoundError: net/neoforged/x/Y"}
 *       抽出缺类名当白名单（只认 {@code net.neoforged.*}，{@code net.minecraft.*} 不生成）。</li>
 * </ul>
 * 两者可叠加。当白名单非空时，{@code --verify} 的判据改为「白名单里每个 neo 类都可在
 * pack 里解析到」，不再要求全量 neo 缺口归零。
 *
 * <p>退出码：0 通过；1 参数/IO 错；2 生成失败或 {@code --verify} 未通过。
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
        Path onlyFile = null;
        Path fromMid = null;
        Path emitMissing = null;
        boolean verify = false;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--ref" -> refs.add(Paths.get(args[++i]));
                case "--shape" -> shapeSrc.addAll(FakeStubGenerator.FileShapeSource.expand(Paths.get(args[++i])));
                case "--out" -> out = Paths.get(args[++i]);
                case "--report" -> report = Paths.get(args[++i]);
                case "--only-file" -> onlyFile = Paths.get(args[++i]);
                case "--from-mid" -> fromMid = Paths.get(args[++i]);
                case "--emit-missing" -> emitMissing = Paths.get(args[++i]);
                case "--verify" -> verify = true;
                case "-h", "--help" -> {
                    System.out.println("用法: --ref <dir|jar> --out <dir> [--shape <jar|dir>] "
                            + "[--only-file <list.txt>] [--from-mid <midCacheDir>] "
                            + "[--emit-missing <file>] [--report <json>] [--verify] <mod jar>...");
                    return 0;
                }
                default -> mods.add(Paths.get(args[i]));
            }
        }
        if (refs.isEmpty() || out == null) {
            System.out.println("[P4][FAIL] 缺少必要参数（--ref / --out）");
            return 1;
        }

        // 白名单：--only-file 与 --from-mid 取并集
        Set<String> whitelist = new TreeSet<>();
        if (onlyFile != null) {
            for (String l : Files.readAllLines(onlyFile, StandardCharsets.UTF_8)) {
                l = l.trim();
                if (l.isEmpty() || l.startsWith("#")) continue;
                whitelist.add(l.replace('/', '.'));
            }
        }
        if (fromMid != null) {
            whitelist.addAll(extractMissingFromMid(fromMid));
        }
        boolean onDemand = !whitelist.isEmpty();

        if (mods.isEmpty() && !onDemand) {
            System.out.println("[P4][FAIL] 没有 mod，也没有白名单（--only-file/--from-mid），无事可做");
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

        // --emit-missing：把（白名单过滤前的）neo 缺口类名落盘，供上层组装 mid-cache 或直接消费
        if (emitMissing != null) {
            List<String> neo = new ArrayList<>();
            for (String m : missing) if (m.startsWith(FakeStubGenerator.GENERATED_PREFIX)) neo.add(m);
            neo.sort(null);
            Files.createDirectories(emitMissing.toAbsolutePath().getParent());
            Files.writeString(emitMissing, String.join("\n", neo) + (neo.isEmpty() ? "" : "\n"),
                    StandardCharsets.UTF_8);
            System.out.println("[P4] emit-missing " + neo.size() + " 个 neo 缺口 -> " + emitMissing);
        }

        // 按需：只<u>缩窄「要生成的类」</u>，绝不裁掉 missing 里的 mc 缺口——
        // 完整缺口集合要留给 FakeStubGenerator.resolveSuper 判定「缺失的 mc 超类回落 Object」。
        // （早期版本在调用方先 missing.retainAll(whitelist)，导致按需模式下 net.minecraft.*
        //  超类被写进 superName，生成物链接期 CNFE，且 --verify 只查资源存在而漏检。）
        if (onDemand) {
            int hit = 0;
            for (String w : whitelist) {
                if (w.startsWith(FakeStubGenerator.GENERATED_PREFIX) && missing.contains(w)) hit++;
            }
            if (hit == 0) {
                System.out.println("[P4] 白名单过滤后无待生成类，跳过（whitelist=" + whitelist.size() + "）");
                return 0;
            }
            System.out.println("[P4] 按需模式：白名单 " + whitelist.size()
                    + " 类，命中待生成 " + hit + " 类");
        }

        if (gaps.isEmpty() && !onDemand) {
            System.out.println("[P4][FAIL] 没有可分析的 mod");
            return 1;
        }

        // ---- 生成 ----
        long t0 = System.currentTimeMillis();
        FakeStubGenerator.Result res;
        try (FakeStubGenerator.FileShapeSource shapes =
                     shapeSrc.isEmpty() ? null : new FakeStubGenerator.FileShapeSource(shapeSrc)) {
            res = FakeStubGenerator.generate(missing, onDemand ? whitelist : null, uses, shapes, out);
        }
        long ms = System.currentTimeMillis() - t0;
        System.out.println("[P4] generated=" + res.generated().size()
                + " skipped=" + res.skipped().size()
                + " failed=" + res.failed().size()
                + " bytes=" + res.bytes() + " (" + ms + " ms, shape=" + shapeSrc.size() + ")"
                + (onDemand ? ", on-demand" : ""));
        for (String f : res.failed()) System.out.println("[P4][FAIL] 生成失败: " + f);

        // ---- 验收 ----
        int verifyFails = 0;
        List<String> afterLines = new ArrayList<>();
        if (verify) {
            List<Path> all = new ArrayList<>(refs);
            all.add(out);
            URL[] allUrls = new URL[all.size()];
            for (int i = 0; i < all.size(); i++) allUrls[i] = all.get(i).toUri().toURL();
            ClassLoader withPack = new URLClassLoader(allUrls, ClassLoader.getPlatformClassLoader());

            if (onDemand) {
                // 白名单模式：每个 neo 缺类都能在 pack 里解析到
                for (String c : whitelist) {
                    if (!c.startsWith(FakeStubGenerator.GENERATED_PREFIX)) continue;
                    boolean ok = existsIn(withPack, c);
                    System.out.println("[P4] verify " + (ok ? "OK  " : "MISS") + " " + c);
                    afterLines.add((ok ? "OK   " : "MISS ") + c);
                    if (!ok) verifyFails++;
                }
            } else {
                // 全量模式：逐 mod 的 neo 缺口必须归零
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
        }

        if (report != null) {
            writeReport(report, refs, out, shapeSrc, gaps, res, afterLines, onDemand);
            System.out.println("[P4] 报告: " + report.toAbsolutePath());
        }
        if (!res.failed().isEmpty()) return 2;
        return verifyFails == 0 ? 0 : 2;
    }

    /**
     * 从 {@code .mid-cache} 目录抽「录制时以 missing_fake 倒下的 neo 类」。
     *
     * <p>录制端 {@link io.mirage.loader.sandbox.SandboxRecorder#classifyFailure} 把
     * 缺 fake 域类的失败归成 {@code missing_fake: NoClassDefFoundError: net/neoforged/x/Y <- ...}。
     * 这里用正则从 JSON 的 {@code "error"} 字段里抓出类名，归一化成点分，过滤掉
     * {@code net.minecraft.*}（那片不生成）。不引 Gson——缓存 JSON 结构简单，正则足够。
     */
    private static Set<String> extractMissingFromMid(Path dir) throws IOException {
        Set<String> out = new TreeSet<>();
        if (!Files.isDirectory(dir)) {
            System.out.println("[P4][WARN] --from-mid 目录不存在: " + dir);
            return out;
        }
        Pattern errField = Pattern.compile("\"error\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
        Pattern missingFake = Pattern.compile(
                "missing_fake:\\s*(?:NoClassDefFoundError|ClassNotFoundException):\\s*(\\S+)");
        List<Path> files;
        try (Stream<Path> s = Files.list(dir)) {
            files = s.filter(x -> x.toString().endsWith(".json")).sorted().toList();
        }
        for (Path f : files) {
            String text = Files.readString(f, StandardCharsets.UTF_8);
            Matcher em = errField.matcher(text);
            while (em.find()) {
                String errVal = em.group(1).replace("\\\"", "\"");
                Matcher mm = missingFake.matcher(errVal);
                if (!mm.find()) continue;
                String cls = mm.group(1).replace('/', '.').trim();
                int sp = cls.indexOf(' ');
                if (sp > 0) cls = cls.substring(0, sp);
                if (cls.startsWith(FakeStubGenerator.GENERATED_PREFIX)) out.add(cls);
            }
        }
        return out;
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

    private static boolean existsIn(ClassLoader reference, String className) {
        if (reference == null) return false;
        String path = className.replace('.', '/') + ".class";
        try (java.io.InputStream in = reference.getResourceAsStream(path)) {
            return in != null;
        } catch (Exception e) {
            return false;
        }
    }

    private static void writeReport(Path file, List<Path> refs, Path out, List<Path> shapes,
                                    List<FakeGapAnalyzer.MemberGap> gaps,
                                    FakeStubGenerator.Result res, List<String> verifyLines,
                                    boolean onDemand) throws IOException {
        StringBuilder sb = new StringBuilder(4096);
        sb.append("{\n  \"schema\": \"mirage.msr.fakepack/1\",\n");
        sb.append("  \"mode\": ").append(str(onDemand ? "on-demand" : "full")).append(",\n");
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
