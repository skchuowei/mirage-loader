package io.mirage.msr.agent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * MSR P3 · 观测结果 → IR 组装器（2026-11-07）。
 *
 * <p>产物是 {@code mod.mirage.json} 风格的 IR（带实例的 NeoForge / MC 版本标签），
 * 供 AG 编译期转译消费。
 *
 * <p><b>输出格式（刻意用 JSON 而不是二进制）</b>：
 * <ul>
 *   <li>可读 ⇒ 人能直接 review「这个 mod 到底注册了什么」；</li>
 *   <li>可比对 ⇒ 基线实例与录制实例的 diff 就是「新 mod 引入的增量」；
 *       这正是 roadmap P3「游戏侧状态观测 + diff 驱动」的核心。</li>
 * </ul>
 *
 * <p><b>基线 diff</b>：空 mods 实例跑一次 = 基线（全量注册表快照 + 事件列表）；
 * 带 mod 的实例跑一次 = 录制；两者求差 = 「这个 mod 引入的注册 id / 事件订阅」。
 * <b>求差必须做</b>：不做求差的话，输出里全是原版内容，mod 的部分被淹没。
 *
 * <p><b>原子写</b>：写临时文件再 {@code ATOMIC_MOVE} 覆盖 —— 运行器会周期落盘，
 * 若写到一半被强杀，读者要么看到旧完整文件、要么看到新完整文件，不会看到半截 JSON。
 *
 * <p><b>不引入 JSON 库</b>：agent 是构建期工具，多带一个依赖就多一份 classpath 负担。
 * 这里手写最小 JSON 序列化（只处理本模块自己产出的数据结构，结构固定，不需要通用序列化器）。
 */
public final class MidIrWriter {

    private MidIrWriter() {}

    /** IR 文件名（基线 / 录制）。 */
    public static String fileName(boolean baseline) {
        return baseline ? "baseline.mirage.json" : "recording.mirage.json";
    }

    /**
     * 落盘观测结果（原子写）。
     *
     * @param outDir        输出目录
     * @param baseline      是否基线实例（基线文件名不同，便于后续求差）
     * @param registrations 注册记录
     * @param subscriptions 订阅记录
     */
    public static void write(String outDir, boolean baseline,
                             List<RecorderAgent.Registration> registrations,
                             List<RecorderAgent.Subscription> subscriptions) throws IOException {
        Path dir = Paths.get(outDir == null || outDir.isBlank() ? "mirage-msr-out" : outDir);
        Files.createDirectories(dir);
        Path file = dir.resolve(fileName(baseline));
        String json = toJson(baseline, registrations, subscriptions);
        Path tmp = dir.resolve(fileName(baseline) + ".tmp");
        Files.writeString(tmp, json, StandardCharsets.UTF_8);
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            // 某些文件系统不支持原子移动，退化为普通替换
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
        System.out.println("[MSR-P3] 观测结果已写出: " + file
                + "（注册 " + registrations.size() + " / 订阅 " + subscriptions.size() + "）");
    }

