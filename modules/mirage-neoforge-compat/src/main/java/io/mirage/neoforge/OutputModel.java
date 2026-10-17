package io.mirage.neoforge;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MG IR v0.1 顶层模型（NeoForge 兼容层产出）。
 *
 * <p>严格对齐 docs/agents/A1-Spec/MG-IR隔离规范.md §1.1 与任务约定的 JSON 形状。
 * 所有字段 public，Gson 反射直接序列化。A4 运行时只读 §1.1 核心字段；
 * {@code _diagnostics} 下的内容仅供调试/QA，A4 不消费。
 */
public class OutputModel {

    /** _src 来源标记（origin/confidence）。 */
    public static class Src {
        public String origin;
        public String confidence;

        public Src() {}
        public Src(String origin, String confidence) {
            this.origin = origin;
            this.confidence = confidence;
        }
    }

    /** mod 块，§1.1。 */
    public static class ModInfo {
        public String id = "unknown";
        public String version = "0.0.0";
        public String group = "";
        public String environment = "BOTH";
        public Map<String, List<String>> entrypoints = new LinkedHashMap<>();
        public List<String> accessWideners = new ArrayList<>();

        public ModInfo() {
            entrypoints.put("main", new ArrayList<>());
            entrypoints.put("client", new ArrayList<>());
            entrypoints.put("server", new ArrayList<>());
        }
    }

    /** dependencies[] 条目。mandatory=true -> REQUIRED，否则 OPTIONAL。 */
    public static class DepInfo {
        public String modId;
        public String version;
        public String type = "REQUIRED";
        public String side = "BOTH";

        public DepInfo() {}
        public DepInfo(String modId, String version, String type, String side) {
            this.modId = modId;
            this.version = version;
            this.type = type;
            this.side = side;
        }
    }

    /** events[] 条目：@SubscribeEvent 标注的方法。 */
    public static class EventInfo {
        public String owner;
        public String method;
        public String desc;
        public String eventType;
        public int priority = 1000;
        public boolean receiveCancelled = false;
        public Src _src;

        public EventInfo() {}
        public EventInfo(String owner, String method, String desc, String eventType, Src src) {
            this.owner = owner;
            this.method = method;
            this.desc = desc;
            this.eventType = eventType;
            this._src = src;
        }
    }

    /** registries[] 条目：DeferredRegister 静态字段的注册提示。 */
    public static class RegistryHint {
        public String owner;
        public String field;
        public String declaredType;
        public String modIdHint;
        public Src _src;

        public RegistryHint() {}
        public RegistryHint(String owner, String field, String declaredType, String modIdHint, Src src) {
            this.owner = owner;
            this.field = field;
            this.declaredType = declaredType;
            this.modIdHint = modIdHint;
            this._src = src;
        }
    }

    /** resources 块，§1.1。 */
    public static class ResourcesInfo {
        public List<String> namespaces = new ArrayList<>();
    }

    /** 统计。 */
    public static class Stats {
        public int classCount;
        public int eventListenerCount;
        public int registryHintCount;
        public int dependencyCount;
    }

    /** 诊断块（A4 不读）。 */
    public static class Diagnostics {
        public List<String> classes = new ArrayList<>();
        public List<String> mixinTargets = new ArrayList<>();
        public List<String> unresolved = new ArrayList<>();
        public Stats stats = new Stats();
    }

    // ===== IR 核心字段（A4 消费）=====
    public String mirageSpecVersion = "0.1";
    public ModInfo mod = new ModInfo();
    public List<DepInfo> dependencies = new ArrayList<>();
    public List<EventInfo> events = new ArrayList<>();
    public List<RegistryHint> registries = new ArrayList<>();
    public ResourcesInfo resources = new ResourcesInfo();

    // ===== 诊断字段（A4 忽略）=====
    public Diagnostics _diagnostics = new Diagnostics();
}
