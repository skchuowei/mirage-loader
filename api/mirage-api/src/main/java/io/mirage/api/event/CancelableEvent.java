package io.mirage.api.event;

/**
 * 可取消事件基类。
 *
 * <p>监听器调用 {@link #cancel()} 后，事件总线应停止向后续监听器派发，
 * 且事件的发起方应据此中断对应操作。具体中断语义由每个事件自行约定。
 */
public abstract class CancelableEvent extends Event {

    private boolean canceled;

    public boolean isCanceled() {
        return canceled;
    }

    public void cancel() {
        this.canceled = true;
    }

    public void setCanceled(boolean canceled) {
        this.canceled = canceled;
    }
}
