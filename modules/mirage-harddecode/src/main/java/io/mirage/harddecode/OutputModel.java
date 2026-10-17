package io.mirage.harddecode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MG 静态描述 JSON 的顶层模型，对齐 A1 MG-Spec.md §0.1。
 * 所有字段用 public，Gson 直接反射序列化。
 */
public class OutputModel {

    /** 来源标记，§0.2。 */
    public static class Src {
        public String origin;
        public String confidence;
        public Boolean unresolved; // 仅 §0.3 的条目为 true

        public Src() {}
        public Src(String origin, String confidence) {
            this.origin = origin;
            this.confidence = confidence;
        }
        public Src(String origin, String confidence, boolean unresolved) {
            this.origin = origin;
            this.confidence = confidence;
            this.unresolved = unresolved;
        }
    }

    /** mod 字段，§0.1。 */
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

    /** 依赖条目，§5.1。静态从 fabric.mod.json depends 提取。 */
    public static class DepInfo {
        public String modId;
        public String version;
        public String type = "REQUIRED";
        public String side = "BOTH";
    }

    /** transforms 条目，§1.3 风格。A2 只登记 handler，不猜 target。 */
    public static class TransformInfo {
        public String kind = "METHOD";
        public String handler;       // 当前类#方法名+desc
        public String injector;     // INJECT / REDIRECT / ...
        public Src _src;

        public TransformInfo() {}
        public TransformInfo(String handler, String injector, Src src) {
            this.handler = handler;
            this.injector = injector;
            this._src = src;
        }
    }

    /** events 条目，§2.2 风格。A2 从 @SubscribeEvent 提取。 */
    public static class EventInfo {
        public String owner;
        public String method;       // 方法名
        public String desc;          // 方法描述符
        public String eventType;    // 唯一参数的内部类名
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

    /** 方法调用边：{from, to, method, desc}。 */
    public static class MethodCall {
        public String from;
        public String to;
        public String method;
        public String desc;

        public MethodCall() {}
        public MethodCall(String from, String to, String method, String desc) {
            this.from = from;
            this.to = to;
            this.method = method;
            this.desc = desc;
        }

        @Override public boolean equals(Object o) {
            if (!(o instanceof MethodCall m)) return false;
            return java.util.Objects.equals(from, m.from) && java.util.Objects.equals(to, m.to)
                && java.util.Objects.equals(method, m.method) && java.util.Objects.equals(desc, m.desc);
        }
        @Override public int hashCode() {
            return java.util.Objects.hash(from, to, method, desc);
        }
    }

    /** unresolved 条目，§0.3。 */
    public static class UnresolvedInfo {
        public String className;
        public String method;
        public String reason;
        public String name;
        public String desc;
        public Src _src;

        public UnresolvedInfo() {}
        public UnresolvedInfo(String className, String method, String reason, String name, String desc) {
            this.className = className;
            this.method = method;
            this.reason = reason;
            this.name = name;
            this.desc = desc;
            this._src = new Src("STATIC", "LOW", true);
        }
    }

    /** resources 字段，§4.1。 */
    public static class ResourcesInfo {
        public List<String> namespaces = new ArrayList<>();
    }

    /** _stats 统计。 */
    public static class Stats {
        public int classCount;
        public int methodCount;
        public int methodCallCount;
        public int unresolvedCount;
    }

    /** 诊断数据：A4 运行时不消费，仅供调试/QA/沙箱补漏。 */
    public static class Diagnostics {
        public List<ClassStructure> classes = new ArrayList<>();
        public List<MethodCall> methodCalls = new ArrayList<>();
        public List<String> mixinTargets = new ArrayList<>();
        public List<UnresolvedInfo> unresolved = new ArrayList<>();
        public Stats stats = new Stats();
    }

    // ===== IR 核心字段（A4 消费）=====
    public String mirageSpecVersion = "0.1";
    public ModInfo mod = new ModInfo();
    public List<DepInfo> dependencies = new ArrayList<>();
    public List<TransformInfo> transforms = new ArrayList<>();
    public List<EventInfo> events = new ArrayList<>();
    public List<Object> registries = new ArrayList<>();
    public ResourcesInfo resources = new ResourcesInfo();

    // ===== 诊断字段（A4 忽略）=====
    public Diagnostics _diagnostics = new Diagnostics();
}
