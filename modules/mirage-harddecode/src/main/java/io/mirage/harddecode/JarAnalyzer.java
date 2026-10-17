package io.mirage.harddecode;

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 纯静态字节码分析器：打开 jar、遍历 .class、用 ASM 提取结构与语义。
 * 不加载类、不运行任何字节码。
 */
public class JarAnalyzer {

    // MG / Fabric / Mixin 关键注解描述符（Type.getDescriptor 形式，即 L...;）
    private static final String DESC_MOD           = "Lio/mirage/api/Mod;";
    private static final String DESC_SUBSCRIBE    = "Lio/mirage/api/SubscribeEvent;";
    private static final String DESC_ENTRYPOINT    = "Lio/mirage/api/Entrypoint;";
    private static final String DESC_MIXIN         = "Lorg/spongepowered/asm/mixin/Mixin;";
    private static final String DESC_INJECT        = "Lorg/spongepowered/asm/mixin/injection/Inject;";

    private final ZipFile zip;
    private final OutputModel model;
    private final Set<OutputModel.MethodCall> callDedup = new HashSet<>();

    public JarAnalyzer(ZipFile zip, OutputModel model) {
        this.zip = zip;
        this.model = model;
    }

    public void analyze() throws IOException {
        // 1. 元数据（fabric.mod.json 等）先读，这样 @Mod 找不到时也有 mod.id
        new MetaReader(zip).readInto(model);

        // 2. 遍历所有 .class
        int classCount = 0;
        java.util.Enumeration<? extends ZipEntry> en = zip.entries();
        while (en.hasMoreElements()) {
            ZipEntry e = en.nextElement();
            String name = e.getName();
            if (e.isDirectory() || !name.endsWith(".class")) continue;
            if (name.endsWith("module-info.class") || name.endsWith("package-info.class")) continue;

            try (InputStream in = zip.getInputStream(e)) {
                byte[] bytes = in.readAllBytes();
                ClassReader cr = new ClassReader(bytes);
                cr.accept(new ClassStructureVisitor(), ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                classCount++;
            }
        }

        // 3. 统计
        model._diagnostics.stats.classCount = classCount;
        model._diagnostics.stats.methodCallCount = callDedup.size();
        model._diagnostics.stats.unresolvedCount = model._diagnostics.unresolved.size();

        // 4. methodCalls 去重后填回
        model._diagnostics.methodCalls.addAll(callDedup);

        // 5. resources.namespaces 用 mod.id 兜底
        if (model.resources.namespaces.isEmpty() && !"unknown".equals(model.mod.id)) {
            model.resources.namespaces.add(model.mod.id);
        }
    }

    // ============================================================
    // ClassVisitor
    // ============================================================
    private class ClassStructureVisitor extends ClassVisitor {
        private ClassStructure cs;

        public ClassStructureVisitor() {
            super(Opcodes.ASM9);
        }

        @Override
        public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
            cs = new ClassStructure(name, superName);
            cs.access = accessStrings(access);
            if (interfaces != null) {
                for (String itf : interfaces) cs.interfaces.add(itf);
            }
            model._diagnostics.classes.add(cs);
        }

        @Override
        public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
            if (!visible) return null;
            cs.annotations.add(descriptor);
            // 返回一个会在 visitEnd 时提取语义的收集器
            return new SemanticAnnotationCollector() {
                @Override
                public void visitEnd() {
                    super.visitEnd();
                    Map<String, Object> v = values;
                    if (DESC_MOD.equals(descriptor)) {
                        Object val = v.get("value");
                        if (val instanceof String s) model.mod.id = s;
                    } else if (DESC_ENTRYPOINT.equals(descriptor)) {
                        Object val = v.get("value");
                        if (val instanceof String stage) {
                            List<String> list = model.mod.entrypoints.computeIfAbsent(stage, k -> new ArrayList<>());
                            list.add(cs.name.replace('/', '.'));
                        }
                    } else if (DESC_MIXIN.equals(descriptor)) {
                        Object val = v.get("value");
                        if (val instanceof List<?> l) {
                            for (Object o : l) {
                                if (o instanceof Type t) model._diagnostics.mixinTargets.add(t.getInternalName());
                                else if (o instanceof String s) model._diagnostics.mixinTargets.add(s);
                            }
                        }
                        Object tg = v.get("targets");
                        if (tg instanceof List<?> l) {
                            for (Object o : l) {
                                if (o instanceof String s) model._diagnostics.mixinTargets.add(s);
                            }
                        }
                    }
                }
            };
        }

