package io.mirage.ls.stage;

import io.mirage.api.registry.DeferredRegister;
import io.mirage.loader.MirageDebug;
import io.mirage.ls.LifecycleContext;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * S4 注册表合并窗口（保序迁移自拆分前 Launcher S4 的内联反射逻辑）。
 *
 * <p>窗口生命周期：{@link #open} 开窗（Bootstrap → 解冻全量 MappedRegistry →
 * 装配 DeferredRegister nativeBackend）→ S6 mod 初始化期经 backend 注册 →
 * S6 尾部 {@link #patchBlockStates} + {@link #refreeze} 关窗。
 *
 * <p>为什么关窗点在 S6 尾而不是 S4 内：mod 内容在 S6 构造期才注册进窗口，
 * 拆分前 Launcher 同样把 flush / BlockState 修补 / 重新冻结排在 mod 初始化之后
 * （保序迁移，不改变注册表可见性时序）。
 */
public final class RegistryWindow {

    private final ClassLoader mcl;
    private final Class<?> mappedReg;
    private final Field frozenField;
    private final Field unregField;
    private final Field allTagsField;
    private final Field byValueField;
    private final Method tagSetUnbound;
    private final Method regRegisterMethod;
    private final Method idFactory;    public final Object blockReg;
    public final Object itemReg;
    public final Object tabReg;
    private final Class<?> builtInReg;

    private RegistryWindow(ClassLoader mcl, Class<?> mappedReg, Field frozenField, Field unregField,
                           Field allTagsField, Field byValueField, Method tagSetUnbound,
                           Method regRegisterMethod, Method idFactory,
                           Class<?> builtInReg, Object blockReg, Object itemReg, Object tabReg) {
        this.mcl = mcl;
        this.mappedReg = mappedReg;
        this.frozenField = frozenField;
        this.unregField = unregField;
        this.allTagsField = allTagsField;
        this.byValueField = byValueField;
        this.tagSetUnbound = tagSetUnbound;
        this.regRegisterMethod = regRegisterMethod;
        this.idFactory = idFactory;
        this.builtInReg = builtInReg;
        this.blockReg = blockReg;
        this.itemReg = itemReg;
        this.tabReg = tabReg;
    }

    /**
     * 开窗：Bootstrap → 解冻全部 MappedRegistry → 装配 DeferredRegister hook + nativeBackend。
     * 全程与拆分前 Launcher 逐行同序同日志。
     */
    public static RegistryWindow open(LifecycleContext ctx) throws Exception {
        ClassLoader mcl = StageSupport.get(ctx, "S3.classLoader");

        // ---- 保序：拆分前 Launcher 在 Bootstrap 前先运行 SharedConstants.tryDetectVersion() ----
        // DataFixers.<clinit> 经 SharedConstants.getCurrentVersion() 读游戏版本，
        // 缺此步 ISE("Game version not set")（2026-10-25 崩溃回归：LS 迁移漏迁此调用）。
        System.out.println("[Mirage] 运行 SharedConstants.tryDetectVersion() ...");
        io.mirage.loader.MirageDebug.setStage("检测 MC 版本");
        io.mirage.loader.MirageDebug.setProgress(22);
        Class<?> sc = Class.forName("net.minecraft.SharedConstants", true, mcl);
        sc.getMethod("tryDetectVersion").invoke(null);

        // ---- 单线程：mod 全部初始化完成后再启动 Minecraft 主类 ----
        System.out.println("[Mirage] 运行 Bootstrap.bootStrap() ...");
        io.mirage.loader.MirageDebug.setStage("初始化注册表");
        io.mirage.loader.MirageDebug.setProgress(32);
        Class<?> bootstrap = Class.forName("net.minecraft.server.Bootstrap", true, mcl);
        bootstrap.getMethod("bootStrap").invoke(null);

        // M5：Bootstrap 后 registry 已 freeze，反射解冻所有 MappedRegistry，注册 Mod 内容，再冻结。
        // 之前只解冻 BLOCK/ITEM/CREATIVE_MODE_TAB，导致 entity_type/enchantment/biome 等
        // DeferredRegister 无法注册（Registry is frozen / 跳过未知注册表类型）。
        System.out.println("[Mirage] 解冻注册表以注册 mod 内容 ...");
        io.mirage.loader.MirageDebug.setStage("解冻注册表");
        io.mirage.loader.MirageDebug.setProgress(42);
        Class<?> builtInReg = Class.forName("net.minecraft.core.registries.BuiltInRegistries", true, mcl);
        Class<?> mappedReg = Class.forName("net.minecraft.core.MappedRegistry", true, mcl);
        Field frozenField = mappedReg.getDeclaredField("frozen");
        frozenField.setAccessible(true);
        Field unregField = mappedReg.getDeclaredField("unregisteredIntrusiveHolders");
        unregField.setAccessible(true);
        Field allTagsField = mappedReg.getDeclaredField("allTags");
        allTagsField.setAccessible(true);
        Field byValueField = mappedReg.getDeclaredField("byValue");
        byValueField.setAccessible(true);
        Class<?> tagSetClass = Class.forName("net.minecraft.core.MappedRegistry$TagSet", true, mcl);
        Method unboundMethod = tagSetClass.getMethod("unbound");
        unboundMethod.setAccessible(true);

        // 反射解冻单个 MappedRegistry（清空 intrusive holders + tags，置 frozen=false）
        Consumer<Object> unfreeze = reg -> {
            try {
                frozenField.setBoolean(reg, false);
                unregField.set(reg, new HashMap<>());
                allTagsField.set(reg, unboundMethod.invoke(null));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        };

        // 遍历 registry-of-registries，对所有 MappedRegistry 实例解冻（vanilla 时序：先全解冻再注册后全冻结）
        Object registryOfRegistries = builtInReg.getField("REGISTRY").get(null);
        List<Object> allMappedRegistries = new ArrayList<>();
        for (Object r : (Iterable<?>) registryOfRegistries) {
            if (mappedReg.isInstance(r)) {
                allMappedRegistries.add(r);
                unfreeze.accept(r);
            }
        }
        System.out.println("[Mirage] 已解冻 " + allMappedRegistries.size() + " 个 MappedRegistry");

        // 取常用的三个，给下面 special-case 用（语义不变）
        Object blockReg = builtInReg.getField("BLOCK").get(null);
        Object itemReg = builtInReg.getField("ITEM").get(null);
        Object tabReg = builtInReg.getField("CREATIVE_MODE_TAB").get(null);

        // DeferredRegister nativeBackend 所需的两个反射句柄（先取后装，保证窗口构造即可用）
        Class<?> registryCls = Class.forName("net.minecraft.core.Registry", true, mcl);
        Class<?> identifierCls = Class.forName("net.minecraft.resources.Identifier", true, mcl);
        Method regRegisterMethod = registryCls.getMethod("register", registryCls, identifierCls, Object.class);
        Method idFactory = identifierCls.getMethod("fromNamespaceAndPath", String.class, String.class);

        RegistryWindow window = new RegistryWindow(mcl, mappedReg, frozenField, unregField, allTagsField,
                byValueField, unboundMethod, regRegisterMethod, idFactory, builtInReg, blockReg, itemReg, tabReg);

        // ---- DeferredRegister 闭环 ----
        // 时机：注册表已 unfreeze、mod 尚未初始化；flush() 仍发生在 unfreeze 窗口内。
        List<DeferredRegister<?>> pendingRegisters = new ArrayList<>();
        DeferredRegister.setHookListener(pendingRegisters::add);
        DeferredRegister.setNativeBackend(window::nativeRegister);
        ctx.properties().put("S4.pendingRegisters", pendingRegisters);

        return window;
    }

    /**
     * DeferredRegister nativeBackend 主体（保序迁移自拆分前 Launcher 的匿名闭包）：
     * 查注册表 → 逐条目 register → CreativeModeTab intrusive holder / BlockItem 绑定 /
     * bug#1 精准 remove。
     */
    private void nativeRegister(DeferredRegister<?> dr, Map<String, Supplier<?>> pending) {
        // 优先用 NeoForge 兼容层显式带入的注册表路径（元素类型简单名与路径不一定一致，
        // 如 point_of_interest_type → PoiType）；无则退化为元素类型简单名小写。
        String path = dr.registryPath();
        Object reg = lookupRegistry(path);
        if (reg == null) {
            // 路径确实不存在 / 本加载器未支持（如纯 codec 注册表）→ 告警跳过，不静默错注册。
            System.out.println("[Mirage] DeferredRegister: 跳过未知注册表类型 "
                    + path + " (mod=" + dr.modId()
                    + ", entries=" + pending.size() + ")");
            return;
        }
        for (Map.Entry<String, Supplier<?>> e : pending.entrySet()) {
            try {
                Object value = e.getValue().get();
                Object id = idFactory.invoke(null, dr.modId(), e.getKey());
                // CreativeModeTab.Builder.build() 不自动 setId，没有 intrusive holder，
                // MappedRegistry.register 会断言失败。手动创建 intrusive holder 塞进去。
                if ("creativemodetab".equals(path)) {
                    try {
                        Class<?> holderRefCls = Class.forName("net.minecraft.core.Holder$Reference", true, mcl);
                        Method createIntrusive = holderRefCls
                                .getMethod("createIntrusive", Class.forName("net.minecraft.core.HolderOwner", true, mcl), Object.class);
                        Object ref = createIntrusive.invoke(null, reg, value);
                        ((Map<Object, Object>) unregField.get(reg)).put(value, ref);
                    } catch (Exception ihEx) {
                        System.err.println("[Mirage] createIntrusive 失败 " + ihEx);
                    }
                }
                regRegisterMethod.invoke(null, reg, id, value);
                // BlockItem 构造不自动绑 block.item，手动反射绑定，否则 block.asItem()=AIR
                // 导致 creative tab 构建时 new ItemStack(AIR) count=0 崩溃
                try {
                    if ("item".equals(path)) {
                        Class<?> blockItemCls = Class.forName("net.minecraft.world.item.BlockItem", true, mcl);
                        if (blockItemCls.isInstance(value)) {
                            Field bf = blockItemCls.getDeclaredField("block");
                            bf.setAccessible(true);
                            Object b = bf.get(value);
                            Class<?> blockCls = Class.forName("net.minecraft.world.level.block.Block", true, mcl);
                            Field iff = blockCls.getDeclaredField("item");
                            iff.setAccessible(true);
                            iff.set(b, value);
                        }
                    }
                } catch (Exception bindEx) {
                    System.err.println("[Mirage] 绑定 block.item 失败: " + bindEx);
                }
                // bug #1 真修复：Properties.setId() 在 Block/Item 构造时创建 intrusive
                // Holder.Reference 塞进 unregisteredIntrusiveHolders。Registry.register
                // 在 unfreeze 的 registry 上不总是把它移走，freeze() 会报
                // "Some intrusive holders were not registered"。这里精准 remove(value)。
                try {
                    ((Map<?, ?>) unregField.get(reg)).remove(value);
                } catch (Exception ignore) {}
                System.out.println("[Mirage] DeferredRegister: 注册 " + path
                        + " " + dr.modId() + ":" + e.getKey());
            } catch (Exception ex) {
                System.err.println("[Mirage] DeferredRegister 注册失败 "
                        + dr.modId() + ":" + e.getKey() + " -> " + ex);
                ex.printStackTrace();
            }
        }
    }

    /** 注册表路径 → BuiltInRegistries 字段对象；路径不存在返回 null（调用方告警跳过）。 */
    private Object lookupRegistry(String path) {
        if (path == null || path.isEmpty()) return null;
        try {
            java.lang.reflect.Field f =
                    builtInReg.getField(path.toUpperCase(Locale.ENGLISH));
            return f.get(null);
        } catch (NoSuchFieldException nsfe) {
            // 路径确实不存在（mod 拼错 / 该注册表本加载器不支持）→ 返回 null，调用方告警
            return null;
        } catch (Exception e) {
            System.err.println("[Mirage] 查找注册表 " + path + " 失败: " + e);
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    Map<Object, Object> unregisteredHolders(Object reg) throws Exception {
        return (Map<Object, Object>) unregField.get(reg);
    }

    /**
     * 补注册 BlockState 到 Block.BLOCK_STATE_REGISTRY（Bootstrap 后新加的 block 不会自动进 IdMapper）。
     * 保序迁移自拆分前 Launcher，在 S6 mod 全部初始化完成后执行。
     */
    public void patchBlockStates() throws Exception {
        System.out.println("[Mirage] 修补 mod 方块的 BlockState 注册表 ...");
        io.mirage.loader.MirageDebug.setStage("修补 BlockState 注册表");
        io.mirage.loader.MirageDebug.setProgress(72);
        Class<?> blockCls = Class.forName("net.minecraft.world.level.block.Block", true, mcl);
        Object bsr = blockCls.getField("BLOCK_STATE_REGISTRY").get(null);
        Method getIdMethod = bsr.getClass().getMethod("getId", Object.class);
        Method addMappingMethod = bsr.getClass().getMethod("addMapping", Object.class, int.class);
        Field idToTField = bsr.getClass().getDeclaredField("idToT");
        idToTField.setAccessible(true);
        List<?> idToT = (List<?>) idToTField.get(bsr);

        // 遍历 BuiltInRegistries.BLOCK 所有 entry
        Map<?, ?> byValue = (Map<?, ?>) byValueField.get(blockReg);
        int patched = 0;
        Method holderGetMethod;
        try {
            holderGetMethod = Class.forName("net.minecraft.core.Holder$Reference", true, mcl).getMethod("value");
        } catch (Exception e) {
            // fallback: get()
            holderGetMethod = Class.forName("net.minecraft.core.Holder$Reference", true, mcl).getMethod("get");
        }
        for (Object holder : byValue.values()) {
            try {
                Object block = holderGetMethod.invoke(holder);
                Method defaultState = blockCls.getMethod("defaultBlockState");
                Object state = defaultState.invoke(block);
                int existing = (int) getIdMethod.invoke(bsr, state);
                if (existing == -1) {
                    int newId = idToT.size();
                    addMappingMethod.invoke(bsr, state, newId);
                    patched++;
                    System.out.println("[Mirage]   + 修补 BlockState id=" + newId + " -> " + state);
                }
                // 触发 BlockState cache 初始化（occlusionShapesByFace 等）
                try {
                    Method initCache = state.getClass().getMethod("initCache");
                    initCache.invoke(state);
                } catch (Exception ignore) {}
            } catch (Exception e) {
                // 跳过不能访问的
            }
        }
        System.out.println("[Mirage] 已修补 " + patched + " 个 BlockState");
    }

    /**
     * 关窗：bug#1 兜底检查 + 重新冻结 BLOCK/ITEM/CREATIVE_MODE_TAB。
     * 保序迁移自拆分前 Launcher（S6 mod 初始化 + flush 之后）。
     */
    public void refreeze() throws Exception {
        System.out.println("[Mirage] 重新冻结注册表 ...");
        io.mirage.loader.MirageDebug.setStage("冻结注册表");
        io.mirage.loader.MirageDebug.setProgress(85);
        // bug #1 兜底：native backend 已在注册时精准 remove(value)，正常情况下这里应为空。
        // 若仍有残留（mod 自己 new Block(props.setId) 但没调 Registry.register），
        // 打印出来并 clear——不静默吞掉，日志能看到哪些 mod 漏注册了。
        try {
            for (Object regObj : new Object[]{blockReg, itemReg, tabReg}) {
                Map<Object, Object> unreg = unregisteredHolders(regObj);
                if (unreg != null && !unreg.isEmpty()) {
                    System.out.println("[Mirage] bug#1 兜底: " + unreg.size()
                            + " 个未注册 intrusive holder 残留，将 clear: "
                            + unreg.keySet().stream().map(k -> k.getClass().getSimpleName()).toList());
                    unreg.clear();
                }
            }
        } catch (Exception e) {
            System.err.println("[Mirage] unregisteredIntrusiveHolders 兜底检查失败: " + e);
        }
        blockReg.getClass().getMethod("freeze").invoke(blockReg);
        itemReg.getClass().getMethod("freeze").invoke(itemReg);
        tabReg.getClass().getMethod("freeze").invoke(tabReg);
    }

    /** 供 S2 判定/调试：当前窗口类加载器。 */
    public ClassLoader classLoader() {
        return mcl;
    }
}
