package io.mirage.loader.sandbox;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * 沙盒字节码改写器：把 mod 类里的"退出进程"调用改道到 {@link SandboxGuard}。
 *
 * <p>只做一件事，且<strong>只换调用、不改栈形状</strong>：
 * <table border="1">
 *   <caption>改写映射</caption>
 *   <tr><th>原调用</th><th>改成</th></tr>
 *   <tr><td>{@code INVOKESTATIC java/lang/System.exit (I)V}</td>
 *       <td>{@code INVOKESTATIC SandboxGuard.exit (I)V}</td></tr>
 *   <tr><td>{@code INVOKEVIRTUAL java/lang/Runtime.exit (I)V}</td>
 *       <td>{@code INVOKESTATIC SandboxGuard.exitRuntime (Ljava/lang/Runtime;I)V}</td></tr>
 *   <tr><td>{@code INVOKEVIRTUAL java/lang/Runtime.halt (I)V}</td>
 *       <td>{@code INVOKESTATIC SandboxGuard.halt (Ljava/lang/Runtime;I)V}</td></tr>
 * </table>
 *
 * <p>两者的操作数栈净效应一致（消费同样的参数、不产生返回值），因此
 * <b>不需要 {@code COMPUTE_FRAMES}</b>：{@code ClassWriter(cr, 0)} 直接沿用原始
 * StackMapTable。这是刻意的——重算帧会让 class 进入"被 ASM 重写"的状态，
 * 后续若再有 Mixin 之类二次转换容易相互踩（AGENTS.md 技术注意第 9 条）。
 *
 * <p>两条前置判断：
 * <ul>
 *   <li><b>先探测再改写</b>：绝大多数 mod 类不含这类调用，探测未命中时<b>原样返回原字节数组</b>，
 *       零重写、零风险；</li>
 *   <li>探测用"命中即抛私有信号异常"早停，避免扫完整个类。</li>
 * </ul>
 *
 * <p>已知边界：只拦直接调用。通过 {@code Runtime.getRuntime()::halt} 之类的方法引用
 * （{@code invokedynamic}）或反射拿到的 {@code Method} 拦不到——记在 {@code docs/已知bug.md}。
 */
public final class SandboxBytecodeGuard {

    /** 钩子类（内部名形式，供 INVOKESTATIC 的 owner 用）。 */
    public static final String HOOK_OWNER = "io/mirage/loader/sandbox/SandboxGuard";

    private static final String RUNTIME_DESC = "(Ljava/lang/Runtime;I)V";

    /**
     * 改写一段类字节码。
     *
     * @param original 原始字节码
     * @param hitsOut  可选；长度为 1 时回填被改写的调用处数（用于日志）
     * @return 改写后的字节码；未命中任何目标调用时**原样返回 {@code original}**
     */
    public static byte[] rewrite(byte[] original, int[] hitsOut) {
        ClassReader cr = new ClassReader(original);
        if (!containsKillCall(cr)) {
            return original;
        }
        int[] hits = new int[1];
        ClassWriter cw = new ClassWriter(cr, 0);
        cr.accept(new Rewriter(cw, hits), 0);
        if (hitsOut != null && hitsOut.length > 0) {
            hitsOut[0] = hits[0];
        }
        return cw.toByteArray();
    }

    /** 探测：类里是否有 {@code System.exit} / {@code Runtime.exit|halt} 的直接调用。 */
    private static boolean containsKillCall(ClassReader cr) {
        try {
            cr.accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String desc,
                                                 String signature, String[] exceptions) {
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override
                        public void visitMethodInsn(int opcode, String owner, String n, String d, boolean itf) {
                            if (isSystemExit(opcode, owner, n, d) || isRuntimeKill(opcode, owner, n, d)) {
                                throw new Stop();
                            }
                        }
                    };
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        } catch (Stop stop) {
            return true;
        }
        return false;
    }

    private static boolean isSystemExit(int opcode, String owner, String name, String desc) {
        return opcode == Opcodes.INVOKESTATIC
                && "java/lang/System".equals(owner)
                && "exit".equals(name)
                && "(I)V".equals(desc);
    }

    private static boolean isRuntimeKill(int opcode, String owner, String name, String desc) {
        if (!"java/lang/Runtime".equals(owner) || !"(I)V".equals(desc)) return false;
        if (opcode != Opcodes.INVOKEVIRTUAL && opcode != Opcodes.INVOKEINTERFACE) return false;
        return "exit".equals(name) || "halt".equals(name);
    }

    /** 真正的改写者。 */
    private static final class Rewriter extends ClassVisitor {

        private final int[] hits;

        Rewriter(ClassVisitor next, int[] hits) {
            super(Opcodes.ASM9, next);
            this.hits = hits;
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String desc,
                                         String signature, String[] exceptions) {
            MethodVisitor mv = super.visitMethod(access, name, desc, signature, exceptions);
            if (mv == null) return null;
            return new MethodVisitor(Opcodes.ASM9, mv) {
                @Override
                public void visitMethodInsn(int opcode, String owner, String n, String d, boolean itf) {
                    if (isSystemExit(opcode, owner, n, d)) {
                        hits[0]++;
                        super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOK_OWNER, "exit", "(I)V", false);
                        return;
                    }
                    if (isRuntimeKill(opcode, owner, n, d)) {
                        hits[0]++;
                        String hook = "exit".equals(n) ? "exitRuntime" : "halt";
                        super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOK_OWNER, hook, RUNTIME_DESC, false);
                        return;
                    }
                    super.visitMethodInsn(opcode, owner, n, d, itf);
                }
            };
        }
    }

    /** 探测期早停信号（关栈，不参与任何对外语义）。 */
    private static final class Stop extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Stop() {
            super(null, null, false, false);
        }
    }

    private SandboxBytecodeGuard() {}
}
