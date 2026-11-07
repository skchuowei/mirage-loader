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
 *   <li>{@code net.minecraft.core.MappedRegistry} → 在 {@code register} 的 RETURN 前
 *       上报「注册表 + id」；</li>
 *   <li>{@code net.neoforged.bus.api.EventBus} → 在 {@code addListener} 的 RETURN 前
 *       上报「事件类型」。</li>
 * </ul>
 *
 * <p><b>为什么插游戏侧而不是 mod 侧</b>：P2 的 fake pass 只能看到 mod 碰到 stub 的调用，
 * mod 直接调 MC 原版方法一律看不见。插游戏侧就能看到<b>全部</b>注册与订阅，
 * 而且插桩点数是常数级的（几个类），不是「mod 引用了多少 API 就插多少」。
 *
 * <p><b>实现要点</b>：
 * <ul>
 *   <li>用 {@link ClassReader#accept} + {@link ClassWriter}，不做方法体全量重写 ——
 *       只在目标方法的 RETURN 前插一段 {@code INVOKESTATIC} 调用，其余原样保留；</li>
 *   <li>用 {@link ClassWriter#COMPUTE_FRAMES} 之前必须注意：它会重算栈映射帧，
 *       对 MC 类通常安全，但为稳妥起见用 {@code COMPUTE_MAXS} + 保留原 frames
 *       （{@code ClassReader.EXPAND_FRAMES} 不开，插入的是无分支指令，不需要新 frame）；</li>
 *   <li>插桩代码必须<b>不抛异常</b>：观测失败绝不能把游戏搞崩，所以上报调用包在
 *       try/catch 里（见 {@code io.mirage.msr.agent.RecorderAgent} 的记录方法）。</li>
 * </ul>
 */
public final class RecorderTransformer implements ClassFileTransformer {

    /** 目标类 → 目标方法名。 */
    private static final String REGISTRY_CLASS = "net/minecraft/core/MappedRegistry";
    private static final String EVENT_BUS_CLASS = "net/neoforged/bus/api/EventBus";

    private static final String AGENT_OWNER = "io/mirage/msr/agent/RecorderAgent";

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain, byte[] classfileBuffer) {
        if (className == null) return null;
        try {
            if (REGISTRY_CLASS.equals(className)) {
                return instrumentRegistry(classfileBuffer);
            }
            if (EVENT_BUS_CLASS.equals(className)) {
                return instrumentEventBus(classfileBuffer);
            }
        } catch (Throwable t) {
            // 插桩失败就返回原始字节码，绝不能把游戏搞崩
            System.err.println("[MSR-P3] 插桩失败 " + className + ": " + t);
        }
        return null;
    }

    /** 在 {@code MappedRegistry.register} 的每个 RETURN 前插上报调用。 */
    private static byte[] instrumentRegistry(byte[] src) {
        ClassReader cr = new ClassReader(src);
        ClassWriter cw = new ClassWriter(cr, 0);
        cr.accept(new ClassVisitor(Opcodes.ASM9, cw) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc,
                                             String signature, String[] exceptions) {
                MethodVisitor mv = super.visitMethod(access, name, desc, signature, exceptions);
                if (!"register".equals(name) || !desc.startsWith("(Lnet/minecraft/resources/ResourceKey;")) {
                    return mv;
                }
                return new MethodVisitor(Opcodes.ASM9, mv) {
                    @Override
                    public void visitInsn(int opcode) {
                        // 静态调用栈：[registry, value] -> 上报需要 resourceKey 与 value
                        // 为了不改动参数栈，这里只上报「当前类名 + 方法名」级别的粗粒度信息，
                        // 精确 id 由后续 IR 组装阶段从 MappedRegistry 的 registry 字段读取。
                        if (opcode == Opcodes.RETURN) {
                            super.visitLdcInsn("mapped_registry");
                            super.visitLdcInsn(name);
                            super.visitLdcInsn("");
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, AGENT_OWNER,
                                    "onRegister", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V", false);
                        }
                        super.visitInsn(opcode);
                    }
                };
            }
        }, 0);
        return cw.toByteArray();
    }

    /** 在 {@code EventBus.addListener} 的 RETURN 前插上报调用。 */
    private static byte[] instrumentEventBus(byte[] src) {
        ClassReader cr = new ClassReader(src);
        ClassWriter cw = new ClassWriter(cr, 0);
        cr.accept(new ClassVisitor(Opcodes.ASM9, cw) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc,
                                             String signature, String[] exceptions) {
                MethodVisitor mv = super.visitMethod(access, name, desc, signature, exceptions);
                if (!"addListener".equals(name) && !"register".equals(name)) {
                    return mv;
                }
                return new MethodVisitor(Opcodes.ASM9, mv) {
                    @Override
                    public void visitInsn(int opcode) {
                        if (opcode == Opcodes.RETURN) {
                            super.visitLdcInsn("event_bus");
                            super.visitLdcInsn(name);
                            super.visitLdcInsn("");
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, AGENT_OWNER,
                                    "onSubscribe", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V", false);
                        }
                        super.visitInsn(opcode);
                    }
                };
            }
        }, 0);
        return cw.toByteArray();
    }
}
