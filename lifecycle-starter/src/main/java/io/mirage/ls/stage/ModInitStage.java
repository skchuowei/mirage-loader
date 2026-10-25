package io.mirage.ls.stage;

import io.mirage.api.env.Dist;
import io.mirage.api.event.ModConstructEvent;
import io.mirage.api.event.ModInitEvent;
import io.mirage.api.event.ModPostInitEvent;
import io.mirage.api.event.ModPreInitEvent;
import io.mirage.api.registry.DeferredRegister;
import io.mirage.eventbus.EventBus;
import io.mirage.loader.ModContainer;
import io.mirage.loader.MirageClassLoader;
import io.mirage.loader.MirageDebug;
import io.mirage.ls.LifecycleContext;
import io.mirage.ls.Stage;
import io.mirage.ls.StageOutcome;

import java.util.List;

/**
 * S6 模组初始化（LS-Interfaces.md §7；语义见 MG-Lifecycle.md S6）。
 *
 * <p>phase 2 迁入的 Launcher 逻辑（保序，逐行同日志）：
 * <ol>
 *   <li>entrypoint 实例化循环（端侧过滤 → main/side 入口 → 构造 → register →
 *       Construct/PreInit/Init 事件 → setModCount）；</li>
 *   <li>DeferredRegister flush（仍在 S4 开窗的 unfreeze 窗口内）；</li>
 *   <li>NeoForge 生命周期事件桥接（FMLConstructModEvent → ModConfigEvent.Loading →
 *       InterModEnqueueEvent → FMLCommonSetupEvent → InterModProcessEvent →
 *       RegisterPayloadHandlersEvent）；</li>
 *   <li>ModPostInitEvent 广播；</li>
 *   <li><b>注册表关窗</b>：BlockState 修补 + bug#1 兜底 + 重新冻结
 *       （LS-Interfaces §7 表中列于 S4，实际执行点在 S6 尾——mod 内容注册完成后，
 *       保序迁移见 {@link RegistryWindow} 类注释）。</li>
 * </ol>
 *
 * <p>MG-Lifecycle S6 要求严格串行（构造顺序即加载图顺序）；调度器串行执行天然满足。
 * S2 按 ParseFailPolicy 判定的跳过集合在此生效（{@code ctx.skippedMods()}）。
 */
public final class ModInitStage implements Stage {

    @Override
    public String id() {
        return "S6";
    }

    @Override
    public String name() {
        return "ModInit";
    }

