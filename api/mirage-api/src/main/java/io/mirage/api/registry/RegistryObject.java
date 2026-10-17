package io.mirage.api.registry;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * 延迟持有的注册对象。
 *
 * <p>{@link DeferredRegister#register(String, Supplier)} 返回的就是它。
 * 在游戏对象真正注册完成前调用 {@link #get()} 会抛
 * {@link IllegalStateException}，以避免在错误阶段使用未注册的对象。
 *
 * @param <T> 持有的对象类型
 */
public final class RegistryObject<T> implements Supplier<T> {

    private final RegistryKey<T> key;
    private T value;

    RegistryObject(RegistryKey<T> key) {
        this.key = key;
    }

    public RegistryKey<T> key() {
        return key;
    }

    /** 是否已完成注册。 */
    public boolean isPresent() {
        return value != null;
    }

    /** 拿到已注册对象；未注册时抛异常。 */
    @Override
    public T get() {
        if (value == null) {
            throw new IllegalStateException("RegistryObject " + key
                    + " 尚未注册完成；请在 ModInitEvent 之后再使用。");
        }
        return value;
    }

    public Optional<T> optional() {
        return Optional.ofNullable(value);
    }

    void set(T newValue) {
        if (this.value != null) {
            throw new IllegalStateException("RegistryObject " + key + " 重复注册");
        }
        this.value = Objects.requireNonNull(newValue);
    }
}
