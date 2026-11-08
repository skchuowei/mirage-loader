package io.mirage.msr.agent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * MSR P3 · 基线 / 录制差分器（2026-11-07）。
 *
 * <p><b>为什么必须求差</b>：P3 观测的是<b>游戏侧全局状态</b>，
 * 原版 MC 自己就有几千条注册和几百条事件订阅 —— 不求差的话，
 * mod 的那几十条会被原版内容彻底淹没。求差后剩下的就是
 * 「<b>这个 mod 引入的</b>注册项与事件订阅」，也就是可直接转译成 fake 的输入。
 *
 * <p><b>差分维度</b>（刻意不按 owner 求差）：
 * <ul>
 *   <li><b>按 (registry, id)</b> 求新增注册项 —— 这是语义上最稳的维度，
 *       不受「owner 归属判定是否准确」影响；</li>
 *   <li><b>按事件类名</b> 求新增订阅；</li>
 *   <li>owner 只作为<b>标注</b>出现在结果里（分不清时是 {@code unknown}），
 *       参与判定但不参与求差。</li>
 * </ul>
 *
 * <p><b>自带极简 JSON 解析器</b>：不引第三方依赖（agent 要尽量瘦），
 * 只支持本模块自己产出的 IR 结构（object / array / string / number / bool / null）。
 *
 * <p><b>用法</b>：
 * <pre>
 * java -cp mirage-msr-agent.jar io.mirage.msr.agent.BaselineDiffer \
 *      baseline.mirage.json recording.mirage.json [diff.mirage.json]
 * </pre>
 */
public final class BaselineDiffer {

    private BaselineDiffer() {}

    /** 一条新增注册项。 */
    public record NewId(String registry, String id, String owner) {}

    /** 一条新增事件订阅。 */
    public record NewEvent(String event, String owner) {}

    /** 差分结果。 */
    public record Result(List<NewId> newIds, List<NewEvent> newEvents,
                          int baselineRegs, int baselineSubs,
                          int recordRegs, int recordSubs,
                          Map<String, Integer> owners) {}

    /**
     * 读两个 IR 文件并求差。
     *
     * @param baselinePath  基线 IR 路径
     * @param recordingPath 录制 IR 路径
     * @return 差分结果
     * @throws IOException 读文件失败
     */
    public static Result diffFiles(String baselinePath, String recordingPath) throws IOException {
        Map<String, Object> base = parseObject(Files.readString(Paths.get(baselinePath), StandardCharsets.UTF_8));
        Map<String, Object> rec = parseObject(Files.readString(Paths.get(recordingPath), StandardCharsets.UTF_8));
        return diff(base, rec);
    }

    /**
     * 对两个已解析的 IR 求差。
     *
     * @param base 基线 IR
     * @param rec  录制 IR
     * @return 差分结果
     */
    public static Result diff(Map<String, Object> base, Map<String, Object> rec) {
        List<Map<String, Object>> baseRegs = objects(base, "registrations");
        List<Map<String, Object>> recRegs = objects(rec, "registrations");
        List<Map<String, Object>> baseSubs = objects(base, "subscriptions");
        List<Map<String, Object>> recSubs = objects(rec, "subscriptions");

        Set<String> baseIdKeys = new LinkedHashSet<>();
        for (Map<String, Object> o : baseRegs) {
            baseIdKeys.add(str(o, "registry") + "|" + str(o, "id"));
        }
        Set<String> baseEventKeys = new LinkedHashSet<>();
        for (Map<String, Object> o : baseSubs) {
            // 必须带 owner：同一个事件类可能被多个 mod 各订阅一次
            // （例如 FMLCommonSetupEvent 既被 neoforge 订阅、也被探针 mod 订阅）。
            // 只按事件名比对会把「新 mod 订阅了老事件」误判成「没变化」——
            // 本轮实测：msrprobe 的 2 条订阅就是这么被吞掉的。
            baseEventKeys.add(str(o, "owner") + "|" + str(o, "event"));
        }

        List<NewId> newIds = new ArrayList<>();
        Map<String, Integer> owners = new TreeMap<>();
        Set<String> seenId = new LinkedHashSet<>();
        for (Map<String, Object> o : recRegs) {
            String registry = str(o, "registry");
            String id = str(o, "id");
            String key = registry + "|" + id;
            if (baseIdKeys.contains(key)) continue;
            if (!seenId.add(key)) continue;
            String owner = str(o, "owner");
            newIds.add(new NewId(registry, id, owner));
            owners.merge(owner, 1, Integer::sum);
        }

        List<NewEvent> newEvents = new ArrayList<>();
        Set<String> seenEvent = new LinkedHashSet<>();
        for (Map<String, Object> o : recSubs) {
            String event = str(o, "event");
            String owner = str(o, "owner");
            if (baseEventKeys.contains(owner + "|" + event)) continue;
            if (!seenEvent.add(owner + "|" + event)) continue;
            newEvents.add(new NewEvent(event, owner));
        }

        return new Result(newIds, newEvents,
                baseRegs.size(), baseSubs.size(),
                recRegs.size(), recSubs.size(),
                owners);
    }

