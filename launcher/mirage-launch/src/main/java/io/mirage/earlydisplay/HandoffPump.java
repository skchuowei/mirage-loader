package io.mirage.earlydisplay;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

/**
 * 窗口交接期的「分片等待 + 消息泵保活」。
 *
 * <p>旧实现在 {@code MirageEarlyWindow.handoff()} 里连续做两次阻塞等待
 * （{@code renderLock.tryAcquire(3, SECONDS)} 与 {@code scheduler.awaitTermination(2, SECONDS)}）。
 * 而 {@code handedOff} 置位后渲染任务已被 cancel，不再调用 {@code glfwPollEvents()}；
 * 交接线程自己又卡在这两次阻塞里不抽窗口消息 —— 两者相加正好 5 秒，踩中 Windows
 * 判定「未响应」的阈值，实机表现为交接瞬间窗口卡住。
 *
 * <p>本方法把等待改成短循环：每轮先查条件，未就绪就先 {@code pump.run()} 抽一次
 * 消息，再 park 一小会儿。生产侧 pump 传 {@code GLFW::glfwPollEvents}，且由
 * <b>创建窗口的主线程</b>（也就是交接线程）执行；离线回归则传一个计数器，
 * 可直接断言「等待期间消息泵确实被反复调用」。
 *
 * <p>纯 JDK，不引用 LWJGL / GLFW，可脱离游戏单独编译验证（见
 * {@code scripts/verify_handoff_pump.ps1}）。
 */
public final class HandoffPump {

    /** 两次 pump 之间的间隔：10ms 远低于 5s 未响应阈值，也不会把 CPU 打满。 */
    public static final long PUMP_INTERVAL_NANOS = TimeUnit.MILLISECONDS.toNanos(10);

    private HandoffPump() {
    }

    /**
     * 反复检查 {@code ready}，其间持续调用 {@code pump} 保活，直到就绪或超时。
     *
     * @param timeoutMs 最长等待毫秒数；必须 &gt; 0
     * @param ready     就绪判定（每次调用应当无副作用或幂等；生产里是 {@code Semaphore::tryAcquire}）
     * @param pump      每轮未就绪时调用一次；生产里是 {@code GLFW::glfwPollEvents}
     * @return {@code true} = 条件在超时前就绪；{@code false} = 超时
     */
    public static boolean await(long timeoutMs, BooleanSupplier ready, Runnable pump) {
        if (timeoutMs <= 0) return ready.getAsBoolean();
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        for (;;) {
            if (ready.getAsBoolean()) return true;
            if (System.nanoTime() - deadline >= 0) return false;
            pump.run();
            LockSupport.parkNanos(PUMP_INTERVAL_NANOS);
        }
    }
}
