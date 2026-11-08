package io.mirage.msr.agent;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

/**
 * MSR P3 · 字节码插桩器（2026-11-07）。
 *
 * <p><b>插桩点收敛在游戏侧有限几个类</b>（这是 P3 相对 P2 的核心优势）：
 * <ul>
 *   <li>{@code net.minecraft.core.MappedRegistry#register} → 上报「注册表 + id」；
 *       真实签名为
 *       {@code register(ResourceKey, T, RegistrationInfo)} 与
 *       {@code register(int, ResourceKey, T, RegistrationInfo)}（<b>返回 Holder$Reference</b>），
 *       ResourceKey 分别在局部变量槽 <b>1</b> / <b>2</b>；</li>
 *   <li>{@code net.neoforged.bus.EventBus#addListener} → 上报「事件类型」；
 *       <b>真实实现类在 {@code net.neoforged.bus} 包</b>（不是 {@code api} 包）；
 *       取 {@code addListener(Class, Consumer)} 重载的 {@code Class} 槽位；</li>
 *   <li>{@code net.neoforged.bus.EventListenerFactory#create(Method, Object)}
 *       → mod 的 {@code @SubscribeEvent} 主路径，能同时拿到<b>事件类型</b>与<b>监听器实例</b>
 *       （归属判定最准）。</li>
 * </ul>
 *
 * <p><b>为什么插游戏侧而不是 mod 侧</b>：P2 的 fake pass 只能看到 mod 碰到 stub 的调用，
 * mod 直接调 MC 原版方法一律看不见。插游戏侧就能看到<b>全部</b>注册与订阅，
 * 而且插桩点数是常数级的（几个类），不是「mod 引用了多少 API 就插多少」。
 *
 * <p><b>实现要点</b>：
 * <ul>
 *   <li>用 {@link ClassReader#accept} + {@link ClassWriter}，只在<b>方法入口</b>插一段
 *       {@code INVOKESTATIC} 调用，其余原样保留；</li>
 *   <li>入口插桩保持栈平衡（push N / pop N），因此<b>不需要</b>重算
 *       {@code maxStack}，原 frames 也保持有效（不引入分支，frame 不变）；</li>
 *   <li>插桩代码必须<b>不抛异常</b>：观测失败绝不能把游戏搞崩，
 *       所以上报调用包在 try/catch 里（见 {@link RecorderAgent} 的记录方法）；</li>
 *   <li>参数一律以 {@code Object} 传递，取值放在 agent 侧用<b>反射</b>做 ——
 *       这样 agent 不编译期依赖 MC/NeoForge，且跨版本改名（如 26.2 把
 *       {@code ResourceLocation} 改名为 {@code Identifier}）不会让 agent 崩。</li>
 * </ul>
 */
public final class RecorderTransformer implements ClassFileTransformer {

    /** 注册表类：记录「注册了哪些 id」。 */
    private static final String REGISTRY_CLASS = "net/minecraft/core/MappedRegistry";

    /** 事件总线实现类：注意在 {@code net.neoforged.bus} 包，<b>不是</b> {@code api} 包。 */
    private static final String EVENT_BUS_CLASS = "net/neoforged/bus/EventBus";

    /** 事件监听器工厂：{@code @SubscribeEvent} 的实际处理点。 */
    private static final String LISTENER_FACTORY_CLASS = "net/neoforged/bus/EventListenerFactory";

    /** agent 侧入口方法（owner）。 */
    private static final String AGENT_OWNER = "io/mirage/msr/agent/RecorderAgent";

    /** 插桩自检计数（测试用）。 */
    private static final java.util.concurrent.atomic.AtomicInteger INSTRUMENTED =
            new java.util.concurrent.atomic.AtomicInteger();

