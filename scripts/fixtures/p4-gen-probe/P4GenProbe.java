package probe;

import io.mirage.loader.FakeGapAnalyzer;
import io.mirage.loader.FakeStubGenerator;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.File;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

/**
 * P4 门禁用的行为探针（不启动游戏、不联网、不依赖真实 mod）。
 *
 * <p>它做四件事：
 * <ol>
 *   <li>用 ASM 现造一份「真 jar」当<b>形状源</b>（一个 enum、一个 interface、一个 class）；</li>
 *   <li>造一份「mod jar」，其 code 真引用上述类的静态字段 / 实例方法 / 静态方法 /
 *       接口方法 / 枚举常量，外加一个 {@code net.minecraft.*} 的缺失类（用来验边界）；</li>
 *   <li>跑 {@link FakeGapAnalyzer} + {@link FakeStubGenerator}，把 pack 落到临时目录；</li>
 *   <li>把生成的类<b>真的加载起来</b>并调用，打印 {@code KEY=VALUE} 供 PowerShell 断言。</li>
 * </ol>
 *
 * <p>为什么必须真加载：只看字节码能不能生成毫无意义 —— 生成物经常是
 * 「能被写出来、但一 {@code defineClass} 就 ClassFormatError」。
 *
 * <p>输出契约（全部 ASCII，避免控制台代码页干扰断言）：
 * <pre>
 * ANALYZE ref=N miss=N neo=N mc=N
 * GEN generated=N skipped=N failed=N
 * BOUNDARY mcGenerated=N
 * LOAD Api=ok interface=false
 * LOAD IThing=ok interface=true abstractMethod=true
 * LOAD Mode=ok enum=true
 * BEHAVIOR Api.VALUE static=true
 * BEHAVIOR Api.bar=UnsupportedOperationException prefixOk=true
 * BEHAVIOR Api.ctor=UnsupportedOperationException prefixOk=true
 * BEHAVIOR Mode.CLIENT=ok values=2
 * </pre>
 */
public final class P4GenProbe {

    private static final String PKG = "net/neoforged/p4probe/";

    public static void main(String[] args) throws Exception {
        Path workspace = Paths.get(args[0]);
        Path compat = Paths.get(args[1]);
        Files.createDirectories(workspace);

        Path shapeJar = workspace.resolve("shape.jar");
        Path modJar = workspace.resolve("mod.jar");
        Path pack = workspace.resolve("pack");
        // 每次从干净目录开始，避免上一轮残留让"生成数"虚高
        deleteTree(pack);
        buildShapeJar(shapeJar);
        buildModJar(modJar);

        ClassLoader ref = new URLClassLoader(new URL[]{compat.toUri().toURL()},
                ClassLoader.getPlatformClassLoader());
        FakeGapAnalyzer.MemberGap gap = FakeGapAnalyzer.analyzeMembers(modJar, ref);
        if (gap == null) {
            System.out.println("ANALYZE ref=0 miss=0 neo=0 mc=0");
            System.out.println("FATAL 分析结果为空");
            return;
        }
        int neo = 0;
        int mc = 0;
        for (String m : gap.missing()) {
            if (m.startsWith("net.neoforged.")) neo++;
            if (m.startsWith("net.minecraft.")) mc++;
        }
        System.out.println("ANALYZE ref=" + gap.referenced().size() + " miss=" + gap.missing().size()
                + " neo=" + neo + " mc=" + mc);

        FakeStubGenerator.Result res;
        try (FakeStubGenerator.FileShapeSource shapes =
                     new FakeStubGenerator.FileShapeSource(List.of(shapeJar))) {
            res = FakeStubGenerator.generate(new TreeSet<>(gap.missing()), gap.memberUses(), shapes, pack);
        }
        System.out.println("GEN generated=" + res.generated().size()
                + " skipped=" + res.skipped().size() + " failed=" + res.failed().size());
        for (String f : res.failed()) System.out.println("GEN_FAIL=" + f);

        long mcGenerated;
        try (var s = Files.walk(pack)) {
            mcGenerated = s.filter(p -> p.toString().replace(File.separatorChar, '/')
                    .contains("/net/minecraft/")).count();
        }
        System.out.println("BOUNDARY mcGenerated=" + mcGenerated);

        URLClassLoader cl = new URLClassLoader(new URL[]{pack.toUri().toURL()},
                ClassLoader.getPlatformClassLoader());

        Class<?> api = load(cl, "net.neoforged.p4probe.Api", "Api");
        if (api == null) return;
        System.out.println("LOAD Api=ok interface=" + api.isInterface());
        try {
            var f = api.getField("VALUE");
            System.out.println("BEHAVIOR Api.VALUE static=" + Modifier.isStatic(f.getModifiers())
                    + " final=" + Modifier.isFinal(f.getModifiers()));
        } catch (Throwable t) {
            System.out.println("BEHAVIOR Api.VALUE=missing " + t.getClass().getSimpleName());
        }
        invokeStatic(api, "bar", "Api.bar");
        invokeCtor(api, "Api.ctor");

        Class<?> itf = load(cl, "net.neoforged.p4probe.IThing", "IThing");
        if (itf != null) {
            boolean abs = false;
            for (var m : itf.getDeclaredMethods()) {
                if (m.getName().equals("size")) abs = Modifier.isAbstract(m.getModifiers());
            }
            boolean fieldFinal = false;
            try {
                fieldFinal = Modifier.isFinal(itf.getField("EMPTY").getModifiers())
                        && Modifier.isStatic(itf.getField("EMPTY").getModifiers());
            } catch (Throwable ignored) {
                // 没字段就保持 false
            }
            System.out.println("LOAD IThing=ok interface=" + itf.isInterface()
                    + " abstractMethod=" + abs + " staticFinalField=" + fieldFinal);
        }

        Class<?> mode = load(cl, "net.neoforged.p4probe.Mode", "Mode");
        if (mode != null) {
            System.out.println("LOAD Mode=ok enum=" + mode.isEnum()
                    + " abstract=" + Modifier.isAbstract(mode.getModifiers()));
            try {
                Object client = mode.getMethod("valueOf", String.class).invoke(null, "CLIENT");
                int values = ((Object[]) mode.getMethod("values").invoke(null)).length;
                System.out.println("BEHAVIOR Mode.CLIENT=" + (client == null ? "null" : "ok")
                        + " values=" + values
                        + " name=" + (client == null ? "-" : ((Enum<?>) client).name()));
            } catch (Throwable t) {
                Throwable root = unwrap(t);
                System.out.println("BEHAVIOR Mode.CLIENT=FAIL " + root.getClass().getSimpleName()
                        + " " + oneLine(root.getMessage()));
            }
        }
    }

