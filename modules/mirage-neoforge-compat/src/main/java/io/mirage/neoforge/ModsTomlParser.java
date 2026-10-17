package io.mirage.neoforge;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Clean-room 解析 jar 内 {@code META-INF/neoforge.mods.toml}。
 *
 * <p>只实现 NeoForge 描述符实际用到的 TOML 子集：
 * <ul>
 *   <li>顶层裸键值：{@code modLoader="javafml"} 等</li>
 *   <li>数组表表头：{@code [[mods]]}、{@code [[dependencies.<modid>]]}、
 *       {@code [[mixins]]}、{@code [[accessTransformers]]}</li>
 *   <li>值类型：双引号字符串、布尔、裸数字/range</li>
 *   <li>{@code #} 注释（行首或值后的空白后）</li>
 * </ul>
 *
 * <p>不引入 TOML 库，不依赖 refs/neoforge 的任何代码。
 */
public class ModsTomlParser {

    /** 一个 [[mods]] 表项。 */
    public static class ModEntry {
        public String modId = "";
        public String version = "";
        public String displayName = "";
        public String authors = "";
        public String description = "";
        public String credits = "";
        public String displayURL = "";
        public String bannerFile = "";
        public String iconFile = "";
    }

    /** 一个 [[dependencies.<modid>]] 表项。 */
    public static class DepEntry {
        public String owningModId = ""; // 表头里的 <modid>（本 mod）
        public String modId = "";       // 表内 modId（被依赖的 mod）
        public boolean mandatory = false;
        public String versionRange = "";
        public String ordering = "NONE";
        public String side = "BOTH";
    }

    /** 解析结果。 */
    public static class Result {
        public String modLoader = "";
        public String loaderVersion = "";
        public String license = "";
        public String issueTrackerURL = "";
        public final List<ModEntry> mods = new ArrayList<>();
        public final List<DepEntry> deps = new ArrayList<>();
        public final List<String> mixinConfigs = new ArrayList<>();
        public final List<String> accessTransformers = new ArrayList<>();
        /** 解析过程中的提示（占位符未解析、缺字段等），塞进 _diagnostics.unresolved。 */
        public final List<String> warnings = new ArrayList<>();
    }

    private final ZipFile zip;

    public ModsTomlParser(ZipFile zip) {
        this.zip = zip;
    }

    /** 在 jar 中定位 META-INF/neoforge.mods.toml 并解析；找不到返回 null。 */
    public Result parse() throws IOException {
        ZipEntry e = zip.getEntry("META-INF/neoforge.mods.toml");
        if (e == null) {
            // 兼容少数打包在根目录的情况
            e = zip.getEntry("neoforge.mods.toml");
        }
        if (e == null) return null;

        String text;
        try (InputStream in = zip.getInputStream(e)) {
            text = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        return parseText(text);
    }

    // ================================================================
    // 包级可见，便于单测（本模块暂不引入 JUnit，仅手工 fixture 验收）
    // ================================================================
    Result parseText(String text) {
        Result r = new Result();
        // 当前所在数组表的类型
        Section section = Section.NONE;
        ModEntry currentMod = null;
        DepEntry currentDep = null;

        for (String raw : text.split("\r?\n")) {
            String line = stripComment(raw).trim();
            if (line.isEmpty()) continue;

            if (line.startsWith("[[") && line.endsWith("]]")) {
                String header = line.substring(2, line.length() - 2).trim();
                currentMod = null;
                currentDep = null;
                if (header.equals("mods")) {
                    section = Section.MODS;
                    currentMod = new ModEntry();
                    r.mods.add(currentMod);
                } else if (header.startsWith("dependencies.")) {
                    section = Section.DEPS;
                    currentDep = new DepEntry();
                    currentDep.owningModId = header.substring("dependencies.".length()).trim();
                    r.deps.add(currentDep);
                } else if (header.equals("mixins")) {
                    section = Section.MIXINS;
                } else if (header.equals("accessTransformers")) {
                    section = Section.ATS;
                } else {
                    section = Section.OTHER;
                }
                continue;
            }
            if (line.startsWith("[") && line.endsWith("]")) {
                // 普通 [table] 头：NeoForge 描述符基本不用，忽略
                section = Section.OTHER;
                currentMod = null;
                currentDep = null;
                continue;
            }

            int eq = line.indexOf('=');
            if (eq < 0) continue;
            String key = line.substring(0, eq).trim();
            String value = line.substring(eq + 1).trim();

            switch (section) {
                case NONE -> applyTopLevel(r, key, value);
                case MODS -> { if (currentMod != null) applyModEntry(currentMod, key, value); }
                case DEPS -> { if (currentDep != null) applyDepEntry(currentDep, key, value); }
                case MIXINS -> {
                    if (key.equals("config")) r.mixinConfigs.add(unquote(value));
                }
                case ATS -> {
                    if (key.equals("file")) r.accessTransformers.add(unquote(value));
                }
                default -> { /* OTHER：忽略 */ }
            }
        }
        return r;
    }

    private enum Section { NONE, MODS, DEPS, MIXINS, ATS, OTHER }

    private void applyTopLevel(Result r, String key, String value) {
        String v = unquote(value);
        switch (key) {
            case "modLoader" -> r.modLoader = v;
            case "loaderVersion" -> r.loaderVersion = v;
            case "license" -> r.license = v;
            case "issueTrackerURL" -> r.issueTrackerURL = v;
            default -> { /* 顶层其它键（showLoadingBar 等）忽略 */ }
        }
    }

    private void applyModEntry(ModEntry m, String key, String value) {
        String v = unquote(value);
        switch (key) {
            case "modId" -> m.modId = v;
            case "version" -> m.version = v;
            case "displayName" -> m.displayName = v;
            case "authors" -> m.authors = v;
            case "description" -> m.description = v;
            case "credits" -> m.credits = v;
            case "displayURL" -> m.displayURL = v;
            case "bannerFile" -> m.bannerFile = v;
            case "iconFile" -> m.iconFile = v;
            default -> { /* 其它描述符字段忽略 */ }
        }
    }

    private void applyDepEntry(DepEntry d, String key, String value) {
        String v = unquote(value);
        switch (key) {
            case "modId" -> d.modId = v;
            case "mandatory" -> d.mandatory = Boolean.parseBoolean(v);
            case "versionRange" -> d.versionRange = v;
            case "ordering" -> d.ordering = v;
            case "side" -> d.side = v.toUpperCase();
            default -> { /* 忽略 */ }
        }
    }

    /** 去掉行尾注释：仅在引号外遇到 # 时截断。简化处理，够 fixture 用。 */
    private static String stripComment(String line) {
        boolean inStr = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') inStr = !inStr;
            else if (c == '#' && !inStr) return line.substring(0, i);
        }
        return line;
    }

    /** 去掉首尾成对双引号，并处理最简单的 \" 转义。 */
    static String unquote(String v) {
        String s = v.trim();
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            s = s.substring(1, s.length() - 1);
            s = s.replace("\\\"", "\"").replace("\\\\", "\\");
        }
        return s;
    }
}
