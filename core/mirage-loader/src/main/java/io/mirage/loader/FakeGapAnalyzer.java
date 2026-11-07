package io.mirage.loader;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * fake 缺口分析器（MSR P4，2026-11-07）。
 *
 * <p><b>要解决的问题</b>：MSR 的 fake 表面只有 19 个手工枚举的类，喂真实 NeoForge mod 时
 * <b>100% 失败</b>（实测：JEI 缺 81 个 {@code net/neoforged/*}、Jade 缺
 * {@code net.minecraft.core.HolderLookup$Provider}，产出 {@code apis=0}）。
 * 手工补 fake 类是「打地鼠」：mod 一多就补不完。
 *
 * <p><b>本类做什么</b>：离线读 mod jar 的 class 常量池，抽出它引用的
 * {@code net.neoforged.*} / {@code net.minecraft.*} 类，与「fake 覆盖域」求差集，
 * 输出一份<b>可数的缺口清单</b>。
 *
 * <p><b>关键设计：一份数据两处用</b>
 * <ul>
 *   <li>① 给 MSR：缺口清单 = 需要补哪些 fake/stub；</li>
 *   <li>② 给链接期（N1）：缺口清单 = 兼容层要补哪些 stub。</li>
 * </ul>
 * 两者本就在补同一批类，所以不该有两套清单。
 *
 * <p><b>方法论（与 {@code scripts/verify_neo_api_surface.ps1} 同源，但输出更适合生成器消费）</b>：
 * 读 class 常量池里的 {@code net/neoforged/...}、{@code net/minecraft/...} 字符串，
 * 与参考 classpath（compat 模块编译产物）里的实际类做差集。
 *
 * <p><b>边界（如实说明）</b>：常量池引用 ≠ 全部立即解析（JVM 懒解析），
 * 只有执行到的路径才炸。所以清单是<b>上界</b>而非精确集合；但对「主类构造 +
 * {@code @SubscribeEvent} 方法签名扫描」这些会立刻触发解析的路径，它是准确的下界。
 */
public final class FakeGapAnalyzer {

    /** 分析结果。 */
    public record Gap(String modId, List<String> referenced, List<String> missing,
                      double coverage, int classCount) {
        /** 缺口占引用数的比例（0 = 全覆盖，1 = 全缺）。 */
        public double missRatio() {
            return referenced.isEmpty() ? 0.0 : (double) missing.size() / referenced.size();
        }
    }

    /** fake 覆盖域：只有这两片参与分析。 */
    private static final String[] FAKE_DOMAINS = { "net/neoforged/", "net/minecraft/" };

    /** 字段/方法描述符里的对象类型段（形如 {@code Lnet/neoforged/x/Y;}）。 */
    private static final Pattern OBJ_TYPE = Pattern.compile("L([^;]+);");

    private FakeGapAnalyzer() {}

    /**
     * 分析一个 mod jar 的 fake 缺口。
     *
     * @param jar        mod jar 路径
     * @param reference  参考 classpath（compat 模块编译产物目录或 fat jar）
     * @return 分析结果；不是 NeoForge mod 时返回 {@code null}
     */
    public static Gap analyze(Path jar, ClassLoader reference) throws IOException {
        Set<String> refs = new LinkedHashSet<>();
        int classCount = 0;
        try (JarFile jf = new JarFile(jar.toFile())) {
            Enumeration<JarEntry> entries = jf.entries();
            while (entries.hasMoreElements()) {
                JarEntry e = entries.nextElement();
                if (e.isDirectory() || !e.getName().endsWith(".class")) continue;
                classCount++;
                try (InputStream in = jf.getInputStream(e)) {
                    collectRefs(new ClassReader(in), refs);
                } catch (Throwable ignored) {
                    // 单个 class 读不出来不影响整体分析
                }
            }
        }
        if (refs.isEmpty()) return null;

        List<String> referenced = new ArrayList<>(new TreeSet<>(refs));
        List<String> missing = new ArrayList<>();
        for (String r : referenced) {
            String cls = r.replace('/', '.');
            if (!existsIn(reference, cls)) missing.add(cls);
        }
        double cov = referenced.isEmpty() ? 1.0
                : (double) (referenced.size() - missing.size()) / referenced.size();
        return new Gap(jar.getFileName().toString(), referenced, missing, cov, classCount);
    }

    /** 参考 classpath 里是否有这个类（只看 fake 覆盖域的两片）。 */
    private static boolean existsIn(ClassLoader reference, String className) {
        if (reference == null) return false;
        String path = className.replace('.', '/') + ".class";
        try (InputStream in = reference.getResourceAsStream(path)) {
            return in != null;
        } catch (Exception e) {
            return false;
        }
    }

    /** 扫一个 class 的常量池，收集它引用的 fake 域类名。 */
    private static void collectRefs(ClassReader cr, Set<String> out) {
        cr.accept(new ClassVisitor(Opcodes.ASM9) {
            // ASM 9 的入口是 visit(...)（没有 visitClass），签名是
            // (version, access, name, signature, superName, interfaces)
            @Override
            public void visit(int version, int access, String name, String signature,
                             String superName, String[] interfaces) {
                addIfFakeDomain(name, out);
                addIfFakeDomain(superName, out);
                if (interfaces != null) {
                    for (String i : interfaces) addIfFakeDomain(i, out);
                }
            }

            @Override
            public FieldVisitor visitField(int access, String name, String desc, String signature,
                                           Object value) {
                addIfFakeDomain(desc, out);
                return null;
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String signature,
                                             String[] exceptions) {
                addIfFakeDomain(desc, out);
                if (exceptions != null) {
                    for (String e : exceptions) addIfFakeDomain(e, out);
                }
                return null;
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
    }

    /**
     * 只认形如 {@code net/neoforged/x/Y} 的描述符/类名片段。
     *
     * <p><b>坑</b>：方法描述符 {@code (Lnet/neoforged/a/B;Lnet/neoforged/c/D;)V} 里
     * 有<b>多个</b>对象类型段。按第一个 {@code ;} 截断会漏掉后面的参数类型，
     * 所以这里改成正则逐段抽取。
     */
    private static void addIfFakeDomain(String s, Set<String> out) {
        if (s == null || s.isEmpty()) return;
        // ① 裸内部名（visit 的 name / superName / interfaces / exceptions）
        for (String d : FAKE_DOMAINS) {
            if (s.startsWith(d)) {
                out.add(s);
                return;
            }
        }
        // ② 描述符里的对象类型段
        Matcher m = OBJ_TYPE.matcher(s);
        while (m.find()) {
            String seg = m.group(1);
            for (String d : FAKE_DOMAINS) {
                if (seg.startsWith(d)) {
                    out.add(seg);
                    break;
                }
            }
        }
    }

    /** 缺口清单的可读文本（写进文档用）。 */
    public static String render(Gap g) {
        StringBuilder sb = new StringBuilder();
        sb.append("== ").append(g.modId()).append('\n');
        sb.append("  引用 fake 域类: ").append(g.referenced().size())
          .append("  缺失: ").append(g.missing().size())
          .append("  覆盖率: ").append(String.format("%.1f%%", g.coverage() * 100)).append('\n');
        for (String m : g.missing()) sb.append("  [缺] ").append(m).append('\n');
        return sb.toString();
    }

    /** 排序（按缺失数降序，便于看谁最急）。 */
    public static List<Gap> sortByGap(List<Gap> gaps) {
        List<Gap> copy = new ArrayList<>(gaps);
        copy.sort((a, b) -> Integer.compare(b.missing().size(), a.missing().size()));
        return Collections.unmodifiableList(copy);
    }
}
