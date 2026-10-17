package io.mirage.neoforge;

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 纯静态字节码扫描（clean-room，不加载类、不运行字节码）：
 *
 * <ul>
 *   <li>类级注解 {@code Lnet/neoforged/fml/common/Mod;} 的 {@code value()} = modId</li>
 *   <li>方法级注解 {@code Lnet/neoforged/bus/api/SubscribeEvent;} -> events[]</li>
 *   <li>{@code public static} 字段类型为
 *       {@code Lnet/neoforged/neoforge/registries/DeferredRegister;} -> registries[] 提示</li>
 * </ul>
 *
 * <p>注解按描述符字符串匹配，不要求 classpath 上真有 NeoForge 类。
 */
public class NeoModScanner {

    /** @Mod 注解描述符（NeoForge 4.x+）。 */
    private static final String DESC_MOD = "Lnet/neoforged/fml/common/Mod;";
    /** @SubscribeEvent 注解描述符。 */
    private static final String DESC_SUBSCRIBE = "Lnet/neoforged/bus/api/SubscribeEvent;";
    /** DeferredRegister 静态字段的擦除后描述符。 */
    private static final String DESC_DEFERRED_REGISTER = "Lnet/neoforged/neoforge/registries/DeferredRegister;";

    private final ZipFile zip;
    private final OutputModel model;

    public NeoModScanner(ZipFile zip, OutputModel model) {
        this.zip = zip;
        this.model = model;
    }

    public void scan() throws IOException {
        // 1. 描述符先行：mod.id 缺省时 toml 兜底
        ModsTomlParser.Result toml = new ModsTomlParser(zip).parse();
        if (toml != null) applyToml(toml);

        // 2. 遍历 .class
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
                cr.accept(new ClassScanVisitor(), ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                classCount++;
            }
        }

        // 3. namespaces 兜底
        if (model.resources.namespaces.isEmpty() && !"unknown".equals(model.mod.id)) {
            model.resources.namespaces.add(model.mod.id);
        }

        // 4. 统计
        model._diagnostics.stats.classCount = classCount;
        model._diagnostics.stats.eventListenerCount = model.events.size();
        model._diagnostics.stats.registryHintCount = model.registries.size();
        model._diagnostics.stats.dependencyCount = model.dependencies.size();
    }

    private void applyToml(ModsTomlParser.Result t) {
        if (!t.mods.isEmpty()) {
            ModsTomlParser.ModEntry m = t.mods.get(0);
            if (!m.modId.isEmpty()) model.mod.id = m.modId;
            if (!m.version.isEmpty()) {
                if (m.version.contains("${")) {
                    // 占位符未解析（${version} / ${file.jarVersion}），按规范默认 1.0.0 并记一笔
                    model._diagnostics.unresolved.add(
                        "toml: mod.version 占位符未解析 -> 采用默认 1.0.0 (原始值=" + m.version + ")");
                    model.mod.version = "1.0.0";
                } else {
                    model.mod.version = m.version;
                }
            }
        } else {
            model._diagnostics.unresolved.add("toml: 缺少 [[mods]] 表，mod.id/version 未知");
        }

        for (ModsTomlParser.DepEntry d : t.deps) {
            OutputModel.DepInfo dep = new OutputModel.DepInfo();
            dep.modId = d.modId;
            dep.version = d.versionRange == null ? "" : d.versionRange;
            dep.type = d.mandatory ? "REQUIRED" : "OPTIONAL";
            dep.side = (d.side == null || d.side.isEmpty()) ? "BOTH" : d.side;
            model.dependencies.add(dep);
        }

        model._diagnostics.mixinTargets.addAll(t.mixinConfigs);
        model.mod.accessWideners.addAll(t.accessTransformers);

        // 诊断：把 loaderVersion / license 等旁注记一笔，便于 QA
        if (!t.loaderVersion.isEmpty()) {
            model._diagnostics.unresolved.add("toml: loaderVersion=" + t.loaderVersion
                + " license=" + t.license + " (未桥接到运行时)");
        }
        model._diagnostics.unresolved.addAll(t.warnings);
    }

    // ============================================================
    // ClassVisitor
    // ============================================================
    private class ClassScanVisitor extends ClassVisitor {
        private String owner;

        public ClassScanVisitor() { super(Opcodes.ASM9); }

        @Override
        public void visit(int version, int access, String name, String signature,
                          String superName, String[] interfaces) {
            this.owner = name;
            model._diagnostics.classes.add(name);
        }

        @Override
        public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
            if (!visible) return null;
            if (DESC_MOD.equals(descriptor)) {
                return new SemanticVisitor() {
                    @Override public void visitEnd() {
                        super.visitEnd();
                        Object v = values.get("value");
                        if (v instanceof String modId) {
                            // 注解显式声明的 modId 覆盖 toml（通常二者一致）
                            model.mod.id = modId;
                            // @Mod 类即 NeoForge 入口点
                            List<String> mains = model.mod.entrypoints.get("main");
                            if (mains == null) {
                                mains = new java.util.ArrayList<>();
                                model.mod.entrypoints.put("main", mains);
                            }
                            mains.add(owner.replace('/', '.'));
                        }
                    }
                };
            }
            return null;
        }

        @Override
        public FieldVisitor visitField(int access, String name, String descriptor,
                                       String signature, Object value) {
            boolean isStatic = (access & Opcodes.ACC_STATIC) != 0;
            if (isStatic && DESC_DEFERRED_REGISTER.equals(descriptor)) {
                OutputModel.RegistryHint hint = new OutputModel.RegistryHint(
                    owner, name, descriptor, model.mod.id,
                    new OutputModel.Src("STATIC", "LOW"));
                model.registries.add(hint);
            }
            return null;
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor,
                                         String signature, String[] exceptions) {
            return new MethodVisitor(Opcodes.ASM9) {
                @Override
                public AnnotationVisitor visitAnnotation(String md, boolean visible) {
                    if (!visible || !DESC_SUBSCRIBE.equals(md)) return null;
                    return new SemanticVisitor() {
                        @Override public void visitEnd() {
                            super.visitEnd();
                            String eventType = firstParamInternalName(descriptor);
                            model.events.add(new OutputModel.EventInfo(
                                owner, name, descriptor,
                                eventType == null ? "" : eventType,
                                new OutputModel.Src("STATIC", "MEDIUM")));
                        }
                    };
                }
            };
        }
    }

    /** 从方法描述符取第一个参数的内部类名：(Lcom/example/Foo;)V -> com/example/Foo。 */
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

    /** 极简注解值收集器（与 harddecode 同款思路，clean-room 重写）。 */
    private static class SemanticVisitor extends AnnotationVisitor {
        final java.util.Map<String, Object> values = new java.util.LinkedHashMap<>();

        SemanticVisitor() { super(Opcodes.ASM9); }

        @Override public void visit(String name, Object value) { values.put(name, value); }
        @Override public void visitEnum(String name, String descriptor, String v) { values.put(name, v); }
        @Override public AnnotationVisitor visitArray(String name) {
            java.util.List<Object> list = new java.util.ArrayList<>();
            values.put(name, list);
            return new AnnotationVisitor(Opcodes.ASM9) {
                @Override public void visit(String n, Object v) { list.add(v); }
                @Override public void visitEnum(String n, String d, String v) { list.add(v); }
            };
        }
        @Override public AnnotationVisitor visitAnnotation(String name, String descriptor) {
            SemanticVisitor nested = new SemanticVisitor();
            values.put(name, nested.values);
            return nested;
        }
    }
}
