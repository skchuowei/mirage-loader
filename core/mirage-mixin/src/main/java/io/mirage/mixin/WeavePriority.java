package io.mirage.mixin;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 织入优先级裁定（用户拍板·社区最大化，见 {@code AGENTS.md} 技术注意事项 §16）。
 *
 * <h2>裁定原文</h2>
 * 「{@code 原版字节 → mod Mixin → MG 自研织入 → defineClass}。MG 自研 transformer 目标类（硬编码 7 个）
 * 与 mod 的 {@code @Mixin} 目标求交集，命中即<strong>放弃 MG 对该类的织入</strong>，
 * 并打 WARN 说明放弃了什么——禁止无声放弃。先做类级交集，验证后再细化到方法级。」
 *
 * <h2>本类负责什么</h2>
 * <ol>
 *   <li>{@link #MG_TARGETS}：MG 自研 ASM transformer 的硬编码目标类 → 该织入做什么（让路 WARN 的白话说明）；</li>
 *   <li>{@link #setModMixinTargets}：{@code MirageMixinBootstrap.init} 收集到的 mod {@code @Mixin} 目标类快照；</li>
 *   <li>{@link #decide(String)} / {@link #decide(String, Set)}：<strong>纯函数</strong>判定「这一类的 MG 自研织入要不要让路」，
 *       后者不碰静态态，可离线单测（门禁 {@code scripts/verify_mixin_order.py} 的行为层就是这么跑的）；</li>
 *   <li>{@link Decision#describe()}：让路 WARN 文案在这里生成，调用方只负责打印——文案要素（类名 + 放弃了什么）
 *       因此可以被门禁直接断言，不会因为调用方改写而失守。</li>
 * </ol>
 *
 * <h2>为什么 MG 目标名在这里另存一份（而不是从 MirageTransformer 读）</h2>
 * {@code MirageTransformer.transform} 的目标类是一串 {@code if ("net/minecraft/XXX".equals(cn))}，
 * 没有任何可供查询的常量表；本轮改动范围不含该文件。于是这里另存一份点分名清单，
 * 由门禁做<strong>双向集合核对</strong>（{@code MirageTransformer} 源码里出现的 7 个名字 ⇔ 本类 {@link #MG_TARGETS} 的键），
 * 任一侧加/删/改而另一侧没跟上 → 门禁判红。改目标类时两边都要动，别只改一边。
 *
 * <h2>为什么 {@code mirage.mixins.json} 不算 mod</h2>
 * 它是 MG 自己的 mixin 配置，其 mixin 目标（{@code CreativeModeInventoryScreen} 等）与 MG 自研 ASM 目标本就重叠；
 * 若把它算进「mod 目标集合」，MG 会自己跟自己冲突、把自研织入让掉，属于逻辑自噬。过滤点见
 * {@link ModMixinTargets#MG_OWN_CONFIG}。
 */
public final class WeavePriority {

    /**
     * MG 自研 ASM transformer 的硬编码目标类（点分）→ 该织入做什么。
     *
     * <p>顺序即日志/冲突列表顺序。值与 {@code MirageTransformer} 里各 {@code transformXxx} 的行为一一对应，
     * 改那边的织入就顺手改这里的说明（门禁只核对类名集合，说明文案靠人）。
     */
    private static final Map<String, String> MG_TARGETS;

    static {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("net.minecraft.SharedConstants", "版本检测入口的版本日志注入");
        m.put("net.minecraft.client.gui.screens.LoadingOverlay", "Forge 式加载遮罩绘制（进度条/事件流/内存条）");
        m.put("net.minecraft.client.gui.screens.TitleScreen", "标题界面 HUD、徽标与按钮点击处理、收尾交叉溶解");
        m.put("com.mojang.blaze3d.platform.Window", "早期窗口交接（glfwCreateWindow 改道）与退出销毁改道");
        m.put("net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen", "创造模式物品栏标签分页（当前实现为空织入）");
        m.put("net.minecraft.server.packs.VanillaPackResourcesBuilder", "mod 资源命名空间挂载（assets/<modid>/ 可见）");
        m.put("net.minecraft.world.item.CreativeModeTab", "无参 builder() 兼容注入（NeoForge 语义补齐）");
        MG_TARGETS = Collections.unmodifiableMap(m);
    }

    /** mod {@code @Mixin} 目标类快照（点分）。默认空集 = 不让路，保持老行为。 */
    private static volatile Set<String> modMixinTargets = Set.of();

    private WeavePriority() {
    }

    /** MG 自研织入的硬编码目标类 → 功能说明（只读）。 */
    public static Map<String, String> mgTargets() {
        return MG_TARGETS;
    }

    /**
     * 灌入本轮收集到的 mod {@code @Mixin} 目标类。
     *
     * <p>传 {@code null} / 空集合等价于「一个都没收集到」⇒ 不产生任何让路（降级到老行为）。
     * 拷贝成不可变集合，避免调用方后续改动影响判定结果。
     */
    public static void setModMixinTargets(Collection<String> targets) {
        if (targets == null || targets.isEmpty()) {
            modMixinTargets = Set.of();
            return;
        }
        Set<String> copy = new LinkedHashSet<>(targets);
        modMixinTargets = Collections.unmodifiableSet(copy);
    }

    /** 当前 mod {@code @Mixin} 目标类快照（只读）。 */
    public static Set<String> modMixinTargets() {
        return modMixinTargets;
    }

    /** 该类是不是 MG 自研织入的目标类。 */
    public static boolean isMgTarget(String className) {
        return className != null && MG_TARGETS.containsKey(className);
    }

    /**
     * 当前快照下「MG 目标 ∩ mod 目标」的类名列表（按 {@link #MG_TARGETS} 顺序，供阶段日志用）。
     */
    public static List<String> conflicts() {
        return conflicts(modMixinTargets);
    }

    /** 纯函数版本：给定 mod 目标集合，返回与 MG 硬编码目标的交集（按 MG 顺序）。 */
    public static List<String> conflicts(Set<String> modTargets) {
        List<String> out = new ArrayList<>();
        if (modTargets == null || modTargets.isEmpty()) {
            return out;
        }
        for (String mg : MG_TARGETS.keySet()) {
            if (modTargets.contains(mg)) {
                out.add(mg);
            }
        }
        return out;
    }

    /** 用当前快照判定该类要不要跑 MG 自研织入。 */
    public static Decision decide(String className) {
        return decide(className, modMixinTargets);
    }

    /**
     * 纯函数判定（不读静态态，可离线单测）。
     *
     * <ul>
     *   <li>非 MG 目标 → 照旧跑 MG 织入（MG 对无关类本来就是原样返回）；</li>
     *   <li>MG 目标、mod 没织入该类 → 照旧跑 MG 织入；</li>
     *   <li>MG 目标、mod 也 {@code @Mixin} 了该类 → <strong>让路</strong>（社区最大化），附带功能说明供 WARN。</li>
     * </ul>
     */
    public static Decision decide(String className, Set<String> modTargets) {
        String purpose = className == null ? null : MG_TARGETS.get(className);
        if (purpose == null) {
            return new Decision(className, true, false, null, "not-mg-target");
        }
        boolean hit = modTargets != null && modTargets.contains(className);
        if (hit) {
            return new Decision(className, false, true, purpose, "mod-mixin-conflict");
        }
        return new Decision(className, true, false, purpose, "mg-target");
    }

    /** 单类的判定结果（不可变）。 */
    public static final class Decision {
        /** 类名（点分）。 */
        public final String className;
        /** 是否执行 MG 自研织入。 */
        public final boolean applyMgWeaving;
        /** 是否因 mod {@code @Mixin} 命中而让路。 */
        public final boolean modMixinConflict;
        /** 若该类是 MG 目标：放弃/执行的是哪一项 MG 功能（非目标类为 null）。 */
        public final String mgPurpose;
        /** 判定依据：{@code not-mg-target} / {@code mg-target} / {@code mod-mixin-conflict}。 */
        public final String reason;

        Decision(String className, boolean applyMgWeaving, boolean modMixinConflict,
                 String mgPurpose, String reason) {
            this.className = className;
            this.applyMgWeaving = applyMgWeaving;
            this.modMixinConflict = modMixinConflict;
            this.mgPurpose = mgPurpose;
            this.reason = reason;
        }

        /**
         * 让路 WARN 文案：必须同时说清「哪个类」和「放弃了什么」。
         *
         * <p>非冲突场景返回一句普通说明（调用方不该拿它当 WARN 打）；冲突场景才是给人看的重点。
         */
        public String describe() {
            if (!modMixinConflict) {
                return "[织入优先级] " + className + " 执行 MG 自研织入（" + mgPurpose + "），无 mod @Mixin 冲突";
            }
            return "[织入优先级] 让路：" + className
                    + " 已被 mod 的 @Mixin 命中（社区最大化裁定：mod 优先），"
                    + "放弃 MG 自研织入「" + mgPurpose + "」——该项 MG 功能本次启动不生效";
        }
    }
}
