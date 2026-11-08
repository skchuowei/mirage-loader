package io.mirage.msr.agent;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.lang.instrument.ClassFileTransformer;
import java.nio.charset.StandardCharsets;
import java.security.ProtectionDomain;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * MSR P3 · 录制期间不显示游戏窗口（2026-11-08）。
 *
 * <p><b>问题</b>：P3 必须在<b>真实</b>客户端里跑（加载期没有 headless 路径），
 * 于是录制时屏幕上会蹦出窗口 —— 跑一次基线 + 一次录制就要占屏几分钟，
 * 而这两轮里人根本不需要看画面（唯一的产出是 IR 文件）。
 *
 * <p><b>为什么不能直接 headless</b>：MC 26.2 的客户端初始化链
 * （{@code Window} → GL 上下文 → {@code GlBackend} → 资源/注册）依赖真实 GLFW 窗口，
 * {@code -Djava.awt.headless=true} 对它没有任何作用。
 *
 * <p><b>做法</b>：窗口「出现」在 MC / NeoForge 里只有一条路 —— 调
 * {@code GLFW.glfwShowWindow(long)}。两个调用点都已用 javap 核实：
 * <ul>
 *   <li>{@code net.minecraft.client.Minecraft#<init>}（窗口初始化末尾，
 *       紧跟着 {@code resizeGui()} / {@code loadCriticalShaders()}）；</li>
 *   <li>{@code net.neoforged.fml.earlydisplay.DisplayWindow#initWindow}
 *       （NeoForge 早期加载画面；该窗口随后被 {@code takeOverGlfwWindow()} 交给 MC 复用）。</li>
 * </ul>
 * 两处创建窗口时都把 {@code GLFW_VISIBLE} 显式设成 {@code GLFW_FALSE}，
 * 所以<b>不调 show</b> 窗口就永远不出现 —— 不需要改 hint，也不需要 OS 层兜底。
 *
 * <p><b>插桩方式</b>：把 {@code INVOKESTATIC GLFW.glfwShowWindow(J)V} 换成 {@code POP2}：
 * 栈上是 {@code long} 句柄（占 2 槽），{@code POP2} 正好吃掉，
 * <b>调用后栈深度与原指令完全一致</b> ⇒ 不需要重算 maxStack、原有 StackMapFrame 仍有效。
 *
 * <p><b>范围</b>：本类对<b>所有</b>类生效（先用字节级 needle 快速筛，
 * 命中才走 ASM），因此 MC 换版本后改用别的类去 show 也会被摘掉；
 * 但绝不改动除该调用以外的任何字节。
 *
 * <p><b>开关</b>：{@code -Dmirage.msr.hidewindow=false} 关闭（要对着画面排查问题时用）。
 * 默认<b>开</b>：录制场景下人不需要看见窗口。
 *
 * <p><b>风险与降级</b>：插桩失败一律返回原始字节码 —— 观测/隐藏绝不能把游戏搞崩；
 * 运行器侧另有一层「发现可见窗口就按下并记进报告」的兜底（见 run_msr_p3.ps1），
 * 所以这里失败也不会让用户屏幕上突然多一个窗口。
 */
public final class WindowHideTransformer implements ClassFileTransformer {

    /** LWJGL 的 GLFW 门面类。 */
    static final String GLFW_OWNER = "org/lwjgl/glfw/GLFW";

    /** 让窗口「出现」的唯一途径。 */
    static final String SHOW_NAME = "glfwShowWindow";

    /** 签名：{@code glfwShowWindow(long handle): void}。 */
    static final String SHOW_DESC = "(J)V";

    /** 字节级快速筛的针：常量池里的方法名字面量一定连续出现。 */
    private static final byte[] NEEDLE = SHOW_NAME.getBytes(StandardCharsets.ISO_8859_1);

    /** 摘掉的 show 调用总数（写进 IR stats，供门禁断言）。 */
    private static final AtomicInteger STRIPPED_CALLS = new AtomicInteger();

    /** 被改写的类名（诊断用）。 */
    private static final Set<String> TOUCHED = Collections.synchronizedSet(new LinkedHashSet<>());

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain, byte[] classfileBuffer) {
        if (classfileBuffer == null) return null;
        // 先做 O(n) 字节筛：99.9% 的类连 needle 都没有，直接放行（不构造 ClassReader）
        if (!mentionsShowWindow(classfileBuffer)) return null;
        try {
            byte[] out = stripShowWindow(classfileBuffer);
            if (out == null) return null;
            TOUCHED.add(className == null ? "<unnamed>" : className);
            return out;
        } catch (Throwable t) {
            System.err.println("[MSR-P3] 隐藏窗口插桩失败 " + className + ": " + t);
            return null;
        }
    }

    /**
     * 字节级快速判定：类文件里是否出现 {@code glfwShowWindow} 字面量。
     *
     * <p>常量池的 CONSTANT_Utf8 是<b>原样存储</b>的，所以方法名会以连续 ASCII 字节出现；
     * 用这个做前置筛，避免对每个类都跑一遍 ASM。
     */
    public static boolean mentionsShowWindow(byte[] bytes) {
        if (bytes == null) return false;
        final int n = NEEDLE.length;
        if (bytes.length < n) return false;
        final byte first = NEEDLE[0];
        final int limit = bytes.length - n;
        for (int i = 0; i <= limit; i++) {
            if (bytes[i] != first) continue;
            int j = 1;
            while (j < n && bytes[i + j] == NEEDLE[j]) j++;
            if (j == n) return true;
        }
        return false;
    }

    /**
     * 把 {@code GLFW.glfwShowWindow(long)} 调用摘成 {@code POP2}。
     *
     * @param src 原始类字节码
     * @return 改写后的字节码；<b>一次都没命中时返回 null</b>（调用方据此不改写）
     */
    public static byte[] stripShowWindow(byte[] src) {
        ClassReader cr = new ClassReader(src);
        ClassWriter cw = new ClassWriter(cr, 0);
        final int[] hits = {0};
        cr.accept(new ClassVisitor(Opcodes.ASM9, cw) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc,
                                             String signature, String[] exceptions) {
                MethodVisitor mv = super.visitMethod(access, name, desc, signature, exceptions);
                return new MethodVisitor(Opcodes.ASM9, mv) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String mname,
                                                String mdesc, boolean isInterface) {
                        if (opcode == Opcodes.INVOKESTATIC && !isInterface
                                && GLFW_OWNER.equals(owner)
                                && SHOW_NAME.equals(mname)
                                && SHOW_DESC.equals(mdesc)) {
                            // long 句柄占 2 槽 → POP2 正好吃掉，调用后栈深度与原指令一致
                            super.visitInsn(Opcodes.POP2);
                            hits[0]++;
                            STRIPPED_CALLS.incrementAndGet();
                            return;
                        }
                        super.visitMethodInsn(opcode, owner, mname, mdesc, isInterface);
                    }
                };
            }
        }, 0);
        return hits[0] == 0 ? null : cw.toByteArray();
    }

    /** 已摘掉的 show 调用数（诊断 / 门禁用）。 */
    public static int strippedCalls() {
        return STRIPPED_CALLS.get();
    }

    /** 被改写的类名（诊断用）。 */
    public static Set<String> touchedClasses() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(TOUCHED));
    }
}
