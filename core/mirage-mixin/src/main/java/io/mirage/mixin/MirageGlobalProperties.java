package io.mirage.mixin;

import org.spongepowered.asm.service.IGlobalPropertyService;
import org.spongepowered.asm.service.IPropertyKey;

import java.util.HashMap;
import java.util.Map;

/**
 * MG 侧的 IGlobalPropertyService 空实现。
 *
 * Sponge Mixin 通过 SPI 加载它来存取 global properties（boot stage、tweak class 列表等）。
 * 内置的 mojang/modlauncher 实现都依赖特定启动器，在 MG 环境里类加载就会炸。
 * 这里用一个 HashMap 兜底。
 */
public class MirageGlobalProperties implements IGlobalPropertyService {

    private static final class Key implements IPropertyKey {
        final String name;
        Key(String name) { this.name = name; }
        @Override public boolean equals(Object o) { return o instanceof Key k && k.name.equals(name); }
        @Override public int hashCode() { return name.hashCode(); }
        @Override public String toString() { return name; }
    }

    private final Map<IPropertyKey, Object> props = new HashMap<>();

    @Override public IPropertyKey resolveKey(String name) { return new Key(name); }

    @Override @SuppressWarnings("unchecked")
    public <T> T getProperty(IPropertyKey key) { return (T) props.get(key); }

    @Override public void setProperty(IPropertyKey key, Object value) { props.put(key, value); }

    @Override public <T> T getProperty(IPropertyKey key, T defaultValue) {
        T v = getProperty(key);
        return v == null ? defaultValue : v;
    }

    @Override public String getPropertyString(IPropertyKey key, String defaultValue) {
        Object v = props.get(key);
        return v == null ? defaultValue : v.toString();
    }
}
