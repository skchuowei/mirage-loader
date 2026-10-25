package net.neoforged.bus.api;

/** 运行时桥接 stub：可取消事件标记接口。具体状态由 {@link Event} 持有。 */
public interface ICancellableEvent {
    default void setCanceled(boolean canceled) {
        ((Event) this).setCanceled(canceled);
    }

    default boolean isCanceled() {
        return ((Event) this).isCanceled();
    }
}
