package io.mirage.loader.sandbox;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Stream;

/**
 * fake pack 来源（MSR P4，2026-11-08）：从<b>构建期生成的目录</b>读 stub 字节码。
 *
 * <p><b>为什么需要第三个来源</b>：固定表面（{@code mirage/sandbox-fake/}，19 个手工类）
 * 与 compat stub 都覆盖不到「真实 mod 用到、而加载器还没实现」的类。P4 的生成器
 * （{@link io.mirage.loader.FakeStubGenerator}）能在构建期按真实 mod 的缺口批量产出骨架类，
 * 这些类需要一个运行期入口 —— 就是这个 provider。
 *
 * <p><b>默认关闭</b>：只有显式给 {@code -Dmirage.msr.fakepack=<dir>} 才生效。
 * 理由与「cache 不许静默变更」同源：生成物是<b>构建期产物</b>，它的存在与否会改变录制结果
 * （某些类从 {@code missing_fake} 变成 {@code stub_incomplete}），必须让运行日志能说清
 * 「这次录制是带 pack 还是不带」。
 *
 * <p><b>顺序</b>：{@link SandboxClassLoader} 的查找链是
 * 固定表面 → {@link CompatFakeProvider} → 本 provider。compat stub 有真正的桥接逻辑
 * （转发到 MG 的 DeferredRegister 等），必须优先于生成的空架子，否则会把能跑的功能打瘸。
 *
 * <p><b>不做什么</b>：不改生成物、不做签名校验（那是门禁的事）、不允许 {@code ..} 逃逸，
 * 也不允许超出 fake 包域的名字（防止 pack 里混进 {@code java.*} 之类的东西遮蔽 JDK）。
 */
public final class FakePackProvider implements FakeProvider {

    /** 系统属性名：显式指定 pack 目录（优先级最高，行为同 2026-11-08 初版）。 */
    public static final String PROPERTY = "mirage.msr.fakepack";

    /**
     * 系统属性名：fake pack 的「家目录」。
     *
     * <p>MSR P4b（2026-11-11）：生成器把按需生成的 pack 写到这个目录后，录制端<b>无需再手填</b>
     * {@link #PROPERTY} 就能自动发现它。语义是「这个目录本身就是一个 pack」（生成器直接写进
     * {@code <home>/net/neoforged/...}）。两个属性都没给时返回 {@code null} —— 绝不偷偷扫全盘，
     * 避免在任何环境里意外加载一份来历不明的 pack 把 classpath 污染了。
     */
    public static final String HOME_PROPERTY = "mirage.msr.fakepack.home";

    /** 允许的包域：与 {@link CompatFakeProvider} 一致，只这两片。 */
    private static final String[] ALLOWED = { "net.neoforged.", "net.minecraft." };

    private final Path root;
    private final int classCount;

    public FakePackProvider(Path root) throws IOException {
        this.root = root.toAbsolutePath().normalize();
        if (!Files.isDirectory(this.root)) {
            throw new IOException("fake pack 不是目录: " + this.root);
        }
        try (Stream<Path> s = Files.walk(this.root)) {
            this.classCount = (int) s.filter(p -> p.toString().endsWith(".class")).count();
        }
    }

    /**
     * 按系统属性取 provider。解析顺序：
     * <ol>
     *   <li>{@link #PROPERTY} 显式指定 pack 目录（给定但不存在 → 返回 {@code null}，让调用方 WARN）；</li>
     *   <li>{@link #HOME_PROPERTY} 指定「家目录」，且该目录本身是个 pack（存在且含 .class）→ 用家目录；</li>
     *   <li>都没给 → 返回 {@code null}（调用方只挂 compat stub）。</li>
     * </ol>
     *
     * @return 解析不到有效 pack 时返回 {@code null}（调用方据此跳过这一级）
     */
    public static FakeProvider fromSystemProperty() {
        String v = System.getProperty(PROPERTY);
        if (v != null && !v.isBlank()) {
            Path p = Paths.get(v);
            if (!Files.isDirectory(p)) return null;
            try {
                return new FakePackProvider(p);
            } catch (IOException e) {
                return null;
            }
        }
        String home = System.getProperty(HOME_PROPERTY);
        if (home != null && !home.isBlank()) {
            Path hp = Paths.get(home);
            if (Files.isDirectory(hp)) {
                try {
                    return new FakePackProvider(hp);
                } catch (IOException e) {
                    return null;
                }
            }
        }
        return null;
    }

    /** pack 里的类文件数（写日志用）。 */
    public int classCount() {
        return classCount;
    }

    public Path root() {
        return root;
    }

    @Override
    public byte[] findFakeBytes(String name) {
        if (name == null || name.isEmpty() || name.indexOf("..") >= 0) return null;
        if (!isAllowed(name)) return null;
        Path f = root.resolve(name.replace('.', '/') + ".class").normalize();
        if (!f.startsWith(root) || !Files.isRegularFile(f)) return null;
        try {
            return Files.readAllBytes(f);
        } catch (IOException e) {
            return null;
        }
    }

    private static boolean isAllowed(String name) {
        for (String p : ALLOWED) {
            if (name.startsWith(p)) return true;
        }
        return false;
    }
}
