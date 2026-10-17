package io.mirage.loader;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * 扫描 mods/ 目录。描述符查找顺序（IR 优先，legacy 保留作 fallback）：
 *   1. mod.mirage.json（jar 根）→ META-INF/mod.mirage.json   —— IR v0.1 层
 *   2. META-INF/mirage.mod.json                                —— 原生 legacy
 *   3. fabric.mod.json                                         —— fabric 兼容
 */
public class ModDiscoverer {
    private static final Gson GSON = new Gson();
    private static final String IR_DESC_ROOT = "mod.mirage.json";
    private static final String IR_DESC_META = "META-INF/mod.mirage.json";
    private static final String MIRAGE_DESC = "META-INF/mirage.mod.json";
    private static final String FABRIC_DESC = "fabric.mod.json";

    public List<ModContainer> discover(Path modsDir) throws IOException {
        return discover(modsDir, null, false, false);
    }

    /**
     * 扫描 mods 目录。
     * @param cache      MID 磁盘缓存；为 null 时不缓存（兼容老调用方）
     * @param forceRegen 为 true 时忽略缓存强制重走 NeoForgeModDetector
     * @param verbose    为 true 时打印缓存命中/未命中日志
     */
    public List<ModContainer> discover(Path modsDir, MidCache cache,
                                      boolean forceRegen, boolean verbose) throws IOException {
        List<ModContainer> result = new ArrayList<>();
        if (!Files.isDirectory(modsDir)) {
            System.out.println("[Mirage] mod 目录不存在: " + modsDir + "（跳过）");
            return result;
        }

        Map<String, ModContainer> byId = new HashMap<>();
        try (var stream = Files.list(modsDir)) {
            var jars = stream.filter(p -> p.getFileName().toString().endsWith(".jar")).toList();
            System.out.println("[Mirage] 扫描 " + jars.size() + " 个 jar，目录: " + modsDir);

            for (Path jar : jars) {
                try (JarFile jf = new JarFile(jar.toFile())) {
                    // ---- 1. IR 层 mod.mirage.json ----
                    JarEntry irEntry = jf.getJarEntry(IR_DESC_ROOT);
                    if (irEntry == null) irEntry = jf.getJarEntry(IR_DESC_META);
                    if (irEntry != null) {
                        try (InputStream in = jf.getInputStream(irEntry)) {
                            IrDescriptorParser.IrResult ir = IrDescriptorParser.parse(in);
                            if (ir == null || ir.id == null || ir.id.isEmpty()
                                    || ir.version == null || ir.version.isEmpty()) {
                                System.out.println("[Mirage]   ! " + jar.getFileName()
                                        + " IR 描述符无效，回退 legacy 检查");
                            } else {
                                if (byId.containsKey(ir.id)) {
                                    throw new IOException("Duplicate mod id '" + ir.id + "'");
                                }
                                ModManifest m = new ModManifest();
                                m.id = ir.id;
                                m.version = ir.version;
                                m.name = ir.id;
                                m.side = mapIrEnvironment(ir.environment);
                                m.mainClass = firstOrNull(ir.entrypoints.get("main"));
                                ModContainer c = new ModContainer(m, jar, ir.entrypoints, ir);
                                byId.put(ir.id, c);
                                result.add(c);
                                System.out.println("[Mirage]   + 发现 (IR " + ir.specVersion + ") " + c
                                        + " [main=" + ir.entrypoints.get("main").size()
                                        + ", client=" + ir.entrypoints.get("client").size()
                                        + ", server=" + ir.entrypoints.get("server").size() + "]");
                                continue;
                            }
                        }
                    }

                    // ---- 2. legacy META-INF/mirage.mod.json ----
                    JarEntry entry = jf.getJarEntry(MIRAGE_DESC);
                    if (entry != null) {
                        try (InputStream in = jf.getInputStream(entry);
                             InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                            // 先读成 JsonObject，既能 Gson 映射 POJO，又能兼容 "entrypoint" 这种别名 key
                            JsonObject json = GSON.fromJson(reader, JsonObject.class);
                            ModManifest m = GSON.fromJson(json, ModManifest.class);
                            if (m == null || !m.isValid()) {
                                System.out.println("[Mirage]   ! " + jar.getFileName() + " manifest 无效，跳过");
                                continue;
                            }
                            // MDK 模板里写的是 "entrypoint"，POJO 字段是 mainClass，这里补上别名映射
                            if (m.mainClass == null && json.has("entrypoint")) {
                                m.mainClass = json.get("entrypoint").getAsString();
                            }
                            if (byId.containsKey(m.id)) {
                                throw new IOException("Duplicate mod id '" + m.id + "'");
                            }
                            ModContainer c = new ModContainer(m, jar);
                            byId.put(m.id, c);
                            result.add(c);
                            System.out.println("[Mirage]   + 发现 (mirage) " + c);
                        }
                        continue;
                    }

                    // ---- 3. fabric.mod.json ----
                    JarEntry fabricEntry = jf.getJarEntry(FABRIC_DESC);
                    if (fabricEntry != null) {
                        try (InputStream in = jf.getInputStream(fabricEntry);
                             InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                            FabricModManifest fm = GSON.fromJson(reader, FabricModManifest.class);
                            if (fm == null || !fm.isValid()) {
                                System.out.println("[Mirage]   ! " + jar.getFileName() + " fabric.mod.json 无效，跳过");
                                continue;
                            }
                            ModManifest m = fm.toMirageManifest();
                            if (byId.containsKey(m.id)) {
                                throw new IOException("Duplicate mod id '" + m.id + "'");
                            }
                            // 把完整 entrypoints（main + client）规范化后存到容器上，
                            // Launcher 初始化阶段按 side 反射调用对应接口方法。
                            Map<String, List<String>> eps = new java.util.LinkedHashMap<>();
                            eps.put("main", fm.entrypointClasses("main"));
                            eps.put("client", fm.entrypointClasses("client"));
                            eps.put("server", List.of());
                            ModContainer c = new ModContainer(m, jar, eps);
                            byId.put(m.id, c);
                            result.add(c);
                            System.out.println("[Mirage]   + 发现 (fabric) " + c
                                    + " [main=" + eps.get("main").size()
                                    + ", client=" + eps.get("client").size() + "]");
                        }
                        continue;
                    }

                    // ---- 4. neoforge.mods.toml（带 MID 缓存） ----
                    NeoForgeModDetector.Detected neo = null;
                    MidCache.Entry cached = (cache != null)
                            ? cache.lookup(jar, null, forceRegen) : null;
                    if (cached != null) {
                        neo = cached.detected;
                        if (verbose) {
                            System.out.println("[Mirage]   · MID 缓存命中: " + neo.id
                                    + " v" + neo.version + "（跳过 ASM 扫 class）");
                        }
                    }
                    if (neo == null) {
                        neo = NeoForgeModDetector.detect(jf);
                        if (neo != null && cache != null) {
                            try {
                                cache.store(jar, neo);
                                if (verbose) System.out.println("[Mirage]   · MID 缓存已写: " + neo.id);
                            } catch (IOException e) {
                                System.err.println("[Mirage] MID 缓存写入失败: " + e);
                            }
                        }
                    }
                    if (neo != null) {
                        if (byId.containsKey(neo.id)) {
                            throw new IOException("Duplicate mod id '" + neo.id + "'");
                        }
                        ModManifest m = new ModManifest();
                        m.id = neo.id;
                        m.version = neo.version;
                        m.name = (neo.displayName == null || neo.displayName.isEmpty()) ? neo.id : neo.displayName;
                        m.side = "both";
                        m.mainClass = neo.mainClass;
                        Map<String, List<String>> eps = new java.util.LinkedHashMap<>();
                        eps.put("main", neo.mainClass != null ? List.of(neo.mainClass) : List.of());
                        eps.put("client", List.of());
                        eps.put("server", List.of());
                        ModContainer c = new ModContainer(m, jar, eps);
                        c.fromNeoForge = true;
                        byId.put(neo.id, c);
                        result.add(c);
                        System.out.println("[Mirage]   + 发现 (neoforge) " + c
                                + " [main=" + eps.get("main").size() + "]");
                        continue;
                    }

                    System.out.println("[Mirage]   ! " + jar.getFileName() + " 没有 mod 描述符，跳过");
                }
            }
        }
        return result;
    }

    /** IR environment（BOTH/CLIENT/SERVER）→ ModManifest.side（both/client/server）。 */
    private static String mapIrEnvironment(String env) {
        if (env == null || env.isBlank()) return "both";
        return switch (env.trim().toUpperCase()) {
            case "CLIENT" -> "client";
            case "SERVER" -> "server";
            default -> "both"; // BOTH 及未知值一律按 both（不 FATAL）
        };
    }

    private static String firstOrNull(List<String> list) {
        return (list == null || list.isEmpty()) ? null : list.get(0);
    }
}
