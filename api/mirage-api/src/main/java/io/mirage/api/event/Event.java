package io.mirage.api.event;

/**
 * 所有 Mirage 事件的根类型。
 *
 * <p>事件总线按事件的 {@link #getClass()} 分桶派发；
 * 监听器通过 {@code @SubscribeEvent} 注解在方法上声明。
 *
 * <p>历史说明：{@link ModConstructEvent} 是 M2 时代留下的裸类，
 * 不继承本类；自 M4 起新增的事件均继承本类，以便未来统一挂载
 * 取消、优先级、相位等扩展能力。
 */
public abstract class Event {

    /** 事件是否已经被派发（防重入）。 */
    private boolean dispatched;

    public final boolean isDispatched() {
        return dispatched;
    }

    public final void markDispatched() {
        this.dispatched = true;
    }
}