    // ------------------------------------------------------------------ 断言辅助

    private static Class<?> load(ClassLoader cl, String name, String label) {
        try {
            return Class.forName(name, true, cl);
        } catch (Throwable t) {
            System.out.println("LOAD " + label + "=FAIL " + unwrap(t).getClass().getSimpleName()
                    + " " + oneLine(unwrap(t).getMessage()));
            return null;
        }
    }

    private static void invokeStatic(Class<?> c, String method, String label) {
        try {
            c.getMethod(method).invoke(null);
            System.out.println("BEHAVIOR " + label + "=NO-THROW");
        } catch (Throwable t) {
            Throwable root = unwrap(t);
            System.out.println("BEHAVIOR " + label + "=" + root.getClass().getSimpleName()
                    + " prefixOk=" + prefixOk(root));
        }
    }

    private static void invokeCtor(Class<?> c, String label) {
        try {
            c.getDeclaredConstructor().newInstance();
            System.out.println("BEHAVIOR " + label + "=NO-THROW");
        } catch (Throwable t) {
            Throwable root = unwrap(t);
            System.out.println("BEHAVIOR " + label + "=" + root.getClass().getSimpleName()
                    + " prefixOk=" + prefixOk(root));
        }
    }

    /** 方法体必须是「未实现」而不是真实现：用生成器自己的常量做比较（避免中文经控制台变码）。 */
    private static boolean prefixOk(Throwable t) {
        return t instanceof UnsupportedOperationException
                && t.getMessage() != null
                && t.getMessage().startsWith(FakeStubGenerator.UOE_PREFIX);
    }

    private static Throwable unwrap(Throwable t) {
        Throwable cur = t;
        for (int i = 0; i < 8 && cur.getCause() != null; i++) cur = cur.getCause();
        return cur;
    }

    private static String oneLine(String s) {
        if (s == null) return "-";
        String x = s.replace('\n', ' ').replace('\r', ' ');
        return x.length() > 120 ? x.substring(0, 120) : x;
    }

    private static void deleteTree(Path p) throws Exception {
        if (!Files.exists(p)) return;
        try (var s = Files.walk(p)) {
            s.sorted(java.util.Comparator.reverseOrder()).forEach(x -> {
                try {
                    Files.deleteIfExists(x);
                } catch (Exception ignored) {
                    // 删不掉就算了，临时目录
                }
            });
        }
    }

    // ------------------------------------------------------------------ 造夹具

    /** 形状源：真 jar 的角色（只用来读签名，不参与运行）。 */
    private static void buildShapeJar(Path jar) throws Exception {
        byte[] api = api();
        byte[] base = base();
        byte[] itf = itf();
        byte[] mode = mode();
        writeJar(jar, new String[]{
                PKG + "Api.class", PKG + "Base.class", PKG + "IThing.class", PKG + "Mode.class"
        }, new byte[][]{api, base, itf, mode});
    }

