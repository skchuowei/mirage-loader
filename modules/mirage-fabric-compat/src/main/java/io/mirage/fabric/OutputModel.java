package io.mirage.fabric;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class OutputModel {
    public static class Src {
        public String origin;
        public String confidence;
        public Src() {}
        public Src(String o, String c) { origin = o; confidence = c; }
    }
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
    public static class DepInfo {
        public String modId;
        public String version;
        public String type = "REQUIRED";
        public String side = "BOTH";
    }
    public static class EventInfo {
        public String owner;
        public String method;
        public String desc;
        public String eventType;
        public int priority = 1000;
        public boolean receiveCancelled = false;
        public Src _src;
        public EventInfo() {}
        public EventInfo(String o, String m, String d, String e, Src s) { owner=o; method=m; desc=d; eventType=e; _src=s; }
    }
    public static class EntrypointInfo {
        public String stage;
        public String value;
        public String adapter = "default";
    }
    public static class ResourcesInfo { public List<String> namespaces = new ArrayList<>(); }
    public static class Stats {
        public int classCount;
        public int eventListenerCount;
        public int entrypointCount;
        public int dependencyCount;
    }
    public static class Diagnostics {
        public List<String> classes = new ArrayList<>();
        public List<String> mixinTargets = new ArrayList<>();
        public List<String> unresolved = new ArrayList<>();
        public Stats stats = new Stats();
    }

    public String mirageSpecVersion = "0.1";
    public ModInfo mod = new ModInfo();
    public List<DepInfo> dependencies = new ArrayList<>();
    public List<EventInfo> events = new ArrayList<>();
    public List<EntrypointInfo> entrypoints = new ArrayList<>();
    public ResourcesInfo resources = new ResourcesInfo();
    public Diagnostics _diagnostics = new Diagnostics();
}