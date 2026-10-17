package io.mirage.mixin;

import org.spongepowered.asm.launch.MixinBootstrap;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.transformer.IMixinTransformer;
import org.spongepowered.asm.service.MixinService;

/**
 * Mirage 侧 Mixin 引导入口。
 */
public final class MirageMixinBootstrap {

    private static volatile IMixinTransformer transformer;
    private static volatile boolean initialized;

    private MirageMixinBootstrap() {}

    public static synchronized void init(ClassLoader mcl, Iterable<String> configs) {
        if (initialized) {
            return;
        }
        ClassLoader prevTcl = Thread.currentThread().getContextClassLoader();
        try {
            Thread.currentThread().setContextClassLoader(mcl);
            MirageMixinService.bind(mcl);

            System.out.println("[Mirage-Mixin] MixinBootstrap.init() ...");
            MixinBootstrap.init();

            MixinEnvironment env = MixinEnvironment.getDefaultEnvironment();
            env.setSide(MixinEnvironment.Side.CLIENT);

            // 先创建 transformer，再注册 config
            MirageMixinService svc = (MirageMixinService) MixinService.getService();
            transformer = svc.getTransformerFactory().createTransformer();
            env.setActiveTransformer(transformer);

            for (String c : configs) {
                env.addConfiguration(c);
                System.out.println("[Mirage-Mixin] 注册 mixin config: " + c);
            }

            // 强制 processor 加载 pendingConfigs（在我们的环境里 addConfiguration 后不会自动触发）
            try {
                java.lang.reflect.Field pf = transformer.getClass().getDeclaredField("processor");
                pf.setAccessible(true);
                Object processor = pf.get(transformer);
                java.lang.reflect.Method selectMethod = processor.getClass().getDeclaredMethod("select", MixinEnvironment.class);
                selectMethod.setAccessible(true);
                selectMethod.invoke(processor, env);
            } catch (Throwable t) {
                System.err.println("[Mirage-Mixin] 触发 config 加载失败: " + t);
            }

            initialized = true;
        } finally {
            Thread.currentThread().setContextClassLoader(prevTcl);
        }
    }

    private static int transformCount = 0;

    public static byte[] transform(String name, byte[] basicClass) {
        if (basicClass == null) return basicClass;
        try {
            byte[] result = transformer.transformClassBytes(name, name, basicClass);
            transformCount++;
            boolean changed = (result != null && result != basicClass && result.length != basicClass.length);
            if (changed || transformCount <= 5) {
                System.out.println("[Mirage-Mixin] transform #" + transformCount + ": " + name
                    + " in=" + basicClass.length + " out=" + (result == null ? "null" : result.length)
                    + (changed ? " [MODIFIED]" : ""));
            }
            return result;
        } catch (Throwable th) {
            System.err.println("[Mirage-Mixin] 转换 " + name + " 失败: " + th);
            return basicClass;
        }
    }

    public static boolean isReady() {
        return transformer != null;
    }
}
