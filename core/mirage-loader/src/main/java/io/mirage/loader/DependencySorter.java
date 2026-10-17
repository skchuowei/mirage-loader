package io.mirage.loader;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 依赖拓扑排序：依赖先于被依赖者加载。
 * M2 阶段只做存在性校验 + 环检测，版本范围比较留给后续。
 */
public class DependencySorter {

    public List<ModContainer> sort(List<ModContainer> mods) {
        Map<String, ModContainer> byId = new HashMap<>();
        for (ModContainer c : mods) byId.put(c.manifest.id, c);

        // 1. 缺失依赖检查
        for (ModContainer c : mods) {
            if (c.manifest.depends == null) continue;
            for (String dep : c.manifest.depends.keySet()) {
                if (!byId.containsKey(dep)) {
                    throw new IllegalStateException("[Mirage] mod '" + c.manifest.id
                            + "' 缺少依赖: " + dep);
                }
            }
        }

        // 2. Kahn 拓扑排序
        Map<String, Set<String>> incoming = new HashMap<>(); // id -> 依赖它的人
        Map<String, Integer> outDegree = new HashMap<>();
        for (String id : byId.keySet()) {
            incoming.put(id, new HashSet<>());
            outDegree.put(id, 0);
        }
        for (ModContainer c : mods) {
            if (c.manifest.depends == null) continue;
            for (String dep : c.manifest.depends.keySet()) {
                incoming.get(dep).add(c.manifest.id);
                outDegree.merge(c.manifest.id, 1, Integer::sum);
            }
        }

        List<ModContainer> queue = new ArrayList<>();
        for (ModContainer c : mods) {
            if (outDegree.get(c.manifest.id) == 0) queue.add(c);
        }
        List<ModContainer> sorted = new ArrayList<>();
        while (!queue.isEmpty()) {
            ModContainer cur = queue.remove(0);
            sorted.add(cur);
            for (String dependent : incoming.get(cur.manifest.id)) {
                int d = outDegree.merge(dependent, -1, Integer::sum);
                if (d == 0) {
                    ModContainer dc = byId.get(dependent);
                    if (dc != null) queue.add(dc);
                }
            }
        }

        if (sorted.size() != mods.size()) {
            throw new IllegalStateException("[Mirage] 检测到依赖循环，涉及 "
                    + (mods.size() - sorted.size()) + " 个 mod");
        }
        return sorted;
    }
}