    /** 渲染差分结果为可读文本。 */
    public static String render(Result r) {
        StringBuilder sb = new StringBuilder();
        sb.append("== MSR P3 增量（录制 - 基线） ==\n");
        sb.append("  基线: 注册 ").append(r.baselineRegs()).append(" / 订阅 ").append(r.baselineSubs()).append('\n');
        sb.append("  录制: 注册 ").append(r.recordRegs()).append(" / 订阅 ").append(r.recordSubs()).append('\n');
        sb.append('\n');
        sb.append("-- 新增注册项 ").append(r.newIds().size()).append(" 条 --\n");
        for (NewId n : r.newIds()) {
            sb.append("  ").append(n.registry()).append(" | ").append(n.id());
            sb.append("    [owner=").append(n.owner()).append("]\n");
        }
        sb.append('\n');
        sb.append("-- 新增事件订阅 ").append(r.newEvents().size()).append(" 条 --\n");
        for (NewEvent n : r.newEvents()) {
            sb.append("  ").append(n.event());
            sb.append("    [owner=").append(n.owner()).append("]\n");
        }
        if (!r.owners().isEmpty()) {
            sb.append('\n');
            sb.append("-- 归属分布 --\n");
            for (Map.Entry<String, Integer> e : r.owners().entrySet()) {
                sb.append("  ").append(e.getKey()).append(": ").append(e.getValue()).append('\n');
            }
        }
        return sb.toString();
    }

