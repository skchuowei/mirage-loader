package io.mirage.loader;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

/**
 * fake 骨架生成器（MSR P4，2026-11-08）。
 *
 * <p><b>输入</b>：{@link FakeGapAnalyzer.MemberGap} 的缺口（类名 + 成员引用）。
 * <b>输出</b>：可直接 {@code defineClass} 的 {@code .class} 文件（一个目录 = fake pack）。
 *
 * <p><b>为什么只生成 {@code net.neoforged.*}</b>：那一片是「加载器本该提供的 API 面」，
 * 缺了是我们的责任。{@code net.minecraft.*} 的缺口是另一回事——沙盒里 fake 掉
 * {@code Minecraft}/{@code Font}/{@code GuiGraphics} 并<b>不能</b>让 mod 真的跑起来，
 * 只会把 {@code NoClassDefFoundError} 换成更晚、更难查的 {@code NPE}
 * （AGENTS 技术注意事项第 7 节的「假覆盖」）。所以那一片<b>如实报告、一条不生成</b>，
 * 由 P3（真客户端录制）去回答。
 *
 * <p><b>生成策略（三条硬规则）</b>
 * <ol>
 *   <li><b>只搬签名，不搬实现</b>：方法体一律 {@code throw new UnsupportedOperationException(...)}，
 *       与 compat 层「未知方法抛 UOE」的既定原则一致 ⇒ {@code SandboxRecorder} 会把它归到
 *       {@code stub_incomplete}，不会误报成加载成功。绝不复制 NeoForge 的字节码（clean-room 红线）。</li>
 *   <li><b>成员按「code 里真用到的」来生成</b>：{@code static}/{@code 接口}标志取自调用点 opcode。
 *       漏了这两项，生成物会在链接期抛 {@code IncompatibleClassChangeError}，
 *       比原来的 CNFE 更难定位。有形状源（真 jar）时再并上它的 public/protected 成员，
 *       让「没被这个 mod 用到、但别的 mod 会用到」的方法也在。</li>
 *   <li><b>enum 必须带 clinit</b>：枚举常量是 {@code static final} 字段，剥掉 {@code <clinit>}
 *       就全是 null，{@code Dist.CLIENT} 变成 null 比缺类更难查。所以这里自己合成
 *       「常量字段 + {@code <clinit>} 逐个 new + {@code values()}/{@code valueOf()}」。</li>
 * </ol>
 *
 * <p><b>不做什么</b>：不做全量生成（签名闭包会拖出传递依赖，越生成越大）、不生成
 * {@code net.minecraft.*}、不改任何既有 stub（生成物只补「参考 classpath 里没有」的类）。
 */
public final class FakeStubGenerator {

    /** 只生成这一片（见类注释）。 */
    public static final String GENERATED_PREFIX = "net.neoforged.";

    /** 生成物里方法体的固定消息前缀（门禁据此断言「没有真实现」）。 */
    public static final String UOE_PREFIX = "MSR fake 未实现: ";

    /** 真实类的形状（只取签名）。 */
    public record Shape(int access, String name, String superName, String[] interfaces,
                        List<FieldShape> fields, List<MethodShape> methods) {}

    public record FieldShape(int access, String name, String desc) {}

    public record MethodShape(int access, String name, String desc) {}

    /** 形状来源：知道就给，不知道返回 {@code null}（生成器退化成「按调用点推」）。 */
    public interface ShapeSource {
        Shape shapeOf(String dottedName);
    }

    /** 一次生成的结果。 */
    public record Result(List<String> generated, List<String> skipped, List<String> failed,
                         long bytes) {}

    private FakeStubGenerator() {}

