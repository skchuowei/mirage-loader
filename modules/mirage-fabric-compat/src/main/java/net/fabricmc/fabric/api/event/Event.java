/*
 * Mirage Fabric Compat — clean-room reimplementation of Fabric's event API surface.
 *
 * Fabric (net.fabricmc.*) is licensed under the Apache License, Version 2.0.
 * This is an independent, minimal implementation that mirrors the public interface
 * so Fabric mods can register callbacks under Mirage without depending on fabric-loader.
 *
 * 本文件为 Mirage 对 Fabric 事件接口的 clean-room 再实现（Fabric 系 Apache 2.0，可参考）。
 * 仅镜像公开接口，不抄袭任何实现细节。
 */
package net.fabricmc.fabric.api.event;

/**
 * Base class for Fabric's event implementations (clean-room).
 *
 * @param <T> The listener type.
 * @see EventFactory
 */
public abstract class Event<T> {
    /**
     * The invoker field. Always refers to an instance containing all code that should be
     * executed upon event emission.
     */
    protected volatile T invoker;

    /**
     * Returns the invoker instance.
     *
     * <p>An "invoker" is an object which hides multiple registered listeners of type T under
     * one instance of type T, executing them and leaving early as necessary.
     *
     * @return The invoker instance.
     */
    public final T invoker() {
        return invoker;
    }

    /**
     * Register a listener to the event, in the default phase.
     *
     * @param listener The desired listener.
     */
    public abstract void register(T listener);

    /**
     * The identifier of the default phase.
     */
    public static final String DEFAULT_PHASE = "fabric:default";

    /**
     * Register a listener to the event for the specified phase.
     * (Phases are not ordered in this minimal implementation; falls back to default registration.)
     *
     * @param phase    Identifier of the phase this listener should be registered for.
     * @param listener The desired listener.
     */
    public void register(String phase, T listener) {
        register(listener);
    }

    /**
     * Request that listeners registered for one phase be executed before listeners registered
     * for another phase. (No-op in this minimal implementation.)
     */
    public void addPhaseOrdering(String firstPhase, String secondPhase) {
        // 极简实现不做阶段排序，保持接口兼容。
    }
}
