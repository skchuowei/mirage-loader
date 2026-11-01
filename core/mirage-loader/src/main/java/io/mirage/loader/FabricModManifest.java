package io.mirage.loader;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * fabric.mod.json 的 POJO（Fabric 兼容层）。
 * 放在 Mod jar 的 fabric.mod.json。
 */
public class FabricModManifest {
    public String id;
    public String version;
    public String name;
    public String description;
    public String[] authors;
    public Map<String, Object> depends;

    /**
     * 环境字符串："client" / "dedicated" / "*" / null。
     * 注意：fabric.mod.json 里是小写字符串，不能直接 Gson 到 enum（大小写敏感会炸）。
     */
    public String environment;

    /**
     * entrypoints 原始映射：side -> JsonElement 列表。
     * 元素既可能是纯类名字符串，也可能是 {"value": "...", "adapter": "..."} 对象，
     * 所以保留 JsonElement 形态，由 {@link #entrypointClasses(String)} 统一规范化。
     */
    public Map<String, List<JsonElement>> entrypoints;

    /**
     * 声明的 mixin config 资源路径列表（fabric.mod.json 的 "mixins" 字段）。
     * 元素通常是字符串（相对 jar 根的路径，如 "modid.mixins.json"，可含子目录）；
     * 也可能罕见的对象形态（{"container": "...", "file": "..."}），本实现不支持、跳过并告警。
     * 必须用 JsonElement 防御：Gson 直读 List&lt;String&gt; 遇对象元素会抛 JsonSyntaxException，
     * 导致整个 manifest 解析失败、mod 被跳过。
     */
    public List<JsonElement> mixins;

    public boolean isValid() {
        if (id == null || id.isEmpty()) return false;
        if (version == null || version.isEmpty()) return false;
        return true;
    }

    /**
     * 取出某一侧（"main" / "client" ...）的 entrypoint 类名列表。
     * 同时兼容字符串写法和 {"value": "..."} 对象写法。
     */
    public List<String> entrypointClasses(String side) {
        List<String> out = new ArrayList<>();
        if (entrypoints == null) return out;
        List<JsonElement> raw = entrypoints.get(side);
        if (raw == null) return out;
        for (JsonElement el : raw) {
            if (el == null || el.isJsonNull()) continue;
            if (el.isJsonPrimitive()) {
                out.add(el.getAsString());
            } else if (el.isJsonObject()) {
                JsonObject o = el.getAsJsonObject();
                if (o.has("value")) out.add(o.get("value").getAsString());
            }
        }
        return out;
    }

    /**
     * 取出声明的 mixin config 路径列表（仅字符串形态）。
     * 对象形态（{"container","file"}）暂不支持：跳过并 WARN 指名，不让整个 mod 失败。
     */
    public List<String> mixinConfigPaths() {
        List<String> out = new ArrayList<>();
        if (mixins == null) return out;
        for (JsonElement el : mixins) {
            if (el == null || el.isJsonNull()) continue;
            if (el.isJsonPrimitive()) {
                String s = el.getAsString();
                if (s != null && !s.isEmpty()) out.add(s);
            } else if (el.isJsonObject()) {
                io.mirage.api.log.MirageLog.LOADER.warn("! mod '" + id + "' 的 fabric.mod.json mixins 含对象形态声明（"
                        + el + "），暂不支持，已跳过");
            }
        }
        return out;
    }

    /** 转成 Mirage ModManifest（只取 main 的第一个作为兼容用主类；完整 entrypoints 由 ModContainer 另存）。 */
    public ModManifest toMirageManifest() {
        ModManifest m = new ModManifest();
        m.id = id;
        m.version = version;
        m.name = name != null ? name : id;
        List<String> mainEps = entrypointClasses("main");
        if (!mainEps.isEmpty()) {
            m.mainClass = mainEps.get(0);
        }
        m.depends = null;
        String env = environment == null ? "*" : environment;
        m.side = "client".equals(env) ? "client"
                : "dedicated".equals(env) ? "server" : "both";
        return m;
    }
}
