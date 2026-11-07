package io.mirage.loader.sandbox;

import io.mirage.api.log.MirageLog;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.security.cert.Certificate;
import java.util.Set;

/**
 * 沙盒隔离 ClassLoader：只用于跑 NeoForge mod 主类的 &lt;clinit&gt; + 构造函数。
 *
 * <p>加载策略：
 * <ul>
 *   <li>{@code net.neoforged.*} 与 {@code net.minecraft.*} → 从 fat jar 的
 *       {@code mirage/sandbox-fake/<name>.class} 资源读 fake 字节码 {@code defineClass}，
 *       <b>绝不</b>委托 parent，避免碰真 MC / 运行时 stub；</li>
 *   <li>其余（mod 自己的类、第三方库、java.*）→ parent-first（app classloader），
 *       mod jar 通过 {@code URLClassLoader} 的 URL 提供；</li>
 *   <li>fake 未覆盖的 {@code net.*} 类 → 抛 {@link ClassNotFoundException}（诚实失败，
 *       由 {@link SandboxRecorder} 降级，不静默）。</li>
 * </ul>
 *
 * <p><b>MSR P1 起：mod 类不再走继承来的 {@code URLClassLoader.findClass}。</b>
 * 自己重写 {@code findClass}，读字节码后交给 {@link SandboxBytecodeGuard} 把
 * {@code System.exit} / {@code Runtime.exit|halt} 改道到 {@link SandboxGuard}——
 * 不改写的话，mod 构造里一句 {@code System.exit} 杀的是 launcher 进程本身。
 * 挂点必须是 {@code findClass} 而不是 {@code defineClass}：非 fake 包由
 * {@code super.loadClass} 进到父优先路径，mod 类由继承的 {@code findClass} 定义。
 *
 * <p>本 loader 由 {@code io.mirage.loader.sandbox} 类（fat jar，app classloader）创建，
 * fake 字节码里引用的 {@link Sink} 走 parent 加载，身份唯一；改写后字节码里引用的
 * {@link SandboxGuard} 同样走 parent，因此异常类型与记录器一致。
 */
public final class SandboxClassLoader extends URLClassLoader {

    /** fake 字节码在 fat jar 内的资源前缀。 */
    public static final String FAKE_PREFIX = "mirage/sandbox-fake/";

    /** fake 覆盖的包前缀（这些走 defineClass，其余走 parent）。 */
    private final Set<String> fakePackages;

    /**
     * fake 类的备用来源（MSR M2，2026-11-07）。可空 = 只有固定的 {@code mirage/sandbox-fake/} 表面。
     *
     * <p>存在的理由：固定表面只有 19 个类，喂真实 NeoForge mod 时 100% 失败
     * （JEI 缺 81 个 {@code net/neoforged/*}）。备用来源让 fake 复用本项目自己的
     * clean-room 兼容层 stub，见 {@link FakeProvider}。
     */
    private final FakeProvider fakeProvider;

    /** 本 loader 累计改写的调用处数（诊断用）。 */
    private int rewrittenCalls;

    /** 旧构造签名保留（provider = null，只有固定 fake 表面）——既有夹具与回归脚本依赖它。 */
    public SandboxClassLoader(URL[] modJarUrls, ClassLoader parent, Set<String> fakePackages) {
        this(modJarUrls, parent, fakePackages, null);
    }

