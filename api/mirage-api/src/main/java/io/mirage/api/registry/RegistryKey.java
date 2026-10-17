package io.mirage.api.registry;

import java.util.Objects;

/**
 * 类型安全的注册键：指向某个注册表（{@code registry}）里的某个值（{@code path}）。
 *
 * <p>例如 {@code minecraft:item} 注册表下的 {@code mymod:ruby}：
 * <pre>{@code
 * RegistryKey<Item> rubyKey = RegistryKey.create("item", "mymod", "ruby");
 * }</pre>
 *
 * @param <T> 被注册值的类型
 */
public final class RegistryKey<T> {

    private final String registry;
    private final String namespace;
    private final String path;

    private RegistryKey(String registry, String namespace, String path) {
        this.registry = Objects.requireNonNull(registry);
        this.namespace = Objects.requireNonNull(namespace);
        this.path = Objects.requireNonNull(path);
    }

    public static <T> RegistryKey<T> create(String registry, String namespace, String path) {
        return new RegistryKey<>(registry, namespace, path);
    }

    public String registry()  { return registry; }
    public String namespace() { return namespace; }
    public String path()      { return path; }

    /** 形如 {@code minecraft:item / mymod:ruby}。 */
    public String location() {
        return namespace + ":" + path;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof RegistryKey<?> other)) return false;
        return registry.equals(other.registry)
                && namespace.equals(other.namespace)
                && path.equals(other.path);
    }

    @Override
    public int hashCode() {
        return Objects.hash(registry, namespace, path);
    }

    @Override
    public String toString() {
        return "RegistryKey[" + registry + " / " + location() + "]";
    }
}
