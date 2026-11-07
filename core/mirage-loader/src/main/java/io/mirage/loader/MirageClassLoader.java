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
        // io.mirage.* 强制走 SystemClassLoader，保证 Launcher 和 MC 渲染线程看到同一个类。
        // 例外 1：io.mirage.neoforge.* 是 NeoForge 兼容层运行时实现（NeoEventBusImpl 等），
        // 必须和 mod 侧用同一个 MirageClassLoader 加载，否则它引用的 net.minecraft.* 类
        // 会出现"app loader 与 MirageClassLoader 各加载一份"的 loader constraint violation。
        // 例外 2（2026-10-31）：io.mirage.fabric.* 同理——Fabric 事件桥（FabricLifecycleBridge 等）
        // 被 mcl 侧织入代码调用，一旦由 app 加载，它引用 net.fabricmc.* 时也由 app 解析，
        // 而 net.fabricmc stub 的 <clinit> 会引用 net.minecraft.* → app 再从 classpath 自己
        // defineClass 一份 MinecraftServer → LinkageError（loader constraint violation）。
        // 识别特征：栈帧里出现 `ServerLifecycleEvents.<clinit>` → `FabricLifecycleBridge.fireServerStarting`
        // 且报 "loader 'app' wants to load abstract class net.minecraft.server.MinecraftServer"。
        // 例外 3（2026-10-31，MGScript）：io.mirage.mgscript.* 是脚本引擎与它的 MC 绑定层。
        // 绑定层直接引用 net.minecraft.*（构造 Item/Block、订阅 tick 事件），一旦由 app 加载，
        // 它引用的 MC 类会被 app 从启动 classpath 再 define 一份 → 与 MCL 版本冲突，
        // 症状是 LinkageError: loader constraint violation，且栈帧指向 net.minecraft.*，
        // 极难倒推。与前两个例外同因，增删此名单前先读这段。
        if (name.startsWith("io.mirage.")
                && !name.startsWith("io.mirage.neoforge.")
                && !name.startsWith("io.mirage.fabric.")
                && !name.startsWith("io.mirage.mgscript.")) {
            return ClassLoader.getSystemClassLoader().loadClass(name);
        }
        // org.lwjgl.* 强制走 SystemClassLoader（2026-10-31，视角转不动回归）。
        // 本 loader 的 parent 是 platform loader（不含 lwjgl），URL 却是整条游戏 classpath，
        // 于是 MCL 会自己 define 一份 org.lwjgl.glfw.GLFW——与早期窗口（AppClassLoader 那份）
        // 形成两份 GLFW 类、各自绑定一份 glfw.dll 模块（日志特征：MemoryUtil 被
        // lwjgl-unsafe-agent 重写两次，sameGlfwClass=false）。后果：窗口由 GLFW-A 创建，
        // MC 的 glfwSetInputMode(GLFW_CURSOR_DISABLED) 打在 GLFW-B 上，只把 B 的
        // _glfw.win32.disabledCursorWindow 置位；真正处理 WM_MOUSEMOVE 的是 A 的 WndProc，
        // 它查 A 的 disabledCursorWindow（仍为 NULL）直接丢弃——光标事件零派发、视角转不动，
        // 而 WM_LBUTTONDOWN/WM_KEYDOWN 不查该标记所以点击和键盘正常。与 io.mirage.* 同因，
        // 统一 loader 后两份 GLFW 合一、_glfw 全局共享，输入恢复。
        if (name.startsWith("org.lwjgl.")) {
            return ClassLoader.getSystemClassLoader().loadClass(name);
        }
        // java.*/javax.*/jdk.* 走 parent
        if (name.startsWith("java.")
                || name.startsWith("javax.") || name.startsWith("jdk.")) {
            return super.loadClass(name, resolve);
        }
        // net.minecraft.* / net.neoforged.* / io.mirage.neoforge.* 走 findClass（MirageClassLoader
        // 自己加载）：NeoForge stub（IEventBus / DeferredRegister / FML*）必须与 mod 引用同 loader，
        // 否则 DeferredRegister 里 Class.forName(net.minecraft...) 拿到 app loader 版本，与 mod 侧
        // MirageClassLoader 版本形成两份 ResourceKey/Block/Item，触发 LinkageError。
        if (name.startsWith("net.minecraft.")
                || name.startsWith("net.neoforged.")
                || name.startsWith("io.mirage.neoforge.")) {
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
        // 仪表盘真实计数：每有一个类经过 Mixin 管线就 +1（分母未知，故只计数不画百分比）。
        LoadProgress.increment("mixin.classes");
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
