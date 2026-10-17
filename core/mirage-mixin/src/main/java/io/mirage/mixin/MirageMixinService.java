package io.mirage.mixin;

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
        // 对 mod 的 mixin config 做后处理：删掉 compatibilityLevel 字段，
        // 因为 Sponge Mixin 0.8.7 在 Java 25 下会拒绝 JAVA_17/21 的硬性声明。
        if (name.endsWith(".mixin.json") && !name.equals("mirage.mixins.json")) {
            try {
                String json = new String(raw.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                raw.close();
                // 删除 compatibilityLevel（Java 25 不支持声明的级别）
                json = json.replaceAll("\"compatibilityLevel\"\\s*:\\s*\"[^\"]*\"\\s*,?", "");
                // 删除 mixinextras（Sponge Mixin 0.8.7 不支持 MixinExtras），连同前面的逗号
                json = json.replaceAll(",?\\s*\"mixinextras\"\\s*:\\s*\\{[^}]*\\}", "");
                System.out.println("[Mirage-Mixin] 后处理 mixin config: " + name);
                return new java.io.ByteArrayInputStream(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            } catch (Exception e) {
                System.err.println("[Mirage-Mixin] 后处理 " + name + " 失败: " + e);
            }
        }
        return raw;
    }

    /** 暴露给 Bootstrap：拿到 MixinBootstrap.init() 后 offer 进来的 transformer factory。 */
    public IMixinTransformerFactory getTransformerFactory() {
        return getInternal(IMixinTransformerFactory.class);
    }
}
