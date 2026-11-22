package io.mirage.mixin;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import io.mirage.api.log.MirageLog;

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * mod {@code @Mixin} 目标类收集器（服务 {@link WeavePriority} 的让路判定）。
 *
 * <h2>为什么需要这一步</h2>
 * 「MG 自研织入与 mod {@code @Mixin} 目标求交集」要求先知道 <em>mod 织入了哪些类</em>。
 * 这个信息不在 config 的数组里——config 的 {@code mixins}/{@code client}/{@code server} 列的是
 * <strong>mixin 类</strong>（相对 {@code package}），真正的目标写在 mixin 类上的
 * {@code @org.spongepowered.asm.mixin.Mixin}：{@code value = Class[]}（编译期类字面量）或
 * {@code targets = String[]}（字符串目标）。所以链路是：
 *
 * <pre>
 * mixin config 资源 (JSON)  →  package + mixins/client/server  →  mixin 类名
 *      →  MCL 读该 mixin 类字节码  →  ASM 读 {@code @Mixin} 注解  →  目标类名集合
 * </pre>
 *
 * <h2>两个实现要点</h2>
 * <ul>
 *   <li><strong>{@code @Mixin} 是 {@code RetentionPolicy.CLASS}</strong>（javap 核实），落在
 *       {@code RuntimeInvisibleAnnotations}；ASM 的 {@code ClassVisitor.visitAnnotation(desc, visible)}
 *       对可见/不可见注解<strong>都会回调</strong>，因此按 desc 匹配即可，不必关心 {@code visible}。</li>
 *   <li><strong>资源读取留了一个接缝</strong>：{@link #collect(ClassLoader, Iterable)} 委派到
 *       {@link #collect(Function, Iterable)}。门禁（{@code scripts/verify_mixin_order.py}）用内存资源喂后者，
 *       不必真的造 jar 就能测「JSON 解析 + 注解读取」这两段逻辑。</li>
 * </ul>
 *
 * <h2>失败策略（降级，不阻断）</h2>
 * 单个 config 读不到 / JSON 坏掉 / 单个 mixin 类解析失败 → 一条 WARN + 继续下一个。
 * 整轮收集抛异常 → 由调用方（{@code MirageMixinBootstrap.init}）WARN 后按空集处理，
 * 等价于「不让路、保持老行为」。这与 §16 禁 3（禁止静默跳过失败的 Mixin）不冲突：
 * 被跳过的不是 mixin 织入本身，而是让路判定的<strong>观测能力</strong>，且每次都留痕。
 */
public final class ModMixinTargets {

    /** MG 自己的 mixin 配置：不算 mod（见 {@link WeavePriority} 类注释「逻辑自噬」）。 */
    public static final String MG_OWN_CONFIG = "mirage.mixins.json";

    /** {@code @Mixin} 注解描述符（Retention.CLASS ⇒ RuntimeInvisible，visitAnnotation 仍回调）。 */
    private static final String MIXIN_ANNOTATION_DESC = "Lorg/spongepowered/asm/mixin/Mixin;";

    private ModMixinTargets() {
    }

    /**
     * 从（Mirage）classpath 读 config 与 mixin 类字节码，收集 mod {@code @Mixin} 的目标类。
     *
     * @param mcl     持有 mod jar 的类加载器（{@code MirageClassLoader}）
     * @param configs mixin config 的<strong>完整资源路径</strong>（与传给 Mixin 的注册名同一份）
     * @return 目标类名集合（点分、保序去重）；永不返回 null
     */
    public static Set<String> collect(ClassLoader mcl, Iterable<String> configs) {
        return collect(name -> readResource(mcl, name), configs);
    }

    /**
     * 纯逻辑版本：资源读取由调用方提供（{@code name → 字节数组，缺失返回 null}）。
     */
    public static Set<String> collect(Function<String, byte[]> resources, Iterable<String> configs) {
        Set<String> targets = new LinkedHashSet<>();
        if (resources == null || configs == null) {
            return targets;
        }
        for (String config : configs) {
            if (config == null || config.isEmpty() || isMgOwnConfig(config)) {
                continue;
            }
            byte[] json = safeGet(resources, config);
            if (json == null) {
                MirageLog.MIXIN.warn("mixin config 读不到，跳过让路判定的目标收集: " + config);
                continue;
            }
            List<String> mixinClasses;
            try {
                mixinClasses = mixinClassNames(new String(json, StandardCharsets.UTF_8));
            } catch (Throwable t) {
                MirageLog.MIXIN.warn("mixin config 解析失败，跳过目标收集: " + config, t);
                continue;
            }
            for (String mixinClass : mixinClasses) {
                byte[] bytes = safeGet(resources, mixinClass.replace('.', '/') + ".class");
                if (bytes == null) {
                    // 常见原因：mod 声明的 mixin 类没打进 jar、或需要别的 mod 才能解析。
                    // Mixin 自己也会在处理该 config 时报错；这里只影响让路判定的完整性。
                    MirageLog.MIXIN.warn("mixin 类读不到，跳过: " + mixinClass + "（来自 " + config + "）");
                    continue;
                }
                try {
                    targets.addAll(targetsOf(bytes));
                } catch (Throwable t) {
                    MirageLog.MIXIN.warn("mixin 类注解读取失败，跳过: " + mixinClass, t);
                }
            }
        }
        return targets;
    }

    /** MG 自己的 config 不属于 mod（点分路径与带子目录的形态都认）。 */
    public static boolean isMgOwnConfig(String config) {
        return config != null
                && (config.equals(MG_OWN_CONFIG) || config.endsWith("/" + MG_OWN_CONFIG));
    }

    /**
     * 从 mixin config JSON 里取出 mixin <strong>类</strong>全名（已补 {@code package} 前缀）。
     *
     * @throws RuntimeException JSON 非法时（由调用方降级）
     */
    public static List<String> mixinClassNames(String json) {
        List<String> out = new ArrayList<>();
        JsonElement root = JsonParser.parseString(json);
        if (root == null || !root.isJsonObject()) {
            return out;
        }
        JsonObject obj = root.getAsJsonObject();
        String pkg = "";
        JsonElement pkgEl = obj.get("package");
        if (pkgEl != null && pkgEl.isJsonPrimitive()) {
            pkg = pkgEl.getAsString().replace('/', '.').trim();
        }
        for (String key : new String[]{"mixins", "client", "server"}) {
            JsonElement arr = obj.get(key);
            if (arr == null || !arr.isJsonArray()) {
                continue;
            }
            for (JsonElement e : arr.getAsJsonArray()) {
                if (e == null || !e.isJsonPrimitive()) {
                    continue;
                }
                String simple = e.getAsString().replace('/', '.').trim();
                if (simple.isEmpty()) {
                    continue;
                }
                String fqcn = (!pkg.isEmpty() && !simple.startsWith(pkg + ".")) ? pkg + "." + simple : simple;
                out.add(fqcn);
            }
        }
        return out;
    }

    /**
     * 读一个 mixin 类的 {@code @Mixin} 注解，返回它声明的目标类（点分）。
     *
     * <p>同时支持 {@code value}（{@code Class[]}，ASM 侧是 {@link Type}）与
     * {@code targets}（{@code String[]}）；两者都没有则返回空集（Mixin 会在应用期自行报错）。
     */
    public static Set<String> targetsOf(byte[] mixinClassBytes) {
        Set<String> out = new LinkedHashSet<>();
        ClassReader cr = new ClassReader(mixinClassBytes);
        cr.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                if (!MIXIN_ANNOTATION_DESC.equals(descriptor)) {
                    return super.visitAnnotation(descriptor, visible);
                }
                return new AnnotationVisitor(Opcodes.ASM9) {
                    @Override
                    public void visit(String name, Object value) {
                        addIfTargetName(out, name, value);
                    }

                    @Override
                    public AnnotationVisitor visitArray(String name) {
                        return new AnnotationVisitor(Opcodes.ASM9) {
                            @Override
                            public void visit(String ignored, Object value) {
                                addIfTargetName(out, name, value);
                            }
                        };
                    }
                };
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return out;
    }

    /** {@code @Mixin} 的 {@code value}/{@code targets} 元素 → 目标类名（其它元素忽略）。 */
    private static void addIfTargetName(Set<String> out, String elementName, Object value) {
        if (value == null || (!"value".equals(elementName) && !"targets".equals(elementName))) {
            return;
        }
        if (value instanceof Type) {
            out.add(((Type) value).getClassName());
        } else if (value instanceof String) {
            String s = ((String) value).replace('/', '.').trim();
            if (!s.isEmpty()) {
                out.add(s);
            }
        } else if (value instanceof Type[]) {
            for (Type t : (Type[]) value) {
                out.add(t.getClassName());
            }
        } else if (value instanceof String[]) {
            for (String s : (String[]) value) {
                String n = s.replace('/', '.').trim();
                if (!n.isEmpty()) {
                    out.add(n);
                }
            }
        } else if (value instanceof Collection) {
            for (Object o : (Collection<?>) value) {
                addIfTargetName(out, elementName, o);
            }
        }
    }

    private static byte[] safeGet(Function<String, byte[]> resources, String name) {
        try {
            return resources.apply(name);
        } catch (Throwable t) {
            return null;
        }
    }

    private static byte[] readResource(ClassLoader mcl, String name) {
        byte[] fromMcl = readAll(mcl, name);
        if (fromMcl != null) {
            return fromMcl;
        }
        return readAll(ClassLoader.getSystemClassLoader(), name);
    }

    private static byte[] readAll(ClassLoader cl, String name) {
        if (cl == null) {
            return null;
        }
        try (InputStream in = cl.getResourceAsStream(name)) {
            return in == null ? null : in.readAllBytes();
        } catch (IOException e) {
            return null;
        }
    }
}
