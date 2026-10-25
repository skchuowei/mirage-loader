package io.mirage.ls;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * C4 自定义周期配置（{@code mirage.ls.json}，LS-Interfaces.md v0.2 §1）。
 *
 * <p>结构草案（计划文档冻结）：
 * <pre>
 * {
 *   "cycle": "C2",
 *   "ag": ["harddecode","neoforge-compat"],
 *   "record": { "mode": "auto" },
 *   "parse": { "failPolicy": "confirm", "minCoverage": 0.8 },
 *   "stages": { "transform": { "enabled": false } }
 * }
 * </pre>
 *
 * <p>零依赖：内置最小 JSON 解析器（对象/数组/字符串/数字/布尔/null），
 * 不引 Gson（LS 运行时 classpath 上没有任何 JSON 库，且本结构足够扁平）。
 *
 * <p>校验失败一律 FATAL（exit 1）并给出可操作信息——配置写错必须响亮失败，
 * 不静默归默认（设计原则 3）。未知 key 仅 WARN（前向兼容）。
 */
public final class LsConfig {

    /** 阶段 id 规范名（配置里 s1..s7 / discovery..loaded 均可，大小写不敏感）。 */
    private static final Map<String, String> STAGE_ALIASES = Map.ofEntries(
            Map.entry("s1", "S1"), Map.entry("discovery", "S1"),
            Map.entry("s2", "S2"), Map.entry("parse", "S2"),
            Map.entry("s3", "S3"), Map.entry("transform", "S3"),
            Map.entry("s4", "S4"), Map.entry("registrymerge", "S4"),
            Map.entry("s5", "S5"), Map.entry("eventbus", "S5"),
            Map.entry("s6", "S6"), Map.entry("modinit", "S6"),
            Map.entry("s7", "S7"), Map.entry("loaded", "S7"));

    private final Path source;
    private final LifecycleMode cycle;          // 非 null（必填）
    private final String recordMode;            // off/auto/force；null = 继承周期模板
    private final ParseFailPolicy failPolicy;   // null = 继承周期模板
    private final double minCoverage;           // <0 = 未设置
    private final Set<String> agModules;        // 可能为空（phase 3 消费）
    private final Map<String, Boolean> stageToggles; // "S3" -> enabled

    private LsConfig(Path source, LifecycleMode cycle, String recordMode,
                     ParseFailPolicy failPolicy, double minCoverage,
                     Set<String> agModules, Map<String, Boolean> stageToggles) {
        this.source = source;
        this.cycle = cycle;
        this.recordMode = recordMode;
        this.failPolicy = failPolicy;
        this.minCoverage = minCoverage;
        this.agModules = agModules;
        this.stageToggles = stageToggles;
    }

