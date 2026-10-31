package io.mirage.loader;

import io.mirage.api.log.MirageLog;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MG IR v0.1（mod.mirage.json）宽松解析器。
 *
 * 契约（见 docs/agents/A1-Spec/MG-IR隔离规范.md §1.1 / §1.2）：
 * - 只读核心字段：mirageSpecVersion / mod{id,version,group,environment,entrypoints{main,client,server},accessWideners}
 *   / dependencies / transforms / events / registries / resources.namespaces。
 * - 诊断字段（classes / methodCalls / mixinTargets / unresolved / _stats，以及将来迁到 _diagnostics
 *   子对象下的形态）一律静默忽略——本类只显式读取认识的 key，未知字段天然被丢弃，不做白名单反序列化报错。
 * - unresolved 条目不 FATAL，只打 WARN。
 */
public final class IrDescriptorParser {

    public static IrResult parse(InputStream in) {
        JsonObject root;
        try (InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            root = JsonParser.parseReader(reader).getAsJsonObject();
        } catch (Exception e) {
            MirageLog.LOADER.warn("IR 描述符解析失败（该 jar 将回退 legacy 检查）", e);
            return null;
        }

        IrResult r = new IrResult();
        r.specVersion = optString(root, "mirageSpecVersion");

        JsonObject mod = optObject(root, "mod");
        if (mod == null) {
            MirageLog.LOADER.warn("IR 描述符缺少 mod 对象，视为无效");
            return null;
        }
        r.id = optString(mod, "id");
        r.version = optString(mod, "version");
        r.group = optString(mod, "group");
        r.environment = optString(mod, "environment");

        JsonObject eps = optObject(mod, "entrypoints");
        r.entrypoints.put("main", stringList(eps, "main"));
        r.entrypoints.put("client", stringList(eps, "client"));
        r.entrypoints.put("server", stringList(eps, "server"));

        // 核心字段接住但暂不消费（A5/A3/后端注册阶段才会用）
        r.dependencies = optArray(root, "dependencies");
        r.transforms = optArray(root, "transforms");
        r.events = optArray(root, "events");
        r.registries = optArray(root, "registries");
        JsonObject resources = optObject(root, "resources");
        r.namespaces = stringList(resources, "namespaces");

        // unresolved：不阻断加载，WARN 一次即可
        JsonArray unresolved = optArray(root, "unresolved");
        if (unresolved != null && !unresolved.isEmpty()) {
            MirageLog.LOADER.debug("  ! IR 未解析条目 " + unresolved.size()
                    + " 条（沙箱待补，不阻断）mod=" + r.id);
        }
        // 诊断字段 classes/methodCalls/mixinTargets/_stats/_diagnostics：不读、不校验。
        return r;
    }

    /** IR 解析结果。未消费的核心字段以原始 JsonArray 形态挂在容器上，供后续阶段取用。 */
    public static final class IrResult {
        public String specVersion;
        public String id;
        public String version;
        public String group;
        public String environment;
        public Map<String, List<String>> entrypoints = new LinkedHashMap<>();
        public JsonArray dependencies;
        public JsonArray transforms;
        public JsonArray events;
        public JsonArray registries;
        public List<String> namespaces;
    }

    // ---- helpers：全部宽松，字段缺失/类型错误都返回 null 或空表 ----

    private static JsonObject optObject(JsonObject parent, String key) {
        if (parent == null) return null;
        JsonElement e = parent.get(key);
        return (e != null && e.isJsonObject()) ? e.getAsJsonObject() : null;
    }

    private static JsonArray optArray(JsonObject parent, String key) {
        if (parent == null) return null;
        JsonElement e = parent.get(key);
        return (e != null && e.isJsonArray()) ? e.getAsJsonArray() : null;
    }

    private static String optString(JsonObject parent, String key) {
        if (parent == null) return null;
        JsonElement e = parent.get(key);
        return (e != null && e.isJsonPrimitive()) ? e.getAsString() : null;
    }

    private static List<String> stringList(JsonObject parent, String key) {
        List<String> out = new ArrayList<>();
        JsonArray arr = optArray(parent, key);
        if (arr == null) return out;
        for (JsonElement e : arr) {
            if (e != null && e.isJsonPrimitive()) {
                out.add(e.getAsString());
            }
            // 非字符串元素（如 {"value":...} 对象写法）：IR 约定 entrypoints 是类名字符串列表，
            // 出现对象形态说明 A2 产出偏差，跳过该条目不报错。
        }
        return out;
    }

    private IrDescriptorParser() {}
}
