package io.mirage.msr.agent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 * <p><b>不引入 JSON 库</b>：agent 是构建期工具，多带一个依赖就多一份 classpath 负担。
 * 这里手写最小 JSON 序列化（只处理本模块自己产出的数据结构，结构固定，不需要通用序列化器）。
 */
public final class MidIrWriter {

    private MidIrWriter() {}

    /**
     * 落盘观测结果。
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
        Path file = dir.resolve(baseline ? "baseline.mirage.json" : "recording.mirage.json");
        String json = toJson(baseline, registrations, subscriptions);
        Files.writeString(file, json, StandardCharsets.UTF_8);
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
        sb.append("  ]\n");
        sb.append("}\n");
        return sb.toString();
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
}
