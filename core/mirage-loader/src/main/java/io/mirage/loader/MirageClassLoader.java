package io.mirage.loader;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 自定义 ClassLoader：在加载 net.minecraft.* 类时跑 MirageTransformer。
 * M3 PoC：验证"类加载期字节码织入"链路。
 */
public class MirageClassLoader extends URLClassLoader {

    static {
        ClassLoader.registerAsParallelCapable();
    }

    private final MirageTransformer transformer;

    public MirageClassLoader(URL[] urls, ClassLoader parent) {
        super("MirageClassLoader", urls, parent);
        this.transformer = new MirageTransformer();
    }

    /** 暴露 addURL，供 Launcher 把 Mod jar 动态加入 classpath */
    public void addJar(java.io.File jar) {
        try {
            addURL(jar.toURI().toURL());
        } catch (java.net.MalformedURLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        // io.mirage.* 强制走 SystemClassLoader，保证 Launcher 和 MC 渲染线程看到同一个类
        if (name.startsWith("io.mirage.")) {
            return ClassLoader.getSystemClassLoader().loadClass(name);
        }
        // java.*/javax.*/jdk.* 走 parent
        if (name.startsWith("java.")
                || name.startsWith("javax.") || name.startsWith("jdk.")) {
            return super.loadClass(name, resolve);
        }
        // net.minecraft.* 走我们的 findClass（带 transform）
        if (name.startsWith("net.minecraft.")) {
            synchronized (getClassLoadingLock(name)) {
                Class<?> c = findLoadedClass(name);
                if (c == null) {
                    c = findClass(name);
                }
                if (resolve) resolveClass(c);
                return c;
            }
        }
        return super.loadClass(name, resolve);
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        // 先读原始字节码
        byte[] original = readClassBytes(name);
        if (original == null) {
            return super.findClass(name);
        }
        // 1) MG 自研 ASM transformer（调试 UI、资源命名空间、创造模式分页等硬编码织入）
        byte[] transformed = transformer.transform(name, original);
        // 2) SpongePowered Mixin 管线（mod 通过 @Inject/@Redirect 织入目标 MC 类）
        //    Bootstrap 未就绪时该调用直接原样返回字节码，不影响现有行为。
        transformed = io.mirage.mixin.MirageMixinBootstrap.transform(name, transformed);
        return defineClass(name, transformed, 0, transformed.length);
    }

    private byte[] readClassBytes(String className) {
        String path = className.replace('.', '/') + ".class";
        URL res = getResource(path);
        if (res == null) return null;
        try (InputStream in = res.openStream();
             ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
            return bos.toByteArray();
        } catch (IOException e) {
            return null;
        }
    }
}
