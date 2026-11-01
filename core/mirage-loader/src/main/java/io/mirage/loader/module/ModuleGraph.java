package io.mirage.loader.module;

import io.mirage.loader.ModManifest;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 模块依赖图上的纯逻辑（需求收集 + 依赖闭包）。
 *
 * <p><b>为什么单独抽一个类</b>：这两步是 G9 里唯一「算错了就会把游戏卡死或功能不生效」
 * 的纯算法，但 {@link ModuleRegistry} 本身依赖 {@code MirageClassLoader} /
 * {@code ModContainer} / {@code NeoForgeModDetector} 等重依赖，没法脱离游戏做 JVM 回归。
 * 抽到这里之后：
 * <ul>
 *   <li>本类只依赖 {@link ModuleDescriptor} 与 {@link ModManifest}（后者是纯 POJO），
 *       可以离线编译、离线断言；</li>
 *   <li>离线测试打的是<b>生产代码本身</b>，而不是测试里重写一遍的副本
 *       （重写副本的常见问题：测的是副本，生产代码改了测试照样全绿）。</li>
 * </ul>
 *
 * <p>所有方法都是纯函数：给定输入必然给出相同输出，不碰文件系统、不碰类加载器。
 */
public final class ModuleGraph {

    private ModuleGraph() {
    }

    /**
     * 收集所有 mod 声明的模块需求。
     *
     * @param manifests mod 清单（可含 null 元素）
     * @return 模块 id → 首个声明它的 mod id（保持声明顺序）
     */
    public static Map<String, String> collectRequests(Collection<ModManifest> manifests) {
        Map<String, String> out = new LinkedHashMap<>();
        if (manifests == null) {
            return out;
        }
        for (ModManifest manifest : manifests) {
            if (manifest == null || manifest.requires == null) {
                continue;
            }
            for (String req : manifest.requires) {
                if (req == null || req.isBlank()) {
                    continue;
                }
                out.putIfAbsent(req, manifest.id);
            }
        }
        return out;
    }

    /**
     * 递归展开依赖闭包：把 {@code id} 自身与它（递归）依赖的模块都放进 {@code out}。
     *
     * <p>用 {@code out} 兼作 visited 集合，防止循环依赖导致无限递归
     * （A 依赖 B、B 依赖 A 这种声明必须能终止，不能把启动卡死）。
     *
     * @param available  仓库里已有的模块表（id → 描述符）
     * @param id         起点模块 id
     * @param out        收集结果（会被就地修改）
     */
    public static void collectClosure(Map<String, ModuleDescriptor> available, String id, Set<String> out) {
        if (id == null || out.contains(id)) {
            return;
        }
        ModuleDescriptor d = available == null ? null : available.get(id);
        if (d == null) {
            return;   // 缺失的模块不展开（由调用方单独告警）
        }
        out.add(id);
        for (String dep : d.requires()) {
            collectClosure(available, dep, out);
        }
    }

    /**
     * 需求集合 → 需要加载的模块闭包。
     *
     * @param available  仓库里已有的模块表
     * @param requested  需求集合（模块 id → 来源 mod id）
     * @return 需要加载的模块 id（保持需求声明顺序，闭包内的依赖紧随其后）
     */
    public static Set<String> neededModules(Map<String, ModuleDescriptor> available,
                                            Map<String, String> requested) {
        Set<String> needed = new LinkedHashSet<>();
        if (requested == null) {
            return needed;
        }
        for (String id : requested.keySet()) {
            collectClosure(available, id, needed);
        }
        return needed;
    }
}