    /**
     * 加载并校验配置。任何问题（文件不存在 / JSON 语法错 / 字段非法）→ FATAL exit 1。
     */
    public static LsConfig load(Path file) {
        String text;
        try {
            text = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            fatal("读取配置文件失败: " + file + " -> " + e);
            return null; // 不可达
        }
        Object root;
        try {
            root = new Json(text).parse();
        } catch (JsonException e) {
            fatal("mirage.ls.json 语法错误: " + e.getMessage() + " (offset " + e.offset + ")");
            return null; // 不可达
        }
        if (!(root instanceof Map<?, ?> rootMap)) {
            fatal("mirage.ls.json 根节点必须是 JSON 对象");
            return null; // 不可达
        }

        // cycle（必填）
        Object cycleV = rootMap.get("cycle");
        if (!(cycleV instanceof String cycleStr)) {
            fatal("mirage.ls.json 缺少必填字段 \"cycle\"（合法值: C1 | C2 | C3）");
            return null; // 不可达
        }
        LifecycleMode cycle = switch (cycleStr.trim().toLowerCase(Locale.ROOT)) {
            case "c1" -> LifecycleMode.C1;
            case "c2" -> LifecycleMode.C2;
            case "c3" -> LifecycleMode.C3;
            default -> {
                fatal("mirage.ls.json cycle 非法（合法值: C1 | C2 | C3；不允许 custom，禁止配置递归）: " + cycleStr);
                yield null; // 不可达
            }
        };

        // record.mode（可选）
        String recordMode = null;
        Object recordV = rootMap.get("record");
        if (recordV != null) {
            if (!(recordV instanceof Map<?, ?> recordMap)) {
                fatal("mirage.ls.json \"record\" 必须是对象，如 {\"mode\":\"auto\"}");
                return null; // 不可达
            }
            Object modeV = recordMap.get("mode");
            if (modeV != null) {
                if (!(modeV instanceof String modeStr)
                        || !List.of("off", "auto", "force").contains(modeStr.toLowerCase(Locale.ROOT))) {
                    fatal("mirage.ls.json record.mode 非法（合法值: off | auto | force）: " + modeV);
                    return null; // 不可达
                }
                recordMode = modeStr.toLowerCase(Locale.ROOT);
            }
        }

        // parse.failPolicy / parse.minCoverage（可选）
        ParseFailPolicy failPolicy = null;
        double minCoverage = -1;
        Object parseV = rootMap.get("parse");
        if (parseV != null) {
            if (!(parseV instanceof Map<?, ?> parseMap)) {
                fatal("mirage.ls.json \"parse\" 必须是对象，如 {\"failPolicy\":\"confirm\",\"minCoverage\":0.8}");
                return null; // 不可达
            }
            Object fpV = parseMap.get("failPolicy");
            if (fpV != null) {
                failPolicy = ParseFailPolicy.parse(String.valueOf(fpV));
                if (failPolicy == null) {
                    fatal("mirage.ls.json parse.failPolicy 非法（合法值: skip | crash | confirm）: " + fpV);
                    return null; // 不可达
                }
            }
            Object mcV = parseMap.get("minCoverage");
            if (mcV != null) {
                if (!(mcV instanceof Number mcNum) || mcNum.doubleValue() < 0 || mcNum.doubleValue() > 1) {
                    fatal("mirage.ls.json parse.minCoverage 必须是 0~1 之间的数字: " + mcV);
                    return null; // 不可达
                }
                minCoverage = mcNum.doubleValue();
            }
        }

        // stages（可选；S1/S7 不允许禁用）
        Map<String, Boolean> stageToggles = new LinkedHashMap<>();
        Object stagesV = rootMap.get("stages");
        if (stagesV != null) {
            if (!(stagesV instanceof Map<?, ?> stagesMap)) {
                fatal("mirage.ls.json \"stages\" 必须是对象，如 {\"transform\":{\"enabled\":false}}");
                return null; // 不可达
            }
            for (Map.Entry<?, ?> e : stagesMap.entrySet()) {
                String key = String.valueOf(e.getKey()).toLowerCase(Locale.ROOT);
                String stageId = STAGE_ALIASES.get(key);
                if (stageId == null) {
                    fatal("mirage.ls.json stages 含未知阶段: " + e.getKey()
                            + "（合法值: s1..s7 或 discovery/parse/transform/registrymerge/eventbus/modinit/loaded）");
                    return null; // 不可达
                }
                if (!(e.getValue() instanceof Map<?, ?> stageCfg)) {
                    fatal("mirage.ls.json stages." + e.getKey() + " 必须是对象，如 {\"enabled\":false}");
                    return null; // 不可达
                }
                Object enV = stageCfg.get("enabled");
                if (!(enV instanceof Boolean en)) {
                    fatal("mirage.ls.json stages." + e.getKey() + ".enabled 必须是布尔值: " + enV);
                    return null; // 不可达
                }
                if (!en && ("S1".equals(stageId) || "S7".equals(stageId))) {
                    fatal("mirage.ls.json 不允许禁用 " + stageId
                            + "（发现/移交是启动必经阶段；禁用=静默改变启动语义，违反原则 3）");
                    return null; // 不可达
                }
                stageToggles.put(stageId, en);
            }
        }

        // ag（可选；phase 3 ModuleHandle 消费，本期仅登记）
        Set<String> agModules = new LinkedHashSet<>();
        Object agV = rootMap.get("ag");
        if (agV != null) {
            if (!(agV instanceof List<?> agList)) {
                fatal("mirage.ls.json \"ag\" 必须是字符串数组");
                return null; // 不可达
            }
            for (Object o : agList) {
                if (!(o instanceof String s)) {
                    fatal("mirage.ls.json ag 数组元素必须是字符串: " + o);
                    return null; // 不可达
                }
                agModules.add(s);
            }
        }

        // 未知顶层 key → WARN（前向兼容，不 FATAL）
        for (Object k : rootMap.keySet()) {
            if (!Set.of("cycle", "ag", "record", "parse", "stages").contains(k)) {
                System.out.println("[LS] WARN mirage.ls.json 含未知字段 \"" + k + "\"（已忽略）");
            }
        }

        return new LsConfig(file, cycle, recordMode, failPolicy, minCoverage, agModules, stageToggles);
    }

    private static void fatal(String msg) {
        System.err.println("[LS] FATAL " + msg);
        System.exit(1);
    }

    public Path source() {
        return source;
    }

    public LifecycleMode cycle() {
        return cycle;
    }

