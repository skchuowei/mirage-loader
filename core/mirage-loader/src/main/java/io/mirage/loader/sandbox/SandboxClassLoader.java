package io.mirage.loader.sandbox;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
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
 * <p>本 loader 由 {@code io.mirage.loader.sandbox} 类（fat jar，app classloader）创建，
 * fake 字节码里引用的 {@link Sink} 走 parent 加载，身份唯一。
 */
public final class SandboxClassLoader extends URLClassLoader {

    /** fake 字节码在 fat jar 内的资源前缀。 */
    public static final String FAKE_PREFIX = "mirage/sandbox-fake/";

    /** fake 覆盖的包前缀（这些走 defineClass，其余走 parent）。 */
    private final Set<String> fakePackages;

    public SandboxClassLoader(URL[] modJarUrls, ClassLoader parent, Set<String> fakePackages) {
        super("MirageSandbox", modJarUrls, parent);
        this.fakePackages = fakePackages;
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

    private boolean isFakePackage(String name) {
        for (String pkg : fakePackages) {
            if (name.startsWith(pkg)) return true;
        }
        return false;
    }

    private Class<?> findFakeClass(String name) throws ClassNotFoundException {
        String path = FAKE_PREFIX + name.replace('.', '/') + ".class";
        URL res = getResource(path);
        if (res == null) {
            throw new ClassNotFoundException(
                    "[MirageSandbox] fake 表面未覆盖: " + name + "（资源缺失 " + path + "）");
        }
        try (InputStream in = res.openStream(); ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
            byte[] bytes = bos.toByteArray();
            return defineClass(name, bytes, 0, bytes.length);
        } catch (IOException e) {
            throw new ClassNotFoundException("读取 fake 字节码失败: " + name, e);
        }
    }
}