    /**
     * 生成缺口 stub。
     *
     * @param missing 缺失类（点分名，全量缺口；非 {@code net.neoforged.} 的会被跳过并记录原因）
     * @param uses    成员引用（点分类名 → 该 mod 用到的成员）
     * @param shapes  形状来源，可为 {@code null}
     * @param outDir  输出目录（不存在会创建）
     */
    public static Result generate(Set<String> missing,
                                  Map<String, List<FakeGapAnalyzer.Member>> uses,
                                  ShapeSource shapes, Path outDir) throws IOException {
        Files.createDirectories(outDir);

        Set<String> missingAll = new TreeSet<>(missing);
        Set<String> neoMissing = new TreeSet<>();
        List<String> skipped = new ArrayList<>();
        for (String m : missingAll) {
            if (m.startsWith(GENERATED_PREFIX)) neoMissing.add(m);
            else skipped.add(m + " <- 不在生成范围（只做 " + GENERATED_PREFIX + "*）");
        }

        List<String> generated = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        long[] bytes = new long[1];
        Set<String> done = new LinkedHashSet<>();

        // 预扫「谁被声明成了接口」：没有形状源的类只能靠这条线索判 kind。
        // 必须在生成前算完 —— 否则先被生成成 class、后被别的类 implements 时，
        // 加载期会 IncompatibleClassChangeError（"can not implement ... it is not an interface"）。
        Set<String> itfDeclared = collectInterfaceDeclarations(neoMissing, uses, shapes);

        for (String name : neoMissing) {
            try {
                emitClass(name, neoMissing, missingAll, uses, shapes, outDir, done,
                        generated, failed, bytes, false, itfDeclared);
            } catch (Throwable t) {
                failed.add(name + " <- " + t.getClass().getSimpleName()
                        + (t.getMessage() != null ? ": " + t.getMessage() : ""));
            }
        }
        generated.sort(Comparator.naturalOrder());
        failed.sort(Comparator.naturalOrder());
        skipped.sort(Comparator.naturalOrder());
        return new Result(List.copyOf(generated), List.copyOf(skipped), List.copyOf(failed), bytes[0]);
    }

    // ------------------------------------------------------------------ 生成

    /** 收集「被当成接口用」的类名：形状源的 implements 列表 + 调用点的 INVOKEINTERFACE。 */
    private static Set<String> collectInterfaceDeclarations(Set<String> neoMissing,
                                                            Map<String, List<FakeGapAnalyzer.Member>> uses,
                                                            ShapeSource shapes) {
        Set<String> out = new TreeSet<>();
        for (String m : neoMissing) {
            Shape sh = (shapes != null) ? shapes.shapeOf(m) : null;
            if (sh != null && sh.interfaces() != null) {
                for (String i : sh.interfaces()) out.add(i.replace('/', '.'));
            }
        }
        for (Map.Entry<String, List<FakeGapAnalyzer.Member>> en : uses.entrySet()) {
            for (FakeGapAnalyzer.Member mm : en.getValue()) {
                if (mm.ownerItf()) out.add(en.getKey());
            }
        }
        return out;
    }