    /** 父类：让 Api 的 <init> 必须调 Base.<init>（写死 Object 就会 VerifyError）。 */
    private static byte[] base() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, PKG + "Base", null,
                "java/lang/Object", null);
        MethodVisitor ctor = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        ctor.visitCode();
        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        ctor.visitInsn(Opcodes.RETURN);
        ctor.visitMaxs(1, 1);
        ctor.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] api() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, PKG + "Api", null,
                PKG + "Base", null);
        cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "VALUE", "L" + PKG + "Api;", null, null).visitEnd();
        MethodVisitor ctor = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        ctor.visitCode();
        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        ctor.visitInsn(Opcodes.RETURN);
        ctor.visitMaxs(1, 1);
        ctor.visitEnd();
        MethodVisitor bar = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "bar", "()V", null, null);
        bar.visitCode();
        bar.visitInsn(Opcodes.RETURN);
        bar.visitMaxs(0, 0);
        bar.visitEnd();
        MethodVisitor foo = cw.visitMethod(Opcodes.ACC_PUBLIC, "foo", "()V", null, null);
        foo.visitCode();
        foo.visitInsn(Opcodes.RETURN);
        foo.visitMaxs(0, 0);
        foo.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] itf() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT,
                PKG + "IThing", null, "java/lang/Object", null);
        cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
                "EMPTY", "L" + PKG + "IThing;", null, null).visitEnd();
        cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "size", "()I", null, null).visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /**
     * 枚举：故意做成 <b>abstract</b>（真实 NeoForge 里 {@code KeyConflictContext} 就是这样：
     * 抽象方法 + 每个常量一个匿名子类）。生成器若照抄 ACC_ABSTRACT，clinit 里 new 自己时
     * 会 InstantiationError —— 这是必须被门禁抓住的回归。
     */
    private static byte[] mode() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_ENUM
                        | Opcodes.ACC_SUPER | Opcodes.ACC_ABSTRACT,
                PKG + "Mode", "Ljava/lang/Enum<L" + PKG + "Mode;>;", "java/lang/Enum", null);
        cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL | Opcodes.ACC_ENUM,
                "CLIENT", "L" + PKG + "Mode;", null, null).visitEnd();
        cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL | Opcodes.ACC_ENUM,
                "SERVER", "L" + PKG + "Mode;", null, null).visitEnd();
        cw.visitMethod(Opcodes.ACC_PRIVATE, "<init>", "(Ljava/lang/String;I)V", null, null).visitEnd();
        cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "values", "()[L" + PKG + "Mode;", null, null).visitEnd();
        cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "valueOf",
                "(Ljava/lang/String;)L" + PKG + "Mode;", null, null).visitEnd();
        cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "tag", "()Ljava/lang/String;", null, null).visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** mod jar：一个类，其 code 真引用上面那些成员（字段/方法/接口/枚举常量/缺失的 mc 类）。 */
    private static void buildModJar(Path jar) throws Exception {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, "probe/User", null,
                "java/lang/Object", null);
        cw.visitField(Opcodes.ACC_PRIVATE, "mcRef", "Lnet/minecraft/p4probe/Missing;", null, null).visitEnd();
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "use", "()V", null, null);
        mv.visitCode();
        mv.visitFieldInsn(Opcodes.GETSTATIC, PKG + "Api", "VALUE", "L" + PKG + "Api;");
        mv.visitInsn(Opcodes.POP);
        mv.visitTypeInsn(Opcodes.NEW, PKG + "Base");
        mv.visitInsn(Opcodes.DUP);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, PKG + "Base", "<init>", "()V", false);
        mv.visitInsn(Opcodes.POP);
        mv.visitTypeInsn(Opcodes.NEW, PKG + "Api");
        mv.visitInsn(Opcodes.DUP);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, PKG + "Api", "<init>", "()V", false);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, PKG + "Api", "foo", "()V", false);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, PKG + "Api", "bar", "()V", false);
        mv.visitFieldInsn(Opcodes.GETSTATIC, PKG + "IThing", "EMPTY", "L" + PKG + "IThing;");
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, PKG + "IThing", "size", "()I", true);
        mv.visitInsn(Opcodes.POP);
        mv.visitFieldInsn(Opcodes.GETSTATIC, PKG + "Mode", "CLIENT", "L" + PKG + "Mode;");
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(2, 0);
        mv.visitEnd();
        cw.visitEnd();
        writeJar(jar, new String[]{"probe/User.class"}, new byte[][]{cw.toByteArray()});
    }

    private static void writeJar(Path jar, String[] names, byte[][] bodies) throws Exception {
        Files.deleteIfExists(jar);
        try (OutputStream os = Files.newOutputStream(jar);
             JarOutputStream jos = new JarOutputStream(os)) {
            for (int i = 0; i < names.length; i++) {
                jos.putNextEntry(new JarEntry(names[i]));
                jos.write(bodies[i]);
                jos.closeEntry();
            }
        }
    }
}
