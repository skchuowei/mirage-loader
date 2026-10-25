package net.neoforged.fml.config;

import java.util.Map;

/**
 * 基于嵌套 Map 的 {@link ConfigData} 实现（由 {@link TomlConfigIO} 解析得到）。
 */
@SuppressWarnings("unchecked")
public final class TomlConfigData implements ConfigData {

    private final Map<String, Object> root;

    public TomlConfigData(Map<String, Object> root) {
        this.root = root;
    }

    public Map<String, Object> root() {
        return root;
    }

    private Object resolve(String path) {
        String[] parts = path.split("\\.");
        Object cur = root;
        for (String p : parts) {
            if (!(cur instanceof Map)) return null;
            cur = ((Map<String, Object>) cur).get(p);
        }
        return cur;
    }

    @Override
    public boolean contains(String path) {
        return resolve(path) != null;
    }

    @Override
    public Object get(String path) {
        return resolve(path);
    }

    @Override
    public String getString(String path) {
        Object v = resolve(path);
        return v == null ? "" : String.valueOf(v);
    }

    @Override
    public int getInt(String path) {
        Object v = resolve(path);
        if (v instanceof Number n) return n.intValue();
        if (v instanceof String s) {
            try { return Integer.parseInt(s.trim()); } catch (NumberFormatException ignored) {}
        }
        return 0;
    }

    @Override
    public long getLong(String path) {
        Object v = resolve(path);
        if (v instanceof Number n) return n.longValue();
        if (v instanceof String s) {
            try { return Long.parseLong(s.trim()); } catch (NumberFormatException ignored) {}
        }
        return 0L;
    }

    @Override
    public double getDouble(String path) {
        Object v = resolve(path);
        if (v instanceof Number n) return n.doubleValue();
        if (v instanceof String s) {
            try { return Double.parseDouble(s.trim()); } catch (NumberFormatException ignored) {}
        }
        return 0.0;
    }

    @Override
    public boolean getBoolean(String path) {
        Object v = resolve(path);
        if (v instanceof Boolean b) return b;
        if (v instanceof String s) return Boolean.parseBoolean(s.trim());
        return false;
    }
}
