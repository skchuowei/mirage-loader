package io.mirage.ls.stage;

import io.mirage.api.env.Dist;
import io.mirage.loader.ModContainer;

import java.util.ArrayList;
import java.util.List;

/**
 * mod 入口点清单计算（保序迁移自拆分前 Launcher S6 循环内联逻辑；
 * S2 解析失败策略判定复用同一计算，避免两处逻辑漂移）。
 */
public final class ModEntrypoints {

    private ModEntrypoints() {}

    /** main 侧入口列表：fabric mod 用 entrypoints.main；mirage 原生用 mainClass。 */
    public static List<String> mainClasses(ModContainer mod) {
        List<String> mainClasses = new ArrayList<>();
        if (mod.fabricEntrypoints != null && mod.fabricEntrypoints.containsKey("main")) {
            mainClasses.addAll(mod.fabricEntrypoints.get("main"));
        }
        if (mainClasses.isEmpty() && mod.manifest.mainClass != null && !mod.manifest.mainClass.isEmpty()) {
            mainClasses.add(mod.manifest.mainClass);
        }
        return mainClasses;
    }

    /**
     * 端侧入口列表（仅 fabric mod 有）：物理客户端跑 entrypoints.client，
     * 专用服务端跑 entrypoints.server（P1-7；Fabric 语义：二者互斥，main 两端都跑）。
     */
    public static List<String> sideClasses(ModContainer mod, Dist dist) {
        return mod.fabricEntrypoints != null
                ? mod.fabricEntrypoints.getOrDefault(dist == Dist.DEDICATED_SERVER ? "server" : "client", List.of())
                : List.of();
    }
}
