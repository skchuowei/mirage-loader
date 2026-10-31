/*
 * Mirage Fabric Compat — clean-room reimplementation of Fabric's event API surface.
 *
 * Fabric (net.fabricmc.*) is licensed under the Apache License, Version 2.0.
 * This is an independent, minimal implementation that mirrors the public interface
 * so Fabric mods can register callbacks under Mirage without depending on fabric-loader.
 */
package net.fabricmc.fabric.api.event;

import java.lang.reflect.Array;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/**
 * Helper for creating {@link Event} classes (clean-room).
 */
public final class EventFactory {
    private EventFactory() {
    }

    /**
     * Create an "array-backed" Event instance.
     *
     * @param type           The listener class type.
     * @param invokerFactory The invoker factory, combining multiple listeners into one instance.
     * @param <T>            The listener type.
     * @return The Event instance.
     */
    public static <T> Event<T> createArrayBacked(Class<? super T> type, Function<T[], T> invokerFactory) {
        return new ArrayBackedEvent<>(type, invokerFactory);
    }

    /**
     * Create an "array-backed" Event instance with a custom empty invoker,
     * for an event whose {@code invokerFactory} only delegates to the listeners.
     *
     * @param type           The listener class type.
     * @param emptyInvoker   The custom empty invoker.
     * @param invokerFactory The invoker factory, combining multiple listeners into one instance.
     * @param <T>            The listener type.
     * @return The Event instance.
     */
    public static <T> Event<T> createArrayBacked(Class<T> type, T emptyInvoker, Function<T[], T> invokerFactory) {
        return createArrayBacked(type, listeners -> {
            if (listeners.length == 0) {
                return emptyInvoker;
            } else if (listeners.length == 1) {
                return listeners[0];
            } else {
                return invokerFactory.apply(listeners);
            }
        });
    }

    /**
     * Create an array-backed event with a list of default phases.
     * (Phases are ignored in this minimal implementation; equivalent to
     * {@link #createArrayBacked(Class, Function)}.)
     */
    public static <T> Event<T> createWithPhases(Class<? super T> type, Function<T[], T> invokerFactory, String... defaultPhases) {
        return createArrayBacked(type, invokerFactory);
    }

    static final class ArrayBackedEvent<T> extends Event<T> {
        private final Class<? super T> type;
        private final List<T> listeners = new CopyOnWriteArrayList<>();
        private final Function<T[], T> invokerFactory;

        ArrayBackedEvent(Class<? super T> type, Function<T[], T> invokerFactory) {
            this.type = type;
            this.invokerFactory = invokerFactory;
            rebuild();
        }

        @Override
        public void register(T listener) {
            listeners.add(listener);
            rebuild();
        }

        @Override
        public void register(String phase, T listener) {
            register(listener);
        }

        @SuppressWarnings("unchecked")
        private void rebuild() {
            T[] arr = (T[]) Array.newInstance(type, listeners.size());
            arr = listeners.toArray(arr);
            this.invoker = invokerFactory.apply(arr);
        }
    }
}