    @Override
    public StageOutcome execute(LifecycleContext ctx) {
        return StageSupport.guard(() -> {
            List<ModContainer> mods = StageSupport.get(ctx, "S1.mods");
            EventBus eventBus = StageSupport.get(ctx, "S2.eventBus");
            MirageClassLoader mcl = StageSupport.get(ctx, "S3.classLoader");
            RegistryWindow window = StageSupport.get(ctx, "S4.registryWindow");
            List<DeferredRegister<?>> pendingRegisters = StageSupport.get(ctx, "S4.pendingRegisters");
            Dist dist = ctx.dist();

            // 实例化每个 Mod 的入口类，按 entrypoints 反射调用 onInitialize()/onInitializeClient()
            // 进度区间 45% ~ 65%，按 mod 数量线性推进
            int modIdx = 0;
            int modTotal = mods.size();
            for (ModContainer mod : mods) {
                // S2 失败策略已判定的跳过集合（skip / confirm+S）——不重复告警
                if (ctx.skippedMods().contains(mod.manifest.id)) {
                    continue;
                }
                // 端侧过滤（P1-7）：客户端运行时跳过 server-only mod；服务端运行时跳过 client-only mod
                if ("server".equals(mod.manifest.side) && dist != Dist.DEDICATED_SERVER) {
                    System.out.println("[Mirage] 跳过 server-only mod: " + mod.manifest.id);
                    continue;
                }
                if ("client".equals(mod.manifest.side) && dist == Dist.DEDICATED_SERVER) {
                    System.out.println("[Mirage] 跳过 client-only mod（服务端运行）: " + mod.manifest.id);
                    continue;
                }
                // main 侧入口列表：fabric mod 用 entrypoints.main；mirage 原生用 mainClass
                List<String> mainClasses = ModEntrypoints.mainClasses(mod);
                // 端侧入口列表（仅 fabric mod 有）：物理客户端跑 entrypoints.client，
                // 专用服务端跑 entrypoints.server（P1-7；Fabric 语义：二者互斥，main 两端都跑）
                List<String> sideClasses = ModEntrypoints.sideClasses(mod, dist);

                if (mainClasses.isEmpty() && sideClasses.isEmpty()) {
                    // 主类定位失败且未被 S2 策略拦下（confirm+Y 放行）——记 WARN，不静默（原则 3）
                    System.out.println("[Mirage] WARN 跳过无可用入口的 mod: " + mod.manifest.id);
                    continue;
                }
                modIdx++;
                int modProgress = modTotal > 0 ? 45 + (int) (20.0 * modIdx / modTotal) : 50;
                MirageDebug.setStage("初始化 mod");
                MirageDebug.setCurrentMod(mod.manifest.id + " v" + mod.manifest.version);
                MirageDebug.setProgress(modProgress);

                // ---- main 侧 ----
                for (String clsName : mainClasses) {
                    System.out.println("[Mirage] 初始化 mod (" + modIdx + "/" + modTotal + "): "
                            + mod.manifest.id + " -> " + clsName);
                    try {
                        Class<?> cls = Class.forName(clsName, true, mcl);
                        Object instance = NeoLifecycle.constructModInstance(cls, mod.fromNeoForge);
                        // P1.5：标记当前活跃 modId，供 ModLoadingContext.registerConfig 推断
                        net.neoforged.fml.ModLoadingContext.setActiveMod(mod.manifest.id);

                        // 把入口类挂到事件总线，使 @SubscribeEvent 方法生效
                        eventBus.register(instance);

                        // 生命周期：construct -> preInit -> onInitialize -> init
                        eventBus.post(new ModConstructEvent(mod.manifest.id));
                        eventBus.post(new ModPreInitEvent(mod.manifest.id));
                        NeoLifecycle.callOnInitialize(instance);
                        eventBus.post(new ModInitEvent(mod.manifest.id));
                        MirageDebug.setModCount(modIdx);
                    } catch (Throwable t) {
                        System.err.println("[Mirage] 初始化 mod " + mod.manifest.id + " (" + clsName + ") 失败: " + t);
                        t.printStackTrace();
                    }
                }

                // ---- 端侧入口实例化（P1-7：客户端 ClientModInitializer.onInitializeClient；
                //      专用服务端 ModInitializer.onInitialize，与 Fabric 官方语义一致）----
                for (String clsName : sideClasses) {
                    if (dist == Dist.DEDICATED_SERVER) {
                        System.out.println("[Mirage] 调用 server entrypoint: " + mod.manifest.id + " -> " + clsName);
                        try {
                            Class<?> cls = Class.forName(clsName, true, mcl);
                            Object instance = cls.getDeclaredConstructor().newInstance();
                            eventBus.register(instance);
                            if (instance instanceof net.fabricmc.api.ModInitializer mi) {
                                mi.onInitialize();
                            } else {
                                java.lang.reflect.Method m = NeoLifecycle.findMethod(cls, "onInitialize");
                                if (m != null) {
                                    m.invoke(instance);
                                } else {
                                    System.out.println("[Mirage]   ! " + clsName + " 没有 onInitialize()，跳过");
                                }
                            }
                        } catch (Throwable t) {
                            System.err.println("[Mirage] 调用 server entrypoint " + clsName + " 失败: " + t);
                            t.printStackTrace();
                        }
                        continue;
                    }
                    System.out.println("[Mirage] 调用 client entrypoint: " + mod.manifest.id + " -> " + clsName);
                    try {
                        Class<?> cls = Class.forName(clsName, true, mcl);
                        Object instance = cls.getDeclaredConstructor().newInstance();
                        eventBus.register(instance);
                        if (instance instanceof net.fabricmc.api.ClientModInitializer cmi) {
                            cmi.onInitializeClient();
                        } else {
                            java.lang.reflect.Method m = NeoLifecycle.findMethod(cls, "onInitializeClient");
                            if (m != null) {
                                m.invoke(instance);
                            } else {
                                System.out.println("[Mirage]   ! " + clsName + " 没有 onInitializeClient()，跳过");
                            }
                        }
                    } catch (Throwable t) {
                        System.err.println("[Mirage] 调用 client entrypoint " + clsName + " 失败: " + t);
                        t.printStackTrace();
                    }
                }
            }
            MirageDebug.setCurrentMod("");

            // DeferredRegister 闭环：所有 mod 声明完条目后统一 flush（仍在 unfreeze 窗口内）
            System.out.println("[Mirage] flush " + pendingRegisters.size() + " 个 DeferredRegister ...");
            for (DeferredRegister<?> dr : pendingRegisters) {
                try {
                    dr.flush();
                } catch (Throwable t) {
                    System.err.println("[Mirage] DeferredRegister flush 失败: " + t);
                }
            }

            // NeoForge 生命周期事件桥接：mod 已全部构造、注册闭环完成，
            // 向 mod bus post 构造/配置/IMC/通用 setup 事件，
            // 让 NeoForge mod 的 @SubscribeEvent 方法真正触发。
            // P1.5 新增：ModConfigEvent.Loading（配置加载）、InterModEnqueueEvent（IMC 入队）
            // 在 FMLConstructModEvent 之后、FMLCommonSetupEvent 之前；InterModProcessEvent 在其后。
            NeoLifecycle.postNeoLifecycle("net.neoforged.fml.event.lifecycle.FMLConstructModEvent", mcl);
            NeoLifecycle.postNeoLifecycle("net.neoforged.fml.event.lifecycle.ModConfigEvent$Loading", mcl);
            NeoLifecycle.postNeoLifecycle("net.neoforged.fml.event.lifecycle.InterModEnqueueEvent", mcl);
            NeoLifecycle.postNeoLifecycle("net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent", mcl);
            NeoLifecycle.postNeoLifecycle("net.neoforged.fml.event.lifecycle.InterModProcessEvent", mcl);
            // P1.6 新增：网络 payload 注册（SimpleChannel / PayloadRegistrar 登记本地通路 handler）
            NeoLifecycle.postNeoLifecycle("net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent", mcl);

            // 所有 mod 完成 onInitialize 后，再广播一轮 ModPostInitEvent
            // （语义：此时可以安全读取其他 mod 注册的内容）
            for (ModContainer mod : mods) {
                if (mod.manifest.mainClass == null || mod.manifest.mainClass.isEmpty()) continue;
                eventBus.post(new ModPostInitEvent(mod.manifest.id));
            }

            // ---- 注册表关窗：BlockState 修补 + 重新冻结（保序：mod 初始化之后）----
            window.patchBlockStates();
            window.refreeze();
        });
    }
}
