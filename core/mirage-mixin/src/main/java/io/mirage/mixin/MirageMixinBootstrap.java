package io.mirage.mixin;

import io.mirage.api.log.MirageLog;

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

            MirageLog.MIXIN.info("MixinBootstrap.init() ...");
            MixinBootstrap.init();

            MixinEnvironment env = MixinEnvironment.getDefaultEnvironment();
            env.setSide(MixinEnvironment.Side.CLIENT);

            // 先创建 transformer，再注册 config
            MirageMixinService svc = (MirageMixinService) MixinService.getService();
            transformer = svc.getTransformerFactory().createTransformer();
            env.setActiveTransformer(transformer);

            for (String c : configs) {
                env.addConfiguration(c);
                MirageLog.MIXIN.info("注册 mixin config: " + c);
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
                MirageLog.MIXIN.warn("触发 config 加载失败", t);
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
                MirageLog.MIXIN.info("transform #" + transformCount + ": " + name
                    + " in=" + basicClass.length + " out=" + (result == null ? "null" : result.length)
                    + (changed ? " [MODIFIED]" : ""));
            }
            return result;
        } catch (Throwable th) {
            // 关键织入失败（注入点写错、目标类不存在、目标方法找不到…）绝不能静默降级。
            //
            // 早期实现对所有异常一律 catch → 打一行 warn → 返回原始字节码。结果是
            // 「配置加载成功、字节码没变、功能静默消失」，比直接崩溃难查得多：
            // 游戏能进、能玩，只是某个功能永远不触发，日志里只有一行"转换失败"。
            //
            // 2026-10-31 的 F1 接线正是这类故障的近邻：onDisconnect 的目标类写错
            // （实际在父类 ClientCommonPacketListenerImpl）。若当时被静默吞掉，
            // 桥接会"看起来注册成功、实际永远不触发"，而日志里查不到任何线索。
            //
            // 现在这类失败直接升级为致命错误：让启动崩，并明确指出
            // 「目标类 + 目标方法 + mixin 名」，宁可响亮地死也不要静默地坏。
            if (isFatalMixinFailure(th)) {
                MirageLog.MIXIN.fatal("[FATAL] 关键织入失败，目标类: " + name
                        + " —— 注入点失效会让功能静默消失，这里选择让启动失败而不是降级为原字节码"
                        + "\n" + describeChain(th), th);
                throw new MixinApplyFatalException("Mixin apply failed for target class: " + name, th);
            }
            // 非关键异常：仍按老行为降级为原字节码，但必须打印完整堆栈。
            // Mixin 抛出的是包装异常（"An unexpected critical error..."），
            // 根因在 cause 链里，只打 th.toString() 会丢失现场（见 AGENTS.md §8）。
            MirageLog.MIXIN.warn("转换 " + name + " 失败", th);
            th.printStackTrace();
            return basicClass;
        }
    }

    /**
     * 判定异常是否属于「关键织入失败」。
     *
     * <p>用类名字符串判定而不是 {@code instanceof}：这些异常类型分属 mixin 包内多个
     * 子包，且部分只在特定版本存在；按名字判定不引入编译期依赖，也不会因 mixin
     * 升级换包名而失效（真换了名字，判定退化为"不致命"，最坏回到老行为，不会误伤）。
     */
    private static boolean isFatalMixinFailure(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            String n = c.getClass().getName();
            if (n.startsWith("org.spongepowered.asm.mixin.")
                    && (n.endsWith("InjectionError")
                        || n.endsWith("InvalidInjectionException")
                        || n.endsWith("InvalidTargetException")
                        || n.endsWith("TargetNotFoundException")
                        || n.endsWith("CriticalInjectionError")
                        || n.endsWith("MixinApplyError")
                        || n.endsWith("MixinTransformerError"))) {
                return true;
            }
            if (c.getCause() == c) {
                break;
            }
        }
        return false;
    }

    /**
     * 把异常链的所有 message 拼成一行，便于在日志里直接看到
     * 「目标方法 + mixin 名」——Mixin 的异常 message 形如
     * {@code Injection failed into the target method 'onDisconnect' in the mixin 'FabricClientDisconnectMixin'}，
     * 只打最外层异常会丢掉这段。
     */
    private static String describeChain(Throwable t) {
        StringBuilder sb = new StringBuilder();
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (sb.length() > 0) {
                sb.append(" | caused by ");
            }
            sb.append(c.getClass().getSimpleName()).append(": ").append(c.getMessage());
            if (c.getCause() == c) {
                break;
            }
        }
        return sb.toString();
    }

    /**
     * 关键织入失败的致命信号。
     *
     * <p>继承 {@link Error} 而非 {@link RuntimeException}：上层大量 catch(Exception)
     * 会把 RuntimeException 吞掉，致命信号必须穿过去。
     */
    public static final class MixinApplyFatalException extends Error {
        private static final long serialVersionUID = 1L;

        public MixinApplyFatalException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static boolean isReady() {
        return transformer != null;
    }
}
