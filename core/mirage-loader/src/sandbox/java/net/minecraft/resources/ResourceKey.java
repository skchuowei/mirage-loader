package net.minecraft.resources;

/**
 * 沙盒 fake：MC {@code ResourceKey} 最小占位。
 * mod 静态初始化只把它当不透明键传入 {@code DeferredRegister.create(ResourceKey, String)}，
 * 这里提供静态 {@code create} 与取值方法，不真做注册表。
 */
public class ResourceKey<T> {

    private final Identifier identifier;

    private ResourceKey(Identifier identifier) {
        this.identifier = identifier;
    }

    public static <T> ResourceKey<T> create(ResourceKey<? extends net.minecraft.core.Registry<T>> registryKey,
                                            Identifier identifier) {
        return new ResourceKey<>(identifier);
    }

    public Identifier location() {
        return identifier;
    }

    public Identifier identifier() {
        return identifier;
    }

    @Override
    public String toString() {
        return "ResourceKey[" + (identifier == null ? "?" : identifier) + "]";
    }
}