        @Override
        public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
            FieldInfo fi = new FieldInfo(name, descriptor, accessStrings(access), new ArrayList<>());
            cs.fields.add(fi);
            return new FieldVisitor(Opcodes.ASM9) {
                @Override
                public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
                    if (!visible) return null;
                    fi.annotations.add(desc);
                    return null;
                }
            };
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
            MethodInfo mi = new MethodInfo(name, descriptor, accessStrings(access), new ArrayList<>());
            cs.methods.add(mi);
            model._diagnostics.stats.methodCount++;
            return new MethodStructureVisitor(cs.name, mi);
        }
    }

    // ============================================================
    // MethodVisitor：记录方法调用、invokedynamic、方法级注解
    // ============================================================
    private class MethodStructureVisitor extends MethodVisitor {
        private final String owner;
        private final MethodInfo mi;

        public MethodStructureVisitor(String owner, MethodInfo mi) {
            super(Opcodes.ASM9);
            this.owner = owner;
            this.mi = mi;
        }

        @Override
        public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
            if (!visible) return null;
            mi.annotations.add(descriptor);
            return new SemanticAnnotationCollector() {
                @Override
                public void visitEnd() {
                    super.visitEnd();
                    if (DESC_SUBSCRIBE.equals(descriptor)) {
                        String eventType = firstParamInternalName(mi.desc);
                        if (eventType != null) {
                            model.events.add(new OutputModel.EventInfo(
                                owner, mi.name, mi.desc, eventType,
                                new OutputModel.Src("STATIC", "HIGH")));
                        }
                    } else if (DESC_INJECT.equals(descriptor)) {
                        String handler = owner + "#" + mi.name + mi.desc;
                        model.transforms.add(new OutputModel.TransformInfo(
                            handler, "INJECT",
                            new OutputModel.Src("STATIC", "MEDIUM")));
                    }
                }
            };
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
            callDedup.add(new OutputModel.MethodCall(this.owner, owner, name, descriptor));
        }

        @Override
        public void visitInvokeDynamicInsn(String name, String descriptor, org.objectweb.asm.Handle bsm, Object... bsmArgs) {
            model._diagnostics.unresolved.add(new OutputModel.UnresolvedInfo(
                owner, mi.name + mi.desc, "invokedynamic/lambda", name, descriptor));
        }
    }

    // ============================================================
    // 工具
    // ============================================================

    /** 把 access flag 转成可读字符串数组。 */
    static List<String> accessStrings(int access) {
        List<String> out = new ArrayList<>();
        if ((access & Opcodes.ACC_PUBLIC) != 0)     out.add("PUBLIC");
        if ((access & Opcodes.ACC_PRIVATE) != 0)    out.add("PRIVATE");
        if ((access & Opcodes.ACC_PROTECTED) != 0)  out.add("PROTECTED");
        if ((access & Opcodes.ACC_STATIC) != 0)     out.add("STATIC");
        if ((access & Opcodes.ACC_FINAL) != 0)      out.add("FINAL");
        if ((access & Opcodes.ACC_ABSTRACT) != 0)    out.add("ABSTRACT");
        if ((access & Opcodes.ACC_NATIVE) != 0)     out.add("NATIVE");
        if ((access & Opcodes.ACC_INTERFACE) != 0)  out.add("INTERFACE");
        if ((access & Opcodes.ACC_SYNTHETIC) != 0)  out.add("SYNTHETIC");
        return out;
    }

    /** 从方法描述符中取第一个参数的内部类名，如 "(Lcom/example/Foo;)V" -> "com/example/Foo"。 */
    static String firstParamInternalName(String methodDesc) {
        int lp = methodDesc.indexOf('(');
        int rp = methodDesc.indexOf(')');
        if (lp < 0 || rp < 0 || rp <= lp + 1) return null;
        String params = methodDesc.substring(lp + 1, rp);
        int i = 0;
        while (i < params.length() && params.charAt(i) == '[') i++;
        if (i >= params.length()) return null;
        char c = params.charAt(i);
        if (c == 'L') {
            int end = params.indexOf(';', i);
            if (end > i) return params.substring(i + 1, end);
        }
        return null;
    }

    /**
     * 注解值收集器：把 visit/visitArray/visitEnum/visitAnnotation 的值
     * 收集到 {@link #values}。子类可重写 visitEnd 来提取语义。
     */
    static class SemanticAnnotationCollector extends AnnotationVisitor {
        final Map<String, Object> values = new LinkedHashMap<>();

        public SemanticAnnotationCollector() {
            super(Opcodes.ASM9);
        }

        @Override
        public void visit(String name, Object value) {
            values.put(name, value);
        }

        @Override
        public void visitEnum(String name, String descriptor, String value) {
            values.put(name, value);
        }

        @Override
        public AnnotationVisitor visitArray(String name) {
            List<Object> list = new ArrayList<>();
            values.put(name, list);
            return new AnnotationVisitor(Opcodes.ASM9) {
                @Override public void visit(String n, Object v) { list.add(v); }
                @Override public void visitEnum(String n, String d, String v) { list.add(v); }
                @Override public AnnotationVisitor visitAnnotation(String n, String d) {
                    SemanticAnnotationCollector nested = new SemanticAnnotationCollector();
                    list.add(nested.values);
                    return nested;
                }
            };
        }

        @Override
        public AnnotationVisitor visitAnnotation(String name, String descriptor) {
            SemanticAnnotationCollector nested = new SemanticAnnotationCollector();
            values.put(name, nested.values);
            return nested;
        }
    }
}
