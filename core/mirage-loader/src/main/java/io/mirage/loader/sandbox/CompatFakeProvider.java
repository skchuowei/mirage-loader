package io.mirage.loader.sandbox;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/**
 * {@link FakeProvider} 的默认实现：从 <b>本项目自己的</b> clean-room 兼容层 stub 读字节码。
 *
 * <p>读的是 {@code modules/mirage-neoforge-compat} 编译产物里的
 * {@code net/neoforged/**.class}（以及 {@code net/minecraft/**.class}，如果将来也补了）。
 * <b>不是</b> NeoForge 的字节码 —— 那些 stub 是本项目按 {@code javap} 提取的签名自己实现的，
 * 不构成复制第三方实现（见 AGENTS 合规边界）。
 *
 * <p><b>为什么用「读资源」而不是「反射加载」</b>：沙盒里的 fake 类必须由
 * {@link SandboxClassLoader} 自己 {@code defineClass}，否则会与 parent（app classloader）
 * 产生<b>两份同名类</b>，mod 传一个 stub 实例给沙盒外的代码会 {@code ClassCastException}，
 * 甚至 {@code LinkageError}。读字节码 → 自己定义，是唯一安全的形式。
 *
 * <p><b>查找方式</b>：用 {@link ClassLoader#getResourceAsStream}，注意要用
 * <b>定义 compat stub 的那个 classloader</b>（通常是 app classloader），不能用本类的
 * {@code getClass().getClassLoader()} —— 后者在某些 classpath 布局下可能查不到
 * （与 {@code FakeSurface.collectFromJarUrl} 踩过的同一个坑：资源定位方式决定成败）。
 */
public final class CompatFakeProvider implements FakeProvider {

    /** 参与 fake 的包前缀：只有这些包才允许从 compat stub 取字节码。 */
    private static final String[] FAKE_PACKAGES = {
        "net.neoforged.",
        "net.minecraft.",
    };

    private final ClassLoader source;

    /**
     * @param source 定义 compat stub 的 classloader；{@code null} 时用本类的 classloader
     */
    public CompatFakeProvider(ClassLoader source) {
        this.source = (source != null) ? source : CompatFakeProvider.class.getClassLoader();
    }

    /** 用默认 classloader（app classloader）的构造。 */
    public CompatFakeProvider() {
        this(null);
    }

    @Override
    public byte[] findFakeBytes(String name) {
        if (name == null || name.isEmpty()) return null;
        if (!isFakePackage(name)) return null;
        String path = name.replace('.', '/') + ".class";
        try (InputStream in = source.getResourceAsStream(path)) {
            if (in == null) return null;
            ByteArrayOutputStream bos = new ByteArrayOutputStream(4096);
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
            return bos.toByteArray();
        } catch (Exception e) {
            // 读不到就当"没有"，让调用方走下一个来源并最终诚实抛 CNFE
            return null;
        }
    }

    private static boolean isFakePackage(String name) {
        for (String p : FAKE_PACKAGES) {
            if (name.startsWith(p)) return true;
        }
        return false;
    }
}