    /**
     * 生成一个类。
     *
     * @param ifaceHint   调用方（另一个生成类的 {@code implements} 列表）声明它是接口。
     *                    <b>没有形状源时这条提示是唯一线索</b>：把接口误生成成 class，
     *                    对方 {@code defineClass} 会直接 {@code IncompatibleClassChangeError}。
     * @param itfDeclared 预扫出来的「全部被当接口用的类名」，避免「先生成成 class、后被 implements」
     */
    private static void emitClass(String dotted, Set<String> neoMissing, Set<String> missingAll,
                                  Map<String, List<FakeGapAnalyzer.Member>> uses,
                                  ShapeSource shapes, Path outDir, Set<String> done,
                                  List<String> generated, List<String> failed,
                                  long[] bytes, boolean ifaceHint, Set<String> itfDeclared)
            throws IOException {
        if (done.contains(dotted)) return;
        // 先占位，防超类环（A extends B，B extends A 这种畸形输入）
        done.add(dotted);

        String name = dotted.replace('.', '/');
        Shape shape = (shapes != null) ? shapes.shapeOf(dotted) : null;
        List<FakeGapAnalyzer.Member> members = uses.getOrDefault(dotted, List.of());

        int access = (shape != null) ? shape.access() : Opcodes.ACC_PUBLIC;
        access |= Opcodes.ACC_PUBLIC;
        access &= ~(Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED);
        // 不是 record：清掉 ACC_RECORD，否则 JVM 会拿"record 类"的规则去校验生成物
        // （没有 Record attribute / 组件），得不偿失。超类 java/lang/Record 本身可保留。
        access &= ~Opcodes.ACC_RECORD;

        boolean isEnum = shape != null && (shape.access() & Opcodes.ACC_ENUM) != 0;
        boolean isAnnotation = shape != null && (shape.access() & Opcodes.ACC_ANNOTATION) != 0;
        // 没有形状源时，只能靠「调用点 opcode」或「调用方的 implements 声明」判断是不是接口
        boolean looksLikeItf = shape == null
                && members.stream().anyMatch(FakeGapAnalyzer.Member::ownerItf)
                && members.stream().noneMatch(m -> "<init>".equals(m.name()));
        boolean isInterface = !isEnum && !isAnnotation
                && (shape != null ? (shape.access() & Opcodes.ACC_INTERFACE) != 0
                                  : (ifaceHint || looksLikeItf || itfDeclared.contains(dotted)));
        if (isEnum) {
            // 真实枚举常带「常量专属实现」（KeyConflictContext 的 isActive 就是抽象 +
            // 每个常量一个匿名子类）。照抄 ACC_ABSTRACT 会让 <clinit> 里 new 自己时
            // 直接 InstantiationError —— 我们的常量都用基类构造，必须清掉这个位。
            access &= ~Opcodes.ACC_ABSTRACT;
        }
        // 关键：判出「是接口」之后必须把 ACC_INTERFACE 真的写进 class 头。
        // 只改 ifaceLike（成员生成方式）而不改标志位，出来的仍是 class —— 于是别的
        // 生成类 implements 它时，加载期 IncompatibleClassChangeError
        // （"can not implement ... because it is not an interface"）。这条踩过。
        if (isInterface) {
            access |= Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT;
        }
        if (isAnnotation) {
            access |= Opcodes.ACC_ANNOTATION | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT;
        }

        // 超类：只引用「确定存在」的类；缺的 neo 超类先递归生成，其余缺失超类退回 Object
        String superName = "java/lang/Object";
        if (!isInterface && !isAnnotation) {
            if (isEnum) {
                superName = "java/lang/Enum";
            } else if (shape != null && shape.superName() != null
                    && !"java/lang/Object".equals(shape.superName())) {
                superName = resolveSuper(shape.superName(), neoMissing, missingAll, uses,
                        shapes, outDir, done, generated, failed, bytes, false, itfDeclared);
            }
        }

        // 接口：缺失的 neo 接口先递归生成；确定存在的保留；缺的 net.minecraft 接口丢掉
        List<String> itfs = new ArrayList<>();
        if (shape != null && shape.interfaces() != null) {
            for (String i : shape.interfaces()) {
                String resolved = resolveSuper(i, neoMissing, missingAll, uses,
                        shapes, outDir, done, generated, failed, bytes, true, itfDeclared);
                if (!"java/lang/Object".equals(resolved)) itfs.add(i);
            }
        }

        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        String[] itfArr = itfs.isEmpty() ? null : itfs.toArray(new String[0]);
        boolean ifaceLike = isInterface || isAnnotation;
        cw.visit(Opcodes.V21, access, name, null, ifaceLike ? "java/lang/Object" : superName, itfArr);

        if (isEnum) {
            emitEnumBody(cw, name, shape, members);
        } else {
            Set<String> methodKeys = new TreeSet<>();
            emitMembers(cw, name, superName, shape, members, ifaceLike, isAnnotation, methodKeys);
            if (!ifaceLike && !methodKeys.contains("<init>()V")) {
                // 每个生成类都保证有无参构造：让子类的 <init> 有 super() 可调
                emitThrowingCtor(cw, name, "()V", superName);
            }
        }

        cw.visitEnd();
        byte[] data = cw.toByteArray();

        Path out = outDir.resolve(name + ".class");
        Files.createDirectories(out.getParent());
        Files.write(out, data);
        bytes[0] += data.length;
        generated.add(dotted);
    }