    public SandboxClassLoader(URL[] modJarUrls, ClassLoader parent, Set<String> fakePackages,
                              FakeProvider fakeProvider) {
        super("MirageSandbox", modJarUrls, parent);
        this.fakePackages = fakePackages;
        this.fakeProvider = fakeProvider;
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            Class<?> c = findLoadedClass(name);
            if (c == null) {
                if (isFakePackage(name)) {
                    c = findFakeClass(name);
                } else {
                    c = super.loadClass(name, false);
                }
            }
            if (resolve) resolveClass(c);
            return c;
        }
    }

    /**
     * mod 类定义入口：读字节码 → 改写退出调用 → defineClass。
     *
     * <p>父优先已由 {@link #loadClass} 处理完毕，这里只负责本 loader 自己的 URL，
     * 所以用 {@code findResource}（不咨询 parent），避免把 fat jar 里的同名类误当 mod 类。
     */
    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        String path = name.replace('.', '/') + ".class";
        URL res = findResource(path);
        if (res == null) {
            throw new ClassNotFoundException(name);
        }
        byte[] bytes = readAll(res, name);

        byte[] out = bytes;
        int[] hits = new int[1];
        try {
            out = SandboxBytecodeGuard.rewrite(bytes, hits);
        } catch (Throwable t) {
            // 改写失败不能连累加载：如实告警，用原字节定义。
            // 后果是这颗类的退出调用没被拦（最坏情况退回 P0 行为），但记录不会因此中断。
            MirageLog.MIRAGE.warn("[Sandbox] 字节码改写失败，按原字节加载: " + name, t);
            hits[0] = 0;
        }
        if (hits[0] > 0) {
            synchronized (this) {
                rewrittenCalls += hits[0];
            }
            MirageLog.MIRAGE.debug("[Sandbox] 拦截退出调用 " + hits[0] + " 处: " + name);
        }
        return defineClass(name, out, 0, out.length, domainOf(res));
    }

    /** 本 loader 至今改写的调用处总数（沙盒结束后可读，用于"真的拦到了"的证据）。 */
    public synchronized int rewrittenCalls() {
        return rewrittenCalls;
    }

    private boolean isFakePackage(String name) {
        for (String pkg : fakePackages) {
            if (name.startsWith(pkg)) return true;
        }
        return false;
    }

    /**
     * 找 fake 类：两级来源（MSR M2）。
     *
     * <ol>
     *   <li>固定表面资源 {@code mirage/sandbox-fake/&lt;name&gt;.class}（19 个手工枚举的类）；</li>
     *   <li>{@link FakeProvider} 备用来源（复用 compat 层的 clean-room stub）。</li>
     * </ol>
     *
     * <p>两者都没有 ⇒ 抛 CNFE（保持原消息格式，既有断言不破）。
     * <b>顺序有意为之</b>：固定表面优先，保证手工维护的那套 fake 行为完全不变
     * （它有 {@code Sink} 埋点，能把 API 触达写进录制结果；compat stub 没有这些埋点）。
     */
    private Class<?> findFakeClass(String name) throws ClassNotFoundException {
        String path = FAKE_PREFIX + name.replace('.', '/') + ".class";
        URL res = getResource(path);
        if (res != null) {
            byte[] bytes = readAll(res, name);
            return defineClass(name, bytes, 0, bytes.length);
        }
        if (fakeProvider != null) {
            byte[] bytes = fakeProvider.findFakeBytes(name);
            if (bytes != null && bytes.length > 0) {
                return defineClass(name, bytes, 0, bytes.length);
            }
        }
        throw new ClassNotFoundException(
                "[MirageSandbox] fake 表面未覆盖: " + name + "（资源缺失 " + path + "）");
    }

    private static byte[] readAll(URL res, String name) throws ClassNotFoundException {
        try (InputStream in = res.openStream(); ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
            return bos.toByteArray();
        } catch (IOException e) {
            throw new ClassNotFoundException("读取字节码失败: " + name + " <- " + res, e);
        }
    }

    /**
     * 构造保护域，等价于 {@code URLClassLoader} 原本会做的事：
     * 让 mod 类拿到指向自己 jar 的 {@code CodeSource}。
     * 少数 mod 会用 {@code getProtectionDomain().getCodeSource().getLocation()} 定位资源，
     * 不补这一步会把它们从"能用"变成 NPE。
     */
    private ProtectionDomain domainOf(URL classResource) {
        try {
            String ext = classResource.toExternalForm();
            int bang = ext.indexOf("!/");
            // URL(String) 在 Java 20+ 已废弃：用 URI 转，路径里的空格在 toExternalForm 里已是 %20
            URL base = (bang > 0) ? java.net.URI.create(ext.substring(0, bang + 2)).toURL() : classResource;
            CodeSource cs = new CodeSource(base, (Certificate[]) null);
            return new ProtectionDomain(cs, null, this, null);
        } catch (Exception e) {
            return null;
        }
    }
}