    /** off/auto/force；null = 继承周期模板缺省。 */
    public String recordMode() {
        return recordMode;
    }

    public ParseFailPolicy failPolicy() {
        return failPolicy;
    }

    /** 未设置时返回 -1。 */
    public double minCoverage() {
        return minCoverage;
    }

    public Set<String> agModules() {
        return agModules;
    }

    /** 返回 null = 配置未提及该阶段（按缺省启用）。 */
    public Boolean stageEnabled(String stageId) {
        return stageToggles.get(stageId);
    }

    // ------------------------------------------------------------------
    // 最小 JSON 解析器（零依赖；仅支持本配置用到的结构）
    // ------------------------------------------------------------------

    private static final class JsonException extends RuntimeException {
        final int offset;

        JsonException(String msg, int offset) {
            super(msg);
            this.offset = offset;
        }
    }

    private static final class Json {
        private final String s;
        private int i;

        Json(String s) {
            this.s = s;
        }

        Object parse() {
            skipWs();
            Object v = value();
            skipWs();
            if (i < s.length()) throw err("结尾存在多余内容");
            return v;
        }

        private Object value() {
            skipWs();
            if (i >= s.length()) throw err("内容意外结束");
            char c = s.charAt(i);
            return switch (c) {
                case '{' -> object();
                case '[' -> array();
                case '"' -> string();
                case 't' -> literal("true", Boolean.TRUE);
                case 'f' -> literal("false", Boolean.FALSE);
                case 'n' -> literal("null", null);
                default -> number();
            };
        }

        private Map<String, Object> object() {
            Map<String, Object> m = new LinkedHashMap<>();
            i++; // '{'
            skipWs();
            if (peek() == '}') {
                i++;
                return m;
            }
            while (true) {
                skipWs();
                if (peek() != '"') throw err("对象 key 必须是字符串");
                String k = string();
                skipWs();
                if (peek() != ':') throw err("对象 key 后缺少 ':'");
                i++;
                m.put(k, value());
                skipWs();
                char c = peek();
                if (c == ',') {
                    i++;
                    continue;
                }
                if (c == '}') {
                    i++;
                    return m;
                }
                throw err("对象中缺少 ',' 或 '}'");
            }
        }

        private List<Object> array() {
            List<Object> l = new ArrayList<>();
            i++; // '['
            skipWs();
            if (peek() == ']') {
                i++;
                return l;
            }
            while (true) {
                l.add(value());
                skipWs();
                char c = peek();
                if (c == ',') {
                    i++;
                    continue;
                }
                if (c == ']') {
                    i++;
                    return l;
                }
                throw err("数组中缺少 ',' 或 ']'");
            }
        }

        private String string() {
            i++; // '"'
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (i >= s.length()) throw err("字符串未闭合");
                char c = s.charAt(i++);
                if (c == '"') return sb.toString();
                if (c == '\\') {
                    if (i >= s.length()) throw err("字符串转义未闭合");
                    char e = s.charAt(i++);
                    switch (e) {
                        case '"' -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case '/' -> sb.append('/');
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'u' -> {
                            if (i + 4 > s.length()) throw err("\\u 转义不完整");
                            String hex = s.substring(i, i + 4);
                            try {
                                sb.append((char) Integer.parseInt(hex, 16));
                            } catch (NumberFormatException nfe) {
                                throw err("\\u 转义非法: " + hex);
                            }
                            i += 4;
                        }
                        default -> throw err("未知转义: \\" + e);
                    }
                } else {
                    sb.append(c);
                }
            }
        }

        private Object number() {
            int start = i;
            if (peek() == '-') i++;
            while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
            if (i < s.length() && s.charAt(i) == '.') {
                i++;
                while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
            }
            if (i < s.length() && (s.charAt(i) == 'e' || s.charAt(i) == 'E')) {
                i++;
                if (i < s.length() && (s.charAt(i) == '+' || s.charAt(i) == '-')) i++;
                while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
            }
            String t = s.substring(start, i);
            if (t.isEmpty() || t.equals("-")) throw err("非法数字");
            try {
                return Double.parseDouble(t);
            } catch (NumberFormatException e) {
                throw err("非法数字: " + t);
            }
        }

        private Object literal(String word, Object v) {
            if (!s.startsWith(word, i)) throw err("非法字面量，期望 " + word);
            i += word.length();
            return v;
        }

        private char peek() {
            if (i >= s.length()) throw err("内容意外结束");
            return s.charAt(i);
        }

        private void skipWs() {
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') i++;
                else break;
            }
        }

        private JsonException err(String msg) {
            return new JsonException(msg, i);
        }
    }
}