    /** 解析一个超类型引用：能确定存在就返回它的内部名，否则返回 {@code java/lang/Object}。 */
    private static String resolveSuper(String internal, Set<String> neoMissing, Set<String> missingAll,
                                       Map<String, List<FakeGapAnalyzer.Member>> uses,
                                       ShapeSource shapes, Path outDir, Set<String> done,
                                       List<String> generated, List<String> failed,
                                       long[] bytes, boolean asInterface, Set<String> itfDeclared)
            throws IOException {
        String dotted = internal.replace('/', '.');
        if (dotted.startsWith("java.") || dotted.startsWith("javax.")) return internal;
        if (neoMissing.contains(dotted)) {
            emitClass(dotted, neoMissing, missingAll, uses, shapes, outDir, done,
                    generated, failed, bytes, asInterface, itfDeclared);
            return internal;
        }
        if (!missingAll.contains(dotted)) return internal;
        // 缺的 net.minecraft 超类型：不生成，也不能引用（否则 defineClass 直接 CNFE）
        return "java/lang/Object";
    }

    /** 普通类/接口/注解的成员。 */
    private static void emitMembers(ClassWriter cw, String name, String superName, Shape shape,
                                    List<FakeGapAnalyzer.Member> uses,
                                    boolean ifaceLike, boolean annotation,
                                    Set<String> methodKeys) {
        Set<String> seenFields = new TreeSet<>();

        // ① 形状源里的 public/protected 成员（跨 mod 复用）
        if (shape != null && shape.fields() != null) {
            for (FieldShape f : shape.fields()) {
                if ((f.access() & (Opcodes.ACC_PRIVATE | Opcodes.ACC_ENUM)) != 0) continue;
                if (!seenFields.add(f.name() + "|" + f.desc())) continue;
                emitField(cw, f.access(), f.name(), f.desc(), ifaceLike);
            }
        }
        if (shape != null && shape.methods() != null) {
            for (MethodShape m : shape.methods()) {
                if ((m.access() & Opcodes.ACC_PRIVATE) != 0) continue;
                if ("<clinit>".equals(m.name())) continue;
                if (!methodKeys.add(m.name() + m.desc())) continue;
                emitMethod(cw, name, superName, m.access(), m.name(), m.desc(), ifaceLike, annotation);
            }
        }

        // ② 调用点推出来的成员（形状源没有时这是唯一来源）
        for (FakeGapAnalyzer.Member m : uses) {
            if (m.kind() == FakeGapAnalyzer.Kind.FIELD) {
                if (!seenFields.add(m.name() + "|" + m.desc())) continue;
                int acc = Opcodes.ACC_PUBLIC | (m.isStatic() ? Opcodes.ACC_STATIC : 0);
                emitField(cw, acc, m.name(), m.desc(), ifaceLike);
            } else {
                if (!methodKeys.add(m.name() + m.desc())) continue;
                int acc = Opcodes.ACC_PUBLIC | (m.isStatic() ? Opcodes.ACC_STATIC : 0);
                emitMethod(cw, name, superName, acc, m.name(), m.desc(), ifaceLike, annotation);
            }
        }
    }

    private static void emitField(ClassWriter cw, int access, String name, String desc,
                                  boolean ifaceLike) {
        int acc;
        if (ifaceLike) {
            // 接口/注解字段在 JVM 里**必须**是 public static final。
            // 少了 FINAL 会直接 ClassFormatError（"Illegal field modifiers"），
            // 不是警告而是整类加载失败 —— 这条踩过：0x9 非法，0x19 合法。
            acc = Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL;
        } else {
            // 普通类的字段丢掉 ACC_FINAL：生成物的字段没有 clinit 赋值，保留 final 会让
            // 外部 putstatic 直接 IllegalAccessError（JVM 对 final 字段的跨类写有限制）
            acc = (access & ~(Opcodes.ACC_FINAL | Opcodes.ACC_PRIVATE)) | Opcodes.ACC_PUBLIC;
        }
        FieldVisitor fv = cw.visitField(acc, name, desc, null, null);
        fv.visitEnd();
    }

