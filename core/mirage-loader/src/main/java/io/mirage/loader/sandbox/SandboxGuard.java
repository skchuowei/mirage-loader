package io.mirage.loader.sandbox;

/**
 * 沙盒字节码改写后的调用目标：mod 里的"退出进程"调用全部改道到这里，一律抛
 * {@link SandboxKillException}，由 {@link SandboxRecorder} 的沙盒线程接住并降级为
 * {@code failed=true, error="exit(code)"}。
 *
 * <p><b>为什么是静态钩子而不是在字节码里直接 new + athrow</b>：钩子方法的参数/返回
 * 形状与被替换的调用**完全一致**（`System.exit(I)V` → `exit(I)V`；
 * `Runtime.halt(I)V` → `halt(Ljava/lang/Runtime;I)V`，接收者正好当第 0 参），
 * 操作数栈形状不变 ⇒ 栈映射帧（StackMapTable）无需重算，ASM 用
 * {@code ClassWriter(cr, 0)} 原样拷贝即可。重算帧是本项目历史上的雷区
 * （AGENTS.md 技术注意第 9 条：ASM 改写丢帧后交 Mixin 二次转换会失败）。
 *
 * <p>本类由 fat jar 侧（与 {@link SandboxRecorder} 同一个 ClassLoader）加载：
 * mod 类链接时按定义类加载器解析 {@code io.mirage.loader.sandbox.SandboxGuard}，
 * 走 parent-first 拿到唯一一份，因此抛出的异常与记录器捕获的是同一个类型。
 */
public final class SandboxGuard {

    /** 改道自 {@code System.exit(int)}。 */
    public static void exit(int code) {
        throw new SandboxKillException(code);
    }

    /** 改道自 {@code Runtime#exit(int)}；{@code rt} 仅为吃掉栈上的接收者。 */
    public static void exitRuntime(Runtime rt, int code) {
        throw new SandboxKillException(code);
    }

    /** 改道自 {@code Runtime#halt(int)}（比 exit 更狠，不跑关闭钩子，必须拦）。 */
    public static void halt(Runtime rt, int code) {
        throw new SandboxKillException(code);
    }

    private SandboxGuard() {}
}