    /** 把差分结果写成 JSON（供后续转译消费）。 */
    public static String toJson(Result r) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"schema\": \"mirage.msr.diff/1\",\n");
        sb.append("  \"newIds\": [\n");
        List<NewId> ids = r.newIds();
        for (int i = 0; i < ids.size(); i++) {
            NewId n = ids.get(i);
            sb.append("    {\"registry\": \"").append(esc(n.registry()))
              .append("\", \"id\": \"").append(esc(n.id()))
              .append("\", \"owner\": \"").append(esc(n.owner())).append("\"}");
            if (i < ids.size() - 1) sb.append(',');
            sb.append('\n');
        }
        sb.append("  ],\n");
        sb.append("  \"newEvents\": [\n");
        List<NewEvent> evs = r.newEvents();
        for (int i = 0; i < evs.size(); i++) {
            NewEvent n = evs.get(i);
            sb.append("    {\"event\": \"").append(esc(n.event()))
              .append("\", \"owner\": \"").append(esc(n.owner())).append("\"}");
            if (i < evs.size() - 1) sb.append(',');
            sb.append('\n');
        }
        sb.append("  ]\n");
        sb.append("}\n");
        return sb.toString();
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    // ---------------------------------------------------------------- 极简 JSON

    /**
     * 解析 JSON（仅支持 IR 用到的类型）。
     *
     * @param text JSON 文本
     * @return Map / List / String / Double / Boolean / null
     */
    public static Object parse(String text) {
        return new JsonParser(text).parse();
    }

    /** 解析 JSON 文本为 Map（顶层必须是对象）。 */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) {
        Object v = parse(text);
        if (!(v instanceof Map)) throw new IllegalArgumentException("JSON 顶层不是对象");
        return (Map<String, Object>) v;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> objects(Map<String, Object> root, String key) {
        Object v = root.get(key);
        if (!(v instanceof List)) return new ArrayList<>();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object o : (List<Object>) v) {
            if (o instanceof Map) out.add((Map<String, Object>) o);
        }
        return out;
    }

    private static String str(Map<String, Object> o, String key) {
        Object v = o.get(key);
        return v == null ? "" : v.toString();
    }

    /** 极简 JSON 解析器（仅支持 object / array / string / number / bool / null）。 */
    private static final class JsonParser {
        private final String s;
        private int i;

        JsonParser(String s) { this.s = s; }

        Object parse() {
            ws();
            Object v = value();
            ws();
            return v;
        }

        private void ws() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        }

        private Object value() {
            ws();
            if (i >= s.length()) return null;
            char c = s.charAt(i);
            switch (c) {
                case '{': return object();
                case '[': return array();
                case '"': return string();
                case 't':
                    i += 4; return Boolean.TRUE;
                case 'f':
                    i += 5; return Boolean.FALSE;
                case 'n':
                    i += 4; return null;
                default: return number();
            }
        }

        private Map<String, Object> object() {
            Map<String, Object> m = new LinkedHashMap<>();
            i++; // {
            ws();
            if (i < s.length() && s.charAt(i) == '}') { i++; return m; }
            while (i < s.length()) {
                ws();
                String k = string();
                ws();
                if (i < s.length() && s.charAt(i) == ':') i++;
                Object v = value();
                m.put(k, v);
                ws();
                if (i < s.length() && s.charAt(i) == ',') { i++; continue; }
                if (i < s.length() && s.charAt(i) == '}') { i++; break; }
                break;
            }
            return m;
        }

        private List<Object> array() {
            List<Object> l = new ArrayList<>();
            i++; // [
            ws();
            if (i < s.length() && s.charAt(i) == ']') { i++; return l; }
            while (i < s.length()) {
                l.add(value());
                ws();
                if (i < s.length() && s.charAt(i) == ',') { i++; continue; }
                if (i < s.length() && s.charAt(i) == ']') { i++; break; }
                break;
            }
            return l;
        }

        private String string() {
            StringBuilder sb = new StringBuilder();
            if (i >= s.length() || s.charAt(i) != '"') return "";
            i++;
            while (i < s.length()) {
                char c = s.charAt(i++);
                if (c == '"') break;
                if (c == '\\' && i < s.length()) {
                    char e = s.charAt(i++);
                    switch (e) {
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'u' -> {
                            if (i + 4 <= s.length()) {
                                sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                                i += 4;
                            }
                        }
                        default -> sb.append(e);
                    }
                } else {
                    sb.append(c);
                }
            }
            return sb.toString();
        }

        private Double number() {
            int start = i;
            while (i < s.length() && "-+.eE0123456789".indexOf(s.charAt(i)) >= 0) i++;
            try {
                return Double.valueOf(s.substring(start, i));
            } catch (RuntimeException e) {
                return 0d;
            }
        }
    }

    /** 命令行入口：{@code BaselineDiffer <baseline.json> <recording.json> [diffOut.json]}。 */
    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            System.err.println("用法: BaselineDiffer <baseline.json> <recording.json> [diffOut.json]");
            System.exit(2);
        }
        Result r = diffFiles(args[0], args[1]);
        String text = render(r);
        System.out.print(text);
        System.out.println("[MSR-P3] 新增注册项 " + r.newIds().size()
                + " / 新增事件订阅 " + r.newEvents().size());
        if (args.length >= 3) {
            Path out = Paths.get(args[2]);
            if (out.getParent() != null) Files.createDirectories(out.getParent());
            Files.writeString(out, toJson(r), StandardCharsets.UTF_8);
            System.out.println("[MSR-P3] 增量已写出: " + out);
        }
        // 有增量才算成功；没有增量不视为失败（可能是空 mod）
        System.exit(0);
    }
}
