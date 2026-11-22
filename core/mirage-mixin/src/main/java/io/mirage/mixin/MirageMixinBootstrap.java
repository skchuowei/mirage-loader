package io.mirage.mixin;

import io.mirage.api.error.MirageErrors;
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

            // MixinExtras 初始化（mod 的 @ModifyReturnValue/@WrapOperation/@ModifyExpressionValue 等）。
            // 时序硬约束（2026-11-22 探针实证）：必须在 setActiveTransformer 之后——
            // MixinExtrasBootstrap.init() 内部走 MixinInternals.getExtensions()，
            // 取 getActiveTransformer().getExtensions()，过早调用直接 NPE。
            // 必须在 addConfiguration 之前：注入器要在 config 解析前注册进 InjectionInfo 注册表。
            // 失败只 WARN 不阻断：库缺失/异常时 MG 仍可启动，只是 MixinExtras 注入器不生效
            // （与「关键织入失败才致命」的分级一致；mod 声明 mixinextras.minVersion 不满足时
            // 由 MirageMixinService 在 config 加载期单独致命，见那边的校验）。
            try {
                com.llamalad7.mixinextras.MixinExtrasBootstrap.init();
                MirageLog.MIXIN.info("MixinExtras " + com.llamalad7.mixinextras.MixinExtrasBootstrap.getVersion() + " 已初始化");
            } catch (Throwable t) {
                MirageLog.MIXIN.warn("MixinExtras 初始化失败（mod 的 MixinExtras 注入器将不生效）", t);
            }

            // 记下实际注册的 config 清单：下面收集 mod @Mixin 目标类还要用一次（入参只是 Iterable，
            // 不保证可重复遍历）
            java.util.List<String> registeredConfigs = new java.util.ArrayList<>();
            for (String c : configs) {
                registeredConfigs.add(c);
                env.addConfiguration(c);
                MirageLog.MIXIN.info("注册 mixin config: " + c);
            }

            // 织入优先级（用户拍板·社区最大化，AGENTS.md §16）：先收集 mod @Mixin 的目标类，
            // 供 MirageClassLoader 在跑 MG 自研 ASM 前判「要不要让路」。
            // 时机：必须在 config 注册之后（config 与 mixin 类此时才保证可从 mcl 读到），
            // 且必须早于任何 net.minecraft.* 类加载（init 在 S3，类加载在 S4/S7，天然满足）。
            // 失败只 WARN：收集不到 ⇒ 空集 ⇒ 不让路、保持老行为，绝不让观测能力拖垮启动。
            try {
                java.util.Set<String> modTargets = ModMixinTargets.collect(mcl, registeredConfigs);
                WeavePriority.setModMixinTargets(modTargets);
                java.util.List<String> conflicts = WeavePriority.conflicts();
                MirageLog.MIXIN.info("mod @Mixin 目标类 " + modTargets.size()
                        + " 个；与 MG 自研织入的硬编码目标冲突 " + conflicts.size() + " 处"
                        + (conflicts.isEmpty() ? "" : " -> " + conflicts + "（这些类 MG 自研织入将让路，逐类打 WARN）"));
            } catch (Throwable t) {
                MirageLog.MIXIN.warn("收集 mod @Mixin 目标类失败（MG 自研织入不做让路判定，保持老行为）", t);
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
                MirageErrors.fatal("关键织入失败，目标类: " + name
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
