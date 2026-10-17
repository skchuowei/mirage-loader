package io.mirage.harddecode;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 读取 jar 根目录 / META-INF 元数据：
 * fabric.mod.json / mirage.mod.json / MANIFEST.MF / *.mixins.json。
 * 只做提取，不做语义校验。
 */
public class MetaReader {

    private final ZipFile zip;

    public MetaReader(ZipFile zip) {
        this.zip = zip;
    }

    /** 读取并解析所有可识别的元数据，结果直接填入 model。 */
    public void readInto(OutputModel model) throws IOException {
        // 1. MANIFEST.MF -> version
        ZipEntry mf = zip.getEntry("META-INF/MANIFEST.MF");
        if (mf != null) {
            try (InputStream in = zip.getInputStream(mf)) {
                Manifest manifest = new Manifest(in);
                String implVersion = manifest.getMainAttributes().getValue("Implementation-Version");
                if (implVersion != null && !implVersion.isBlank()) {
                    model.mod.version = implVersion;
                }
            }
        }

        // 2. fabric.mod.json（根目录）
        JsonObject fabric = readJsonEntry("fabric.mod.json");
        if (fabric != null) {
            applyFabricModJson(model, fabric);
        }

        // 3. mirage.mod.json（根目录或 META-INF/）
        JsonObject mirage = readJsonEntry("mirage.mod.json");
        if (mirage == null) mirage = readJsonEntry("META-INF/mirage.mod.json");
        if (mirage != null) {
            if (mirage.has("id")) model.mod.id = mirage.get("id").getAsString();
            if (mirage.has("version")) model.mod.version = mirage.get("version").getAsString();
            if (mirage.has("group")) model.mod.group = mirage.get("group").getAsString();
            if (mirage.has("environment")) {
                model.mod.environment = mirage.get("environment").getAsString();
            } else if (mirage.has("side")) {
                // mirage.mod.json 用 side: "both"/"client"/"server"
                String side = mirage.get("side").getAsString();
                model.mod.environment = switch (side) {
                    case "both" -> "BOTH";
                    case "client" -> "CLIENT";
                    case "server" -> "SERVER";
                    default -> "BOTH";
                };
            }
            // mainClass -> entrypoints.main
            if (mirage.has("mainClass")) {
                List<String> mainList = model.mod.entrypoints.get("main");
                if (mainList == null) {
                    mainList = new ArrayList<>();
                    model.mod.entrypoints.put("main", mainList);
                }
                mainList.add(mirage.get("mainClass").getAsString());
            }
            if (mirage.has("entrypoints")) {
                applyEntrypoints(model, mirage.getAsJsonObject("entrypoints"));
            }
            if (mirage.has("depends")) {
                applyDepends(model, mirage.getAsJsonObject("depends"));
            }
        }

        // 4. *.mixins.json：列出所有以 .mixins.json 结尾的条目（根目录）
        java.util.Enumeration<? extends ZipEntry> en = zip.entries();
        while (en.hasMoreElements()) {
            ZipEntry e = en.nextElement();
            String name = e.getName();
            if (name.endsWith(".mixins.json") && !name.contains("/")) {
                model._diagnostics.mixinTargets.add(name);
            }
        }
    }

    private void applyFabricModJson(OutputModel model, JsonObject f) {
        if (f.has("id")) model.mod.id = f.get("id").getAsString();
        if (f.has("version")) model.mod.version = f.get("version").getAsString();
        if (f.has("group")) model.mod.group = f.get("group").getAsString();
        if (f.has("environment")) model.mod.environment = f.get("environment").getAsString();
        if (f.has("entrypoints")) {
            applyEntrypoints(model, f.getAsJsonObject("entrypoints"));
        }
        if (f.has("depends")) {
            applyDepends(model, f.getAsJsonObject("depends"));
        }
    }

    private void applyEntrypoints(OutputModel model, JsonObject eps) {
        for (String stage : new String[]{"main", "client", "server"}) {
            if (!eps.has(stage)) continue;
            List<String> list = model.mod.entrypoints.get(stage);
            if (list == null) { list = new ArrayList<>(); model.mod.entrypoints.put(stage, list); }
            JsonElement el = eps.get(stage);
            if (el.isJsonArray()) {
                for (JsonElement item : el.getAsJsonArray()) {
                    list.add(extractEntrypointClass(item));
                }
            } else {
                list.add(extractEntrypointClass(el));
            }
        }
    }

    private String extractEntrypointClass(JsonElement item) {
        if (item.isJsonObject()) {
            return item.getAsJsonObject().get("value").getAsString();
        }
        return item.getAsString();
    }

    private void applyDepends(OutputModel model, JsonObject depends) {
        for (String key : depends.keySet()) {
            OutputModel.DepInfo dep = new OutputModel.DepInfo();
            dep.modId = key;
            JsonElement v = depends.get(key);
            dep.version = v.isJsonPrimitive() ? v.getAsString() : v.toString();
            model.dependencies.add(dep);
        }
    }

    private JsonObject readJsonEntry(String path) {
        ZipEntry e = zip.getEntry(path);
        if (e == null) return null;
        try (InputStream in = zip.getInputStream(e)) {
            return JsonParser.parseString(readAll(in)).getAsJsonObject();
        } catch (Exception ex) {
            return null;
        }
    }

    static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] tmp = new byte[4096];
        int n;
        while ((n = in.read(tmp)) != -1) buf.write(tmp, 0, n);
        return buf.toString("UTF-8");
    }
}