    /** 已插桩的类名（测试用）。 */
    private static final java.util.Set<String> INSTRUMENTED_CLASSES =
            java.util.Collections.synchronizedSet(new java.util.LinkedHashSet<>());

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain, byte[] classfileBuffer) {
        if (className == null) return null;
        boolean target = REGISTRY_CLASS.equals(className)
                || EVENT_BUS_CLASS.equals(className)
                || LISTENER_FACTORY_CLASS.equals(className);
        if (!target) return null;
        try {
            byte[] out;
            if (REGISTRY_CLASS.equals(className)) {
                out = instrumentRegistry(classfileBuffer);
            } else if (EVENT_BUS_CLASS.equals(className)) {
                out = instrumentEventBus(classfileBuffer);
            } else {
                out = instrumentListenerFactory(classfileBuffer);
            }
            if (out != null) {
                INSTRUMENTED.incrementAndGet();
                INSTRUMENTED_CLASSES.add(className);
            }
            return out;
        } catch (Throwable t) {
            // 插桩失败就返回原始字节码，绝不能把游戏搞崩
            System.err.println("[MSR-P3] 插桩失败 " + className + ": " + t);
            return null;
        }
    }

    /**
     * 在 {@code MappedRegistry.register} 的<b>入口</b>插上报调用。
     *
     * <p>两个重载的 ResourceKey 槽位不同：3 参版在槽 1，4 参版（首参为 int id）在槽 2。
     */
    private static byte[] instrumentRegistry(byte[] src) {
        ClassReader cr = new ClassReader(src);
        ClassWriter cw = new ClassWriter(cr, 0);
        cr.accept(new ClassVisitor(Opcodes.ASM9, cw) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc,
                                             String signature, String[] exceptions) {
                MethodVisitor mv = super.visitMethod(access, name, desc, signature, exceptions);
                if (!"register".equals(name)) return mv;
                int keySlot = keySlotOfRegister(desc);
                if (keySlot < 0) return mv;
                return new MethodVisitor(Opcodes.ASM9, mv) {
                    @Override
                    public void visitCode() {
                        super.visitCode();
                        super.visitVarInsn(Opcodes.ALOAD, keySlot);
                        super.visitMethodInsn(Opcodes.INVOKESTATIC, AGENT_OWNER,
                                "onRegisterKey", "(Ljava/lang/Object;)V", false);
                    }
                };
            }
        }, 0);
        return cw.toByteArray();
    }

    /**
     * 从 {@code register} 的方法描述符里算出 ResourceKey 所在局部变量槽。
     *
     * @param desc 方法描述符
     * @return 槽位；不是目标签名返回 -1
     */
    static int keySlotOfRegister(String desc) {
        String key = "Lnet/minecraft/resources/ResourceKey;";
        if (desc.startsWith("(" + key)) return 1;                                  // register(ResourceKey, T, RegistrationInfo)
        if (desc.startsWith("(I" + key)) return 2;                                 // register(int, ResourceKey, T, RegistrationInfo)
        return -1;
    }

    /**
     * 在 {@code EventBus.addListener} / {@code EventBus.addToListeners} 的入口插上报调用。
     *
     * <p><b>为什么两个都插</b>：{@code addToListeners(Object owner, Class eventType, EventListener, EventPriority)}
     * 是两条订阅路径的<b>唯一汇聚点</b> ——
     * {@code addListener(...)} 的全部公有重载最终都汇到它，
     * {@code @SubscribeEvent} 的自动注册（{@code register(Object)} →
     * {@code new SubscribeEventListener} → 构造里 {@code EventListenerFactory.create}）之后也汇到它。
     * 在汇聚点插一次就能同时覆盖两条路，且 owner 直接以参数形式拿到（不必靠栈回溯）。
     * 但上游重载在版本间会变（本轮实测 bus 8.0.5 的 {@code addListener(Consumer)} 单参重载
     * 就没有 {@code Class} 参数），所以保留 {@code addListener} 与
     * {@code EventListenerFactory.create} 两个冗余点做跨版本兜底；
     * 重复上报由 {@link RecorderAgent#onSubscribeEvent} 按 {@code owner|event} 去重吸收。
     */
    static final String ADD_TO_LISTENERS_NAME = "addToListeners";

    /** {@code addToListeners} 的确切描述符（槽 1 = owner，槽 2 = 事件类型 Class）。 */
    static final String ADD_TO_LISTENERS_DESC =
            "(Ljava/lang/Object;Ljava/lang/Class;Lnet/neoforged/bus/api/EventListener;"
                    + "Lnet/neoforged/bus/api/EventPriority;)V";

    private static byte[] instrumentEventBus(byte[] src) {
        ClassReader cr = new ClassReader(src);
        ClassWriter cw = new ClassWriter(cr, 0);
        cr.accept(new ClassVisitor(Opcodes.ASM9, cw) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc,
                                             String signature, String[] exceptions) {
                MethodVisitor mv = super.visitMethod(access, name, desc, signature, exceptions);
                if (ADD_TO_LISTENERS_NAME.equals(name) && ADD_TO_LISTENERS_DESC.equals(desc)) {
                    return new MethodVisitor(Opcodes.ASM9, mv) {
                        @Override
                        public void visitCode() {
                            super.visitCode();
                            // 先压 typeSource（槽 2 = Class），再压 listener（槽 1 = owner）：
                            // 与 onSubscribeEvent(Object typeSource, Object listener) 的形参顺序一致。
                            super.visitVarInsn(Opcodes.ALOAD, 2);
                            super.visitVarInsn(Opcodes.ALOAD, 1);
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, AGENT_OWNER,
                                    "onSubscribeEvent", "(Ljava/lang/Object;Ljava/lang/Object;)V", false);
                        }
                    };
                }
                if (!"addListener".equals(name)) return mv;
                int classSlot = classSlotOfAddListener(desc);
                if (classSlot < 0) return mv;
                int consumerSlot = classSlot + 1;
                return new MethodVisitor(Opcodes.ASM9, mv) {
                    @Override
                    public void visitCode() {
                        super.visitCode();
                        super.visitVarInsn(Opcodes.ALOAD, classSlot);
                        super.visitVarInsn(Opcodes.ALOAD, consumerSlot);
                        super.visitMethodInsn(Opcodes.INVOKESTATIC, AGENT_OWNER,
                                "onSubscribeEvent", "(Ljava/lang/Object;Ljava/lang/Object;)V", false);
                    }
                };
            }
        }, 0);
        return cw.toByteArray();
    }

    /**
     * 从 {@code addListener} 描述符里算出 {@code Class} 参数所在槽位。
     *
     * @param desc 方法描述符
     * @return 槽位；该重载不含 Class 返回 -1
     */
    static int classSlotOfAddListener(String desc) {
        if (!desc.startsWith("(")) return -1;
        // 逐个参数类型扫描，记录第几个参数是 Class。
        // 槽 0 是 this；本组重载只含引用类型与 boolean（均占 1 槽），故每参数 +1。
        int slot = 1;
        int i = 1;
        while (i < desc.length() && desc.charAt(i) != ')') {
            char c = desc.charAt(i);
            while (c == '[') { i++; c = desc.charAt(i); }
            if (c == 'L') {
                int semi = desc.indexOf(';', i);
                if (semi < 0) return -1;
                String type = desc.substring(i + 1, semi);
                i = semi + 1;
                if (type.equals("java/lang/Class")) return slot;
            } else {
                i++;
            }
            slot += 1;
        }
        return -1;
    }

    /**
     * 在 {@code EventListenerFactory.create(Method, Object)} 的入口插上报调用。
     *
     * <p>这是 mod 声明 {@code @SubscribeEvent} 的实际处理点：
     * 第一个参数是<b>被注解的方法</b>（事件类型 = 其第一个参数类型），
     * 第二个参数是<b>监听器实例</b>（归属判定的最强线索）。
     */
    private static byte[] instrumentListenerFactory(byte[] src) {
        ClassReader cr = new ClassReader(src);
        ClassWriter cw = new ClassWriter(cr, 0);
        cr.accept(new ClassVisitor(Opcodes.ASM9, cw) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc,
                                             String signature, String[] exceptions) {
                MethodVisitor mv = super.visitMethod(access, name, desc, signature, exceptions);
                if (!"create".equals(name)) return mv;
                if (!desc.startsWith("(Ljava/lang/reflect/Method;Ljava/lang/Object;)")) return mv;
                return new MethodVisitor(Opcodes.ASM9, mv) {
                    @Override
                    public void visitCode() {
                        super.visitCode();
                        // 静态方法：槽 0 = Method，槽 1 = 监听器实例
                        super.visitVarInsn(Opcodes.ALOAD, 0);
                        super.visitVarInsn(Opcodes.ALOAD, 1);
                        super.visitMethodInsn(Opcodes.INVOKESTATIC, AGENT_OWNER,
                                "onSubscribeEvent", "(Ljava/lang/Object;Ljava/lang/Object;)V", false);
                    }
                };
            }
        }, 0);
        return cw.toByteArray();
    }

    /** 已插桩的类数（测试用）。 */
    public static int instrumentedCount() {
        return INSTRUMENTED.get();
    }

    /** 已插桩的类名集合（测试用）。 */
    public static java.util.Set<String> instrumentedClasses() {
        return java.util.Collections.unmodifiableSet(new java.util.LinkedHashSet<>(INSTRUMENTED_CLASSES));
    }
}