    /** 组装 JSON。 */
    public static String toJson(boolean baseline,
                                 List<RecorderAgent.Registration> registrations,
                                 List<RecorderAgent.Subscription> subscriptions) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"schema\": \"mirage.msr.ir/1\",\n");
        sb.append("  \"kind\": ").append(quote(baseline ? "baseline" : "recording")).append(",\n");
        sb.append("  \"registrations\": [\n");
        for (int i = 0; i < registrations.size(); i++) {
            RecorderAgent.Registration r = registrations.get(i);
            sb.append("    {")
              .append("\"owner\": ").append(quote(r.owner())).append(", ")
              .append("\"registry\": ").append(quote(r.registry())).append(", ")
              .append("\"id\": ").append(quote(r.id()))
              .append('}');
            if (i < registrations.size() - 1) sb.append(',');
            sb.append('\n');
        }
        sb.append("  ],\n");
        sb.append("  \"subscriptions\": [\n");
        for (int i = 0; i < subscriptions.size(); i++) {
            RecorderAgent.Subscription s = subscriptions.get(i);
            sb.append("    {")
              .append("\"owner\": ").append(quote(s.owner())).append(", ")
              .append("\"event\": ").append(quote(s.eventType()))
              .append('}');
            if (i < subscriptions.size() - 1) sb.append(',');
            sb.append('\n');
        }
        sb.append("  ],\n");
        sb.append("  \"stats\": ");
        sb.append(statsJson(registrations, subscriptions));
        sb.append('\n');
        sb.append("}\n");
        return sb.toString();
    }

    /** 统计段：规模、去重数、归属分布。 */
    static String statsJson(List<RecorderAgent.Registration> registrations,
                            List<RecorderAgent.Subscription> subscriptions) {
        Set<String> uniqIds = new LinkedHashSet<>();
        Map<String, Integer> owners = new TreeMap<>();
        for (RecorderAgent.Registration r : registrations) {
            uniqIds.add(r.registry() + "|" + r.id());
            owners.merge(r.owner(), 1, Integer::sum);
        }
        Map<String, Integer> eventOwners = new TreeMap<>();
        Set<String> uniqEvents = new LinkedHashSet<>();
        for (RecorderAgent.Subscription s : subscriptions) {
            uniqEvents.add(s.eventType());
            eventOwners.merge(s.owner(), 1, Integer::sum);
        }
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("    \"registrations\": ").append(registrations.size()).append(",\n");
        sb.append("    \"uniqueIds\": ").append(uniqIds.size()).append(",\n");
        sb.append("    \"subscriptions\": ").append(subscriptions.size()).append(",\n");
        sb.append("    \"uniqueEvents\": ").append(uniqEvents.size()).append(",\n");
        sb.append("    \"registrationOwners\": ").append(countMapJson(owners)).append(",\n");
        sb.append("    \"subscriptionOwners\": ").append(countMapJson(eventOwners)).append(",\n");
        // 录制期窗口隐藏的自证：`showWindowStripped` 为 0 ⇒ agent 的窗口插桩没接上
        // （此时画面能不能不出现只能靠运行器的 OS 层兜底，会闪一下）。
        sb.append("    \"windowHidden\": ").append(RecorderAgent.isHideWindow()).append(",\n");
        sb.append("    \"showWindowStripped\": ").append(WindowHideTransformer.strippedCalls()).append("\n");
        sb.append("  }");
        return sb.toString();
    }

    private static String countMapJson(Map<String, Integer> m) {
        if (m.isEmpty()) return "{}";
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Integer> e : m.entrySet()) {
            if (!first) sb.append(", ");
            first = false;
            sb.append(quote(e.getKey())).append(": ").append(e.getValue());
        }
        return sb.append('}').toString();
    }

    /**
     * 基线与录制求差 ⇒ 本 mod 引入的增量。
     *
     * @param baselineReg   基线的注册记录
     * @param baselineSubs  基线的订阅记录
     * @param recordReg     录制实例的注册记录
     * @param recordSubs    录制实例的订阅记录
     * @return 增量（key = "registry|id" / "bus|event"）
     */
    public static Map<String, Integer> diff(List<RecorderAgent.Registration> baselineReg,
                                            List<RecorderAgent.Subscription> baselineSubs,
                                            List<RecorderAgent.Registration> recordReg,
                                            List<RecorderAgent.Subscription> recordSubs) {
        Map<String, Integer> out = new TreeMap<>();
        java.util.Set<String> baseReg = new java.util.HashSet<>();
        for (RecorderAgent.Registration r : baselineReg) {
            baseReg.add(r.owner() + '|' + r.registry() + '|' + r.id());
        }
        for (RecorderAgent.Registration r : recordReg) {
            String k = r.owner() + '|' + r.registry() + '|' + r.id();
            if (!baseReg.contains(k)) out.merge(k, 1, Integer::sum);
        }
        java.util.Set<String> baseSubs = new java.util.HashSet<>();
        for (RecorderAgent.Subscription s : baselineSubs) {
            baseSubs.add(s.owner() + '|' + s.eventType());
        }
        for (RecorderAgent.Subscription s : recordSubs) {
            String k = s.owner() + '|' + s.eventType();
            if (!baseSubs.contains(k)) out.merge(k, 1, Integer::sum);
        }
        return out;
    }

    /** 把 diff 结果渲染成可读文本。 */
    public static String renderDiff(Map<String, Integer> diff) {
        StringBuilder sb = new StringBuilder();
        sb.append("== 增量（相对基线）共 ").append(diff.size()).append(" 项\n");
        for (Map.Entry<String, Integer> e : diff.entrySet()) {
            sb.append("  + ").append(e.getKey());
            if (e.getValue() > 1) sb.append("  ×").append(e.getValue());
            sb.append('\n');
        }
        return sb.toString();
    }

    private static String quote(String s) {
        if (s == null) return "\"\"";
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.append('"').toString();
    }

    /** 便捷：把列表转成可比较的 map（测试用）。 */
    public static Map<String, Integer> index(List<RecorderAgent.Registration> regs) {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (RecorderAgent.Registration r : regs) {
            m.merge(r.owner() + '|' + r.registry() + '|' + r.id(), 1, Integer::sum);
        }
        return m;
    }

    /** 便捷：订阅索引（测试用）。 */
    public static Map<String, Integer> indexSubs(List<RecorderAgent.Subscription> subs) {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (RecorderAgent.Subscription s : subs) {
            m.merge(s.owner() + '|' + s.eventType(), 1, Integer::sum);
        }
        return m;
    }

    /** 便捷：owner 列表（测试用）。 */
    public static List<String> owners(List<RecorderAgent.Registration> regs) {
        List<String> out = new ArrayList<>();
        for (RecorderAgent.Registration r : regs) {
            out.add(r.owner());
        }
        return out;
    }
}
