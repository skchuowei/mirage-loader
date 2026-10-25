package net.neoforged.fml.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 最小 TOML 读写器（NeoForge 兼容 stub）。
 *
 * <p>支持：行注释（#）、扁平 {@code key = value}（字符串/整数/浮点/布尔/裸标识）、
 * 单层 {@code [table]} 段、{@code [[array of tables]]}。足以覆盖绝大多数 mod 配置文件。
 * 不实现完整 TOML 规范（多行字符串、内联表、日期等）。
 */
public final class TomlConfigIO {

    private TomlConfigIO() {}

    /** 解析 TOML 文本为嵌套 Map（段 -> 子 Map）。 */
    public static Map<String, Object> parse(String text) {
        Map<String, Object> root = new LinkedHashMap<>();
        Map<String, Object> current = root;
        for (String raw : text.split("\r?\n")) {
            String line = stripComment(raw).trim();
            if (line.isEmpty()) continue;
            if (line.startsWith("[[") && line.endsWith("]]")) {
                // 数组表：作为 List<Map> 处理（最少支持）
                String name = line.substring(2, line.length() - 2).trim();
                Map<String, Object> tbl = new LinkedHashMap<>();
                current = tbl;
                root.computeIfAbsent(name, k -> new ArrayList<Map<String, Object>>());
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> list = (List<Map<String, Object>>) root.get(name);
                list.add(tbl);
            } else if (line.startsWith("[") && line.endsWith("]")) {
                String name = line.substring(1, line.length() - 1).trim();
                Object existing = root.get(name);
                Map<String, Object> tbl;
                if (existing instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> m = (Map<String, Object>) existing;
                    tbl = m;
                } else {
                    tbl = new LinkedHashMap<>();
                    root.put(name, tbl);
                }
                current = tbl;
            } else {
                int eq = line.indexOf('=');
                if (eq < 0) continue;
                String key = line.substring(0, eq).trim();
                String val = line.substring(eq + 1).trim();
                current.put(key, parseValue(val));
            }
        }
        return root;
    }

    /** 把嵌套 Map 序列化为 TOML 文本（扁平键 + 单层 [table] 段）。 */
    public static String write(Map<String, Object> root) {
        StringBuilder sb = new StringBuilder();
        // 先写顶层扁平键
        for (Map.Entry<String, Object> e : root.entrySet()) {
            if (e.getValue() instanceof Map) continue;
            sb.append(e.getKey()).append(" = ").append(formatValue(e.getValue())).append('\n');
        }
        // 再写各 table 段
        for (Map.Entry<String, Object> e : root.entrySet()) {
            if (!(e.getValue() instanceof Map)) continue;
            @SuppressWarnings("unchecked")
            Map<String, Object> tbl = (Map<String, Object>) e.getValue();
            sb.append('\n').append('[').append(e.getKey()).append("]\n");
            for (Map.Entry<String, Object> te : tbl.entrySet()) {
                sb.append(te.getKey()).append(" = ").append(formatValue(te.getValue())).append('\n');
            }
        }
        return sb.toString();
    }

    public static Map<String, Object> readFile(Path file) throws IOException {
        if (!Files.exists(file)) return new LinkedHashMap<>();
        return parse(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
    }

    public static void writeFile(Path file, Map<String, Object> data) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, write(data), StandardCharsets.UTF_8);
    }

    // ----------------------------------------------------------------

    private static Object parseValue(String v) {
        if (v.isEmpty()) return "";
        if (v.startsWith("\"") && v.endsWith("\"")) {
            String s = v.substring(1, v.length() - 1);
            return s.replace("\\\"", "\"").replace("\\\\", "\\").replace("\\n", "\n");
        }
        if ("true".equalsIgnoreCase(v) || "false".equalsIgnoreCase(v)) {
            return Boolean.parseBoolean(v);
        }
        // 数字
        try {
            if (v.contains(".") || v.contains("e") || v.contains("E")) {
                return Double.parseDouble(v);
            }
            // 可能是十六进制
            if (v.toLowerCase().startsWith("0x")) {
                return Long.parseLong(v.substring(2), 16);
            }
            return Long.parseLong(v);
        } catch (NumberFormatException ignored) {}
        // 裸标识 / 其它：当字符串
        return v;
    }

    private static String formatValue(Object v) {
        if (v == null) return "\"\"";
        if (v instanceof String s) {
            return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
        }
        if (v instanceof Boolean || v instanceof Number) return v.toString();
        return "\"" + v + "\"";
    }

    private static String stripComment(String line) {
        boolean inStr = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') inStr = !inStr;
            else if (c == '#' && !inStr) return line.substring(0, i);
        }
        return line;
    }
}
