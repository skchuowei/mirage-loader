package net.neoforged.bus.api;

/**
 * 运行时桥接 stub：NeoForge 事件体系的顶层基类。
 *
 * <p>保留取消状态与 Result 语义；是否可取消由子类是否实现
 * {@link ICancellableEvent} 决定。
 */
public abstract class Event {

    private boolean canceled;
    private Result result = Result.DEFAULT;

    /** 子类实现 ICancellableEvent 即视为可取消。 */
    public boolean isCancelable() {
        return this instanceof ICancellableEvent;
    }

    public boolean isCanceled() {
        return canceled;
    }

    public void setCanceled(boolean canceled) {
        if (!isCancelable()) {
            throw new UnsupportedOperationException(getClass().getName() + " 不是可取消事件");
        }
        this.canceled = canceled;
    }

    public boolean hasResult() {
        return false;
    }

    public Result getResult() {
        return result;
    }

    public void setResult(Result result) {
        this.result = result;
    }

    public enum Result {
        DENY,
        DEFAULT,
        ALLOW
    }
}
