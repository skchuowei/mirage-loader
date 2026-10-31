package io.mirage.fabric;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class ModsJsonParser {
    public static class Result {
        public String id = "unknown";
        public String version = "0.0.0";
        public String environment = "BOTH";
        public List<String> mainEntrypoints = new java.util.ArrayList<>();
        public List<String> clientEntrypoints = new java.util.ArrayList<>();
        public List<String> serverEntrypoints = new java.util.ArrayList<>();
        public List<Dep> deps = new java.util.ArrayList<>();
        public List<String> mixinConfigs = new java.util.ArrayList<>();
        public List<String> accessWideners = new java.util.ArrayList<>();
        public List<String> warnings = new java.util.ArrayList<>();
    }
    public static class Dep {
        public String modId;
        public String version;
    }

    private final ZipFile zip;
    public ModsJsonParser(ZipFile zip) { this.zip = zip; }

    public Result parse() {
        Result r = new Result();
        ZipEntry e = zip.getEntry("fabric.mod.json");
        if (e == null) return null;
        try (InputStream in = zip.getInputStream(e)) {
            String txt = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            JsonObject root = JsonParser.parseString(txt).getAsJsonObject();
            if (root.has("id")) r.id = root.get("id").getAsString();
            if (root.has("version")) r.version = root.get("version").getAsString();
            if (root.has("environment")) r.environment = root.get("environment").getAsString();
            if (root.has("entrypoints")) {
                JsonObject eps = root.getAsJsonObject("entrypoints");
                if (eps.has("main")) addEp(eps.get("main"), r.mainEntrypoints);
                if (eps.has("client")) addEp(eps.get("client"), r.clientEntrypoints);
                if (eps.has("server")) addEp(eps.get("server"), r.serverEntrypoints);
            }
            if (root.has("depends")) {
                JsonObject deps = root.getAsJsonObject("depends");
                for (String k : deps.keySet()) {
                    Dep d = new Dep(); d.modId = k; d.version = deps.get(k).getAsString();
                    r.deps.add(d);
                }
            }
            if (root.has("mixins")) {
                JsonElement m = root.get("mixins");
                if (m.isJsonArray()) for (JsonElement x : m.getAsJsonArray()) r.mixinConfigs.add(x.getAsString());
                else r.mixinConfigs.add(m.getAsString());
            }
            if (root.has("accessWidener")) r.accessWideners.add(root.get("accessWidener").getAsString());
        } catch (Exception ex) {
            r.warnings.add("fabric.mod.json parse error: " + ex.getMessage());
        }
        // mixin configs root-level
        Enumeration<? extends ZipEntry> en = zip.entries();
        while (en.hasMoreElements()) {
            ZipEntry ze = en.nextElement();
            if (ze.getName().endsWith(".mixins.json") && !ze.getName().contains("/")) r.mixinConfigs.add(ze.getName());
        }
        return r;
    }

    private void addEp(JsonElement el, List<String> out) {
        if (el.isJsonArray()) for (JsonElement x : el.getAsJsonArray()) out.add(x.isJsonObject() ? x.getAsJsonObject().get("value").getAsString() : x.getAsString());
        else out.add(el.isJsonObject() ? el.getAsJsonObject().get("value").getAsString() : el.getAsString());
    }
}