package io.mirage.loader;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * fake 缺口分析器（MSR P4，2026-11-07 起；2026-11-08 扩到成员级）。
 *
 * <p><b>要解决的问题</b>：MSR 的 fake 表面只有 19 个手工枚举的类，喂真实 NeoForge mod 时
 * <b>100% 失败</b>（实测：JEI 缺 81 个 {@code net/neoforged/*}、Jade 缺
 * {@code net.minecraft.core.HolderLookup$Provider}，产出 {@code apis=0}）。
 * 手工补 fake 类是「打地鼠」：mod 一多就补不完。
 *
 * <p><b>本类做什么</b>：离线读 mod jar 的字节码，抽出它引用的
 * {@code net.neoforged.*} / {@code net.minecraft.*} 类（<b>类级</b>），以及它在 code 里
 * 真的用到的<b>字段与方法</b>（成员级），与「fake 覆盖域」求差集，输出缺口。
 *
 * <p><b>关键设计：一份数据两处用</b>
 * <ul>
 *   <li>① 给 MSR：缺口清单 = 需要补哪些 fake/stub，成员级数据直接喂
 *       {@link FakeStubGenerator} 生成骨架类；</li>
 *   <li>② 给链接期（N1）：缺口清单 = 兼容层要补哪些 stub。</li>
 * </ul>
 * 两者本就在补同一批类，所以不该有两套清单。
 *
 * <p><b>方法论（与 {@code scripts/verify_neo_api_surface.ps1} 同源，但输出更适合生成器消费）</b>：
 * 读常量池与 code 里的 owner/描述符，与参考 classpath（compat 模块编译产物）里的实际类做差集。
 *
 * <p><b>为什么成员级必须扫 code</b>：类级引用只能回答「缺哪个类」，生成器还需要知道
 * 「这个类要有什么字段/方法、是 static 还是实例、owner 是不是接口」——只从描述符拿不到这些，
 * 必须看 {@code visitFieldInsn} / {@code visitMethodInsn} 的 opcode 与 owner。漏了这几项，
 * 生成出来的 stub 会在链接期抛 {@code IncompatibleClassChangeError} 或
 * {@code NoSuchMethodError}，比原来的 CNFE 更难查。
 *
 * <p><b>边界（如实说明）</b>：常量池引用 ≠ 全部立即解析（JVM 懒解析），
 * 只有执行到的路径才炸。所以清单是<b>上界</b>而非精确集合；但对「主类构造 +
 * {@code @SubscribeEvent} 方法签名扫描」这些会立刻触发解析的路径，它是准确的下界。
 */
public final class FakeGapAnalyzer {

    /** 缺口里的成员种类。 */
    public enum Kind { METHOD, FIELD }

    /**
     * mod 代码里对某个成员的引用。
     *
     * @param kind      方法还是字段
     * @param name      成员名（方法含 {@code <init>}）
     * @param desc      描述符（方法形如 {@code (Lnet/neoforged/api/distmarker/Dist;)V}）
     * @param isStatic  引用点用的是 {@code INVOKESTATIC}/{@code GETSTATIC}/{@code PUTSTATIC}
     * @param ownerItf  调用点把 owner 当成接口（{@code INVOKEINTERFACE} 或 itf=true）
     */
    public record Member(Kind kind, String name, String desc, boolean isStatic, boolean ownerItf)
            implements Comparable<Member> {
        @Override
        public int compareTo(Member o) {
            int c = kind.compareTo(o.kind);
            if (c != 0) return c;
            c = name.compareTo(o.name);
            if (c != 0) return c;
            c = desc.compareTo(o.desc);
            if (c != 0) return c;
            c = Boolean.compare(isStatic, o.isStatic);
            return c != 0 ? c : Boolean.compare(ownerItf, o.ownerItf);
        }

        /** 用于报告的一行文本。 */
        public String render() {
            return (kind == Kind.METHOD ? "方法 " : "字段 ")
                    + (isStatic ? "static " : "") + name + desc;
        }
    }

    /** 分析结果（类级，保持 P4 初版签名不变）。 */
    public record Gap(String modId, List<String> referenced, List<String> missing,
                      double coverage, int classCount) {
        /** 缺口占引用数的比例（0 = 全覆盖，1 = 全缺）。 */
        public double missRatio() {
            return referenced.isEmpty() ? 0.0 : (double) missing.size() / referenced.size();
        }
    }

    /**
     * 分析结果（成员级，2026-11-08）。
     *
     * @param memberUses 仅包含<b>缺失类</b>的成员引用：类名（点分）→ 该 mod 用到的成员集合
     */
    public record MemberGap(String modId, List<String> referenced, List<String> missing,
                            Map<String, List<Member>> memberUses,
                            double coverage, int classCount) {
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
     * 分析一个 mod jar 的 fake 缺口（类级）。
     *
     * @param jar        mod jar 路径
     * @param reference  参考 classpath（compat 模块编译产物目录或 fat jar）
     * @return 分析结果；不是 NeoForge mod 时返回 {@code null}
     */
    public static Gap analyze(Path jar, ClassLoader reference) throws IOException {
        MemberGap mg = analyzeMembers(jar, reference);
        if (mg == null) return null;
        return new Gap(mg.modId(), mg.referenced(), mg.missing(), mg.coverage(), mg.classCount());
    }

    /**
     * 分析一个 mod jar 的 fake 缺口（类级 + 成员级）。
     *
     * @param jar        mod jar 路径
     * @param reference  参考 classpath（compat 模块编译产物目录或 fat jar）
     * @return 分析结果；jar 里完全没有 fake 域引用时返回 {@code null}
     */
    public static MemberGap analyzeMembers(Path jar, ClassLoader reference) throws IOException {
        Set<String> refs = new LinkedHashSet<>();
        Map<String, Set<Member>> uses = new LinkedHashMap<>();
        int classCount = 0;
        try (JarFile jf = new JarFile(jar.toFile())) {
            Enumeration<JarEntry> entries = jf.entries();
            while (entries.hasMoreElements()) {
                JarEntry e = entries.nextElement();
                if (e.isDirectory() || !e.getName().endsWith(".class")) continue;
                classCount++;
                try (InputStream in = jf.getInputStream(e)) {
                    // 注意：这里**不能** SKIP_CODE —— 成员级数据全在 code 里
                    collectRefs(new ClassReader(in), refs, uses);
                } catch (Throwable ignored) {
                    // 单个 class 读不出来不影响整体分析
                }
            }
        }
        if (refs.isEmpty()) return null;

        List<String> referenced = new ArrayList<>(new TreeSet<>(refs));
        List<String> missing = new ArrayList<>();
        Set<String> missingSet = new LinkedHashSet<>();
        for (String r : referenced) {
            String cls = r.replace('/', '.');
            if (!existsIn(reference, cls)) {
                missing.add(cls);
                missingSet.add(cls);
            }
        }
        Map<String, List<Member>> missingUses = new LinkedHashMap<>();
        for (Map.Entry<String, Set<Member>> en : uses.entrySet()) {
            String dotted = en.getKey().replace('/', '.');
            if (!missingSet.contains(dotted)) continue;
            missingUses.put(dotted, List.copyOf(new TreeSet<>(en.getValue())));
        }
        double cov = referenced.isEmpty() ? 1.0
                : (double) (referenced.size() - missing.size()) / referenced.size();
        return new MemberGap(jar.getFileName().toString(), referenced, missing,
                Collections.unmodifiableMap(missingUses), cov, classCount);
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

    /** 扫一个 class：类级引用进 {@code out}，成员级引用进 {@code uses}。 */
    private static void collectRefs(ClassReader cr, Set<String> out,
                                    Map<String, Set<Member>> uses) {
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
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitTypeInsn(int opcode, String type) {
                        // NEW / ANEWARRAY / CHECKCAST / INSTANCEOF：只出现在 code 里，
                        // 光看描述符会漏（典型：new Foo() 后立刻传给接口方法）
                        addIfFakeDomain(type, out);
                    }

                    @Override
                    public void visitMultiANewArrayInsn(String descriptor, int numDimensions) {
                        addIfFakeDomain(descriptor, out);
                    }

                    @Override
                    public void visitLdcInsn(Object value) {
                        if (value instanceof Type t) {
                            addIfFakeDomain(t.getDescriptor(), out);
                        }
                    }

                    @Override
                    public void visitFieldInsn(int opcode, String owner, String fname, String fdesc) {
                        addIfFakeDomain(fdesc, out);
                        if (isFakeDomain(owner)) {
                            boolean stat = opcode == Opcodes.GETSTATIC || opcode == Opcodes.PUTSTATIC;
                            addUse(uses, owner, new Member(Kind.FIELD, fname, fdesc, stat, false));
                        }
                    }

                    @Override
                    public void visitMethodInsn(int opcode, String owner, String mname, String mdesc,
                                               boolean itf) {
                        addIfFakeDomain(mdesc, out);
                        if (isFakeDomain(owner)) {
                            boolean stat = opcode == Opcodes.INVOKESTATIC;
                            boolean itfOwner = itf || opcode == Opcodes.INVOKEINTERFACE;
                            addUse(uses, owner, new Member(Kind.METHOD, mname, mdesc, stat, itfOwner));
                        }
                    }

                    @Override
                    public void visitInvokeDynamicInsn(String idynName, String idynDesc,
                                                       Handle bsm, Object... bsmArgs) {
                        addIfFakeDomain(idynDesc, out);
                        for (Object a : bsmArgs) {
                            if (a instanceof Type t) addIfFakeDomain(t.getDescriptor(), out);
                            else if (a instanceof Handle h) addIfFakeDomain(h.getDesc(), out);
                        }
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
    }

    private static void addUse(Map<String, Set<Member>> uses, String owner, Member m) {
        uses.computeIfAbsent(owner, k -> new LinkedHashSet<>()).add(m);
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
        // ① 裸内部名（visit 的 name / superName / interfaces / exceptions / typeInsn）
        if (isFakeDomain(s)) {
            out.add(s);
            return;
        }
        // ② 描述符里的对象类型段
        Matcher m = OBJ_TYPE.matcher(s);
        while (m.find()) {
            String seg = m.group(1);
            if (isFakeDomain(seg)) out.add(seg);
        }
    }

    private static boolean isFakeDomain(String internalName) {
        if (internalName == null) return false;
        for (String d : FAKE_DOMAINS) {
            if (internalName.startsWith(d)) return true;
        }
        return false;
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

    /** 成员级缺口清单的可读文本。 */
    public static String renderMembers(MemberGap g) {
        StringBuilder sb = new StringBuilder(render(new Gap(g.modId(), g.referenced(),
                g.missing(), g.coverage(), g.classCount())));
        for (Map.Entry<String, List<Member>> en : g.memberUses().entrySet()) {
            sb.append("  · ").append(en.getKey()).append('\n');
            for (Member m : en.getValue()) sb.append("      ").append(m.render()).append('\n');
        }
        return sb.toString();
    }

    /** 排序（按缺失数降序，便于看谁最急）。 */
    public static List<Gap> sortByGap(List<Gap> gaps) {
        List<Gap> copy = new ArrayList<>(gaps);
        copy.sort((a, b) -> Integer.compare(b.missing().size(), a.missing().size()));
        return Collections.unmodifiableList(copy);
    }
}