    private static void emitMethod(ClassWriter cw, String owner, String superName, int access,
                                   String name, String desc,
                                   boolean ifaceLike, boolean annotation) {
        int acc = access & ~(Opcodes.ACC_PRIVATE | Opcodes.ACC_NATIVE | Opcodes.ACC_FINAL);
        acc |= Opcodes.ACC_PUBLIC;
        boolean isStatic = (acc & Opcodes.ACC_STATIC) != 0;

        if ("<init>".equals(name)) {
            // 必须调**直接超类**的构造，不能写死 Object：
            // 生成类可能 extends java/lang/Record 或 compat 里的 Event，调错目标是
            // VerifyError: "Bad <init> method call"（整类加载失败，不是运行期异常）。
            emitThrowingCtor(cw, owner, desc, superName);
            return;
        }
        if (ifaceLike && !isStatic) {
            // 接口/注解：保持抽象（mod 自己 implements 时签名对得上，lambda 也仍是函数式接口）
            acc |= Opcodes.ACC_ABSTRACT;
            acc &= ~Opcodes.ACC_STATIC;
            cw.visitMethod(acc, name, desc, null, null).visitEnd();
            return;
        }
        acc &= ~Opcodes.ACC_ABSTRACT;
        MethodVisitor mv = cw.visitMethod(acc, name, desc, null,
                new String[]{"java/lang/Throwable"});
        mv.visitCode();
        mv.visitTypeInsn(Opcodes.NEW, "java/lang/UnsupportedOperationException");
        mv.visitInsn(Opcodes.DUP);
        mv.visitLdcInsn(UOE_PREFIX + owner.replace('/', '.') + "#" + name + desc);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/UnsupportedOperationException",
                "<init>", "(Ljava/lang/String;)V", false);
        mv.visitInsn(Opcodes.ATHROW);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /** 构造函数：调直接超类的无参构造 + 抛 UOE（不构造出半成品对象）。 */
    private static void emitThrowingCtor(ClassWriter cw, String owner, String desc,
                                         String superName) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", desc, null,
                new String[]{"java/lang/Throwable"});
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, superName, "<init>", "()V", false);
        mv.visitTypeInsn(Opcodes.NEW, "java/lang/UnsupportedOperationException");
        mv.visitInsn(Opcodes.DUP);
        mv.visitLdcInsn(UOE_PREFIX + owner.replace('/', '.') + "#<init>" + desc);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/UnsupportedOperationException",
                "<init>", "(Ljava/lang/String;)V", false);
        mv.visitInsn(Opcodes.ATHROW);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /**
     * 枚举：常量字段 + {@code <clinit>} + {@code values()} + {@code valueOf(String)}。
     *
     * <p>常量名优先取形状源里带 {@code ACC_ENUM} 的字段（就是真实声明顺序），
     * 没有形状源时退化成「调用点里出现的 GETSTATIC 字段名」——够 mod 读
     * {@code Dist.CLIENT} 这种常量，但 {@code values()} 只含用到的那些。
     */
    private static void emitEnumBody(ClassWriter cw, String name, Shape shape,
                                     List<FakeGapAnalyzer.Member> uses) {
        List<String> constants = new ArrayList<>();
        if (shape != null && shape.fields() != null) {
            for (FieldShape f : shape.fields()) {
                if ((f.access() & Opcodes.ACC_ENUM) != 0) constants.add(f.name());
            }
        }
        if (constants.isEmpty()) {
            for (FakeGapAnalyzer.Member m : uses) {
                if (m.kind() == FakeGapAnalyzer.Kind.FIELD && m.isStatic()
                        && ("L" + name + ";").equals(m.desc())) {
                    constants.add(m.name());
                }
            }
        }
        for (String c : constants) {
            cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL
                    | Opcodes.ACC_ENUM, c, "L" + name + ";", null, null).visitEnd();
        }
        cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL
                | Opcodes.ACC_SYNTHETIC, "$VALUES", "[L" + name + ";", null, null).visitEnd();

        // private X(String name, int ordinal)
        MethodVisitor ctor = cw.visitMethod(Opcodes.ACC_PRIVATE, "<init>",
                "(Ljava/lang/String;I)V", null, null);
        ctor.visitCode();
        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitVarInsn(Opcodes.ALOAD, 1);
        ctor.visitVarInsn(Opcodes.ILOAD, 2);
        ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Enum", "<init>",
                "(Ljava/lang/String;I)V", false);
        ctor.visitInsn(Opcodes.RETURN);
        ctor.visitMaxs(0, 0);
        ctor.visitEnd();

        // <clinit>
        MethodVisitor cl = cw.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        cl.visitCode();
        for (int i = 0; i < constants.size(); i++) {
            String c = constants.get(i);
            cl.visitTypeInsn(Opcodes.NEW, name);
            cl.visitInsn(Opcodes.DUP);
            cl.visitLdcInsn(c);
            pushInt(cl, i);
            cl.visitMethodInsn(Opcodes.INVOKESPECIAL, name, "<init>",
                    "(Ljava/lang/String;I)V", false);
            cl.visitFieldInsn(Opcodes.PUTSTATIC, name, c, "L" + name + ";");
        }
        pushInt(cl, constants.size());
        cl.visitTypeInsn(Opcodes.ANEWARRAY, name);
        for (int i = 0; i < constants.size(); i++) {
            cl.visitInsn(Opcodes.DUP);
            pushInt(cl, i);
            cl.visitFieldInsn(Opcodes.GETSTATIC, name, constants.get(i), "L" + name + ";");
            cl.visitInsn(Opcodes.AASTORE);
        }
        cl.visitFieldInsn(Opcodes.PUTSTATIC, name, "$VALUES", "[L" + name + ";");
        cl.visitInsn(Opcodes.RETURN);
        cl.visitMaxs(0, 0);
        cl.visitEnd();

        // values()
        MethodVisitor vals = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "values", "()[L" + name + ";", null, null);
        vals.visitCode();
        vals.visitFieldInsn(Opcodes.GETSTATIC, name, "$VALUES", "[L" + name + ";");
        vals.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "[L" + name + ";", "clone",
                "()Ljava/lang/Object;", false);
        vals.visitTypeInsn(Opcodes.CHECKCAST, "[L" + name + ";");
        vals.visitInsn(Opcodes.ARETURN);
        vals.visitMaxs(0, 0);
        vals.visitEnd();

        // valueOf(String)
        MethodVisitor vof = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "valueOf", "(Ljava/lang/String;)L" + name + ";", null, null);
        vof.visitCode();
        vof.visitLdcInsn(Type.getObjectType(name));
        vof.visitVarInsn(Opcodes.ALOAD, 0);
        vof.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Enum", "valueOf",
                "(Ljava/lang/Class;Ljava/lang/String;)Ljava/lang/Enum;", false);
        vof.visitTypeInsn(Opcodes.CHECKCAST, name);
        vof.visitInsn(Opcodes.ARETURN);
        vof.visitMaxs(0, 0);
        vof.visitEnd();

        // 其余用到的成员（非枚举常量）照普通方法生成
        Set<String> constSet = new LinkedHashSet<>(constants);
        Set<String> seen = new TreeSet<>();
        for (FakeGapAnalyzer.Member m : uses) {
            if (m.kind() == FakeGapAnalyzer.Kind.FIELD) {
                if (constSet.contains(m.name())) continue;
                if (!seen.add(m.name() + "|" + m.desc())) continue;
                emitField(cw, Opcodes.ACC_PUBLIC | (m.isStatic() ? Opcodes.ACC_STATIC : 0),
                        m.name(), m.desc(), false);
            } else {
                if ("<init>".equals(m.name())) continue;
                if (!seen.add(m.name() + m.desc())) continue;
                emitMethod(cw, name, "java/lang/Enum",
                        Opcodes.ACC_PUBLIC | (m.isStatic() ? Opcodes.ACC_STATIC : 0),
                        m.name(), m.desc(), false, false);
            }
        }
    }

    /** 小整数压栈（iconst_* / bipush，够枚举常量个数用）。 */
    private static void pushInt(MethodVisitor mv, int v) {
        if (v >= -1 && v <= 5) mv.visitInsn(Opcodes.ICONST_0 + v);
        else mv.visitIntInsn(Opcodes.BIPUSH, v);
    }

    // ------------------------------------------------------------------ 形状源

    /**
     * 从真 jar / 目录读类形状的实现。
     *
     * <p><b>只读签名</b>：{@code ClassReader} 带 {@code SKIP_CODE}，方法体一个字节都不碰，
     * 生成物里也不会出现任何来自 NeoForge 的实现（clean-room 红线）。
     */
    public static final class FileShapeSource implements ShapeSource, Closeable {

        private final List<JarFile> jars = new ArrayList<>();
        private final List<Path> dirs = new ArrayList<>();
        private final Map<String, Shape> cache = new LinkedHashMap<>();

        public FileShapeSource(List<Path> sources) throws IOException {
            for (Path p : sources) {
                if (p == null || !Files.exists(p)) continue;
                if (Files.isDirectory(p)) dirs.add(p);
                else jars.add(new JarFile(p.toFile()));
            }
        }

        @Override
        public Shape shapeOf(String dottedName) {
            if (cache.containsKey(dottedName)) return cache.get(dottedName);
            Shape s = load(dottedName.replace('.', '/'));
            cache.put(dottedName, s);
            return s;
        }

        private Shape load(String internal) {
            String entry = internal + ".class";
            for (JarFile jf : jars) {
                JarEntry e = jf.getJarEntry(entry);
                if (e == null) continue;
                try (InputStream in = jf.getInputStream(e)) {
                    return parse(in);
                } catch (Throwable ignored) {
                    return null;
                }
            }
            for (Path d : dirs) {
                Path f = d.resolve(entry);
                if (!Files.exists(f)) continue;
                try (InputStream in = Files.newInputStream(f)) {
                    return parse(in);
                } catch (Throwable ignored) {
                    return null;
                }
            }
            return null;
        }

        private static Shape parse(InputStream in) throws IOException {
            ClassReader cr = new ClassReader(in);
            List<FieldShape> fields = new ArrayList<>();
            List<MethodShape> methods = new ArrayList<>();
            int[] access = new int[1];
            String[] names = new String[3];
            String[][] itfs = new String[1][];
            cr.accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public void visit(int version, int a, String name, String signature,
                                  String superName, String[] interfaces) {
                    access[0] = a;
                    names[0] = name;
                    names[1] = superName;
                    itfs[0] = interfaces;
                }

                @Override
                public FieldVisitor visitField(int a, String name, String desc, String signature,
                                               Object value) {
                    fields.add(new FieldShape(a, name, desc));
                    return null;
                }

                @Override
                public MethodVisitor visitMethod(int a, String name, String desc, String signature,
                                                 String[] exceptions) {
                    methods.add(new MethodShape(a, name, desc));
                    return null;
                }
            }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            return new Shape(access[0], names[0], names[1], itfs[0], fields, methods);
        }

        @Override
        public void close() {
            for (JarFile jf : jars) {
                try {
                    jf.close();
                } catch (IOException ignored) {
                    // 关闭失败无所谓
                }
            }
            jars.clear();
        }

        /** 收集一个路径下所有 jar（形状源目录便利法）。 */
        public static List<Path> expand(Path p) throws IOException {
            List<Path> out = new ArrayList<>();
            if (!Files.exists(p)) return out;
            if (Files.isDirectory(p)) {
                try (Stream<Path> s = Files.list(p)) {
                    s.filter(x -> x.toString().endsWith(".jar")).sorted().forEach(out::add);
                }
            } else {
                out.add(p);
            }
            return out;
        }
    }
}
