package io.mirage.mixin;

import io.mirage.api.log.MirageLog;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.launch.platform.container.IContainerHandle;
import org.spongepowered.asm.service.IClassBytecodeProvider;
import org.spongepowered.asm.service.IClassProvider;
import org.spongepowered.asm.service.IClassTracker;
import org.spongepowered.asm.service.IMixinAuditTrail;
import org.spongepowered.asm.service.IMixinInternal;
import org.spongepowered.asm.service.ITransformer;
import org.spongepowered.asm.service.ITransformerProvider;
import org.spongepowered.asm.service.MixinServiceAbstract;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.transformer.IMixinTransformerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;
import java.util.List;

/**
 * Mirage 侧的 IMixinService 实现。
 *
 * Sponge Mixin 通过 Java SPI（META-INF/services）发现 IMixinService。
 * 内置的 LaunchWrapper / ModLauncher 实现都依赖特定启动器，在 MG 环境里 isValid() 会返回 false；
 * 本类作为兜底，桥接到 MirageClassLoader。
 *
 * 只实现 PoC 所需的最小子集：资源查找、类查找、空的 transformer/tracker/audit。
 */
public class MirageMixinService extends MixinServiceAbstract
        implements IClassProvider, IClassBytecodeProvider, ITransformerProvider, IClassTracker, IMixinAuditTrail {

    static ClassLoader mcl;

    public static void bind(ClassLoader classLoader) {
        mcl = classLoader;
    }

    @Override public String getName() { return "mirage"; }
    @Override public boolean isValid() { return true; } // mcl 可能在 ServiceLoader 实例化后才绑定，这里始终返回 true
    @Override public MixinEnvironment.Phase getInitialPhase() { return MixinEnvironment.Phase.PREINIT; }

    @Override public MixinEnvironment.CompatibilityLevel getMinCompatibilityLevel() {
        return MixinEnvironment.CompatibilityLevel.JAVA_8;
    }
    @Override public MixinEnvironment.CompatibilityLevel getMaxCompatibilityLevel() {
        return MixinEnvironment.CompatibilityLevel.JAVA_21;
    }

    // ---- IClassProvider ----
    @Override public java.net.URL[] getClassPath() {
        try {
            return ((java.net.URLClassLoader) mcl).getURLs();
        } catch (Throwable t) {
            return new java.net.URL[0];
        }
    }
    @Override public Class<?> findClass(String name) throws ClassNotFoundException {
        return Class.forName(name, false, mcl);
    }
    @Override public Class<?> findClass(String name, boolean initialize) throws ClassNotFoundException {
        return Class.forName(name, initialize, mcl);
    }
    @Override public Class<?> findAgentClass(String name, boolean initialize) throws ClassNotFoundException {
        return Class.forName(name, initialize, mcl);
    }

    // ---- IClassBytecodeProvider ----
    @Override public ClassNode getClassNode(String name) throws ClassNotFoundException, IOException {
        return getClassNode(name, false, 0);
    }
    @Override public ClassNode getClassNode(String name, boolean runTransformers)
            throws ClassNotFoundException, IOException {
        return getClassNode(name, runTransformers, 0);
    }
    @Override public ClassNode getClassNode(String name, boolean runTransformers, int readerFlags)
            throws ClassNotFoundException, IOException {
        String path = name.replace('.', '/') + ".class";
        byte[] bytes;
        try (InputStream in = mcl.getResourceAsStream(path)) {
            if (in == null) throw new ClassNotFoundException(name);
            bytes = in.readAllBytes();
        }
        org.objectweb.asm.ClassReader cr = new org.objectweb.asm.ClassReader(bytes);
        ClassNode node = new ClassNode(org.objectweb.asm.Opcodes.ASM9);
        cr.accept(node, readerFlags);
        return node;
    }

    // ---- ITransformerProvider ----
    @Override public Collection<ITransformer> getTransformers() { return List.of(); }
    @Override public List<ITransformer> getDelegatedTransformers() { return List.of(); }
    @Override public void addTransformerExclusion(String name) { /* no-op */ }

    // ---- IClassTracker ----
    @Override public void registerInvalidClass(String className) { /* no-op */ }
    @Override public boolean isClassLoaded(String className) { return false; }
    @Override public String getClassRestrictions(String className) { return ""; }

    // ---- IMixinAuditTrail ----
    @Override public void onApply(String mixin, String target) { /* no-op */ }
    @Override public void onPostProcess(String target) { /* no-op */ }
    @Override public void onGenerate(String name, String unneeded) { /* no-op */ }

    // ---- IMixinService 其余方法 ----
    @Override public IClassProvider getClassProvider() { return this; }
    @Override public IClassBytecodeProvider getBytecodeProvider() { return this; }
    @Override public ITransformerProvider getTransformerProvider() { return this; }
    @Override public IClassTracker getClassTracker() { return this; }
    @Override public IMixinAuditTrail getAuditTrail() { return this; }

    @Override public Collection<String> getPlatformAgents() { return List.of(); }
    @Override public IContainerHandle getPrimaryContainer() { return new MirageContainerHandle(); }
    @Override public Collection<IContainerHandle> getMixinContainers() { return List.of(); }

    @Override public InputStream getResourceAsStream(String name) {
        InputStream raw = null;
        if (mcl != null) {
            raw = mcl.getResourceAsStream(name);
        }
        if (raw == null) {
            raw = ClassLoader.getSystemResourceAsStream(name);
        }
        if (raw == null) return null;
        // 对 mod 的 mixin config 做后处理（2026-11-22 修正后缀匹配：单数 .mixin.json 与
        // 复数 .mixins.json 都要走——此前只认单数，mod 常见的 *.mixins.json 整个绕过剥离，
        // compatibilityLevel: JAVA_21 直接 MixinInitialisationError，探针已复现）。
        if ((name.endsWith(".mixin.json") || name.endsWith(".mixins.json"))
                && !name.equals("mirage.mixins.json")) {
            try {
                String json = new String(raw.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                raw.close();
                // mixinextras.minVersion 前置校验（2026-11-22，用户拍板：不满足启动致命）。
                // 键本身保留不剥——MixinExtras 自己的 MixinConfigUtils 也会读它。
                checkMixinExtrasMinVersion(name, json);
                // 删除 compatibilityLevel（Java 25 不支持声明的级别）
                json = json.replaceAll("\"compatibilityLevel\"\\s*:\\s*\"[^\"]*\"\\s*,?", "");
                MirageLog.MIXIN.info("后处理 mixin config: " + name);
                return new java.io.ByteArrayInputStream(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            } catch (Exception e) {
                // 注意：minVersion 校验抛的是 Error（不是 Exception），不会进这里、不会被降级吞掉。
                MirageLog.MIXIN.warn("后处理 " + name + " 失败", e);
            }
        }
        return raw;
    }

    /**
     * 校验 mod mixin config 声明的 {@code mixinextras.minVersion} 是否被内置版本满足。
     *
     * <p>不满足 → {@code [MG-FATAL]} + 抛 Error 让启动失败（宁可响亮地死不要静默地坏：
     * 放过去的话该 mod 的 MixinExtras 注入器会静默失效）。满足 → INFO 记一笔。
     * 无声明 / 无 minVersion 字段 → 不动作。版本串解析不了 → WARN 放行（不因怪串杀启动）。
     */
    private static void checkMixinExtrasMinVersion(String name, String json) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"mixinextras\"\\s*:\\s*\\{[^}]*?\"minVersion\"\\s*:\\s*\"([^\"]+)\"")
                .matcher(json);
        if (!m.find()) {
            return;
        }
        String required = m.group(1);
        String bundled;
        try {
            bundled = com.llamalad7.mixinextras.MixinExtrasBootstrap.getVersion();
        } catch (Throwable t) {
            throw new Error("[MG-FATAL] mixin config " + name + " 要求 mixinextras >= " + required
                    + "，但 MG 未内置 MixinExtras —— 该 config 的注入器会静默失效，这里选择让启动失败");
        }
        if (bundled == null) bundled = "0";
        if (versionLess(bundled, required)) {
            io.mirage.api.error.MirageErrors.fatal("mixin config " + name + " 要求 mixinextras >= " + required
                    + "，MG 内置 " + bundled + " —— 版本不满足会让该 mod 的注入器静默失效，这里选择让启动失败");
            throw new Error("mixinextras.minVersion not satisfied: config " + name + " requires >= " + required
                    + ", bundled " + bundled);
        }
        MirageLog.MIXIN.info("mixin config " + name + " 要求 mixinextras >= " + required
                + "，内置 " + bundled + "，满足");
    }

    /** 逐数字段比较 "a < b"；任一段解析失败退回 String.compareTo < 0。 */
    private static boolean versionLess(String a, String b) {
        String[] pa = a.split("[.+_-]");
        String[] pb = b.split("[.+_-]");
        int n = Math.max(pa.length, pb.length);
        for (int i = 0; i < n; i++) {
            String sa = i < pa.length ? pa[i] : "0";
            String sb = i < pb.length ? pb[i] : "0";
            int cmp;
            try {
                cmp = Integer.compare(Integer.parseInt(sa), Integer.parseInt(sb));
            } catch (NumberFormatException e) {
                cmp = sa.compareTo(sb);
            }
            if (cmp != 0) {
                return cmp < 0;
            }
        }
        return false;
    }

    /** 暴露给 Bootstrap：拿到 MixinBootstrap.init() 后 offer 进来的 transformer factory。 */
    public IMixinTransformerFactory getTransformerFactory() {
        return getInternal(IMixinTransformerFactory.class);
    }
}
