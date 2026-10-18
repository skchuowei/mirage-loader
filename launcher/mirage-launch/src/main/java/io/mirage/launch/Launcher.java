package io.mirage.launch;

import io.mirage.api.Mirage;
import io.mirage.api.env.Dist;
import io.mirage.api.event.ClientStartingEvent;
import io.mirage.api.event.ModConstructEvent;
import io.mirage.api.event.ModInitEvent;
import io.mirage.api.event.ModPostInitEvent;
import io.mirage.api.event.ModPreInitEvent;
import io.mirage.api.registry.DeferredRegister;
import io.mirage.eventbus.EventBus;
import io.mirage.eventbus.EventBusHandleImpl;
import io.mirage.loader.Loader;
import io.mirage.loader.ModContainer;
import io.mirage.loader.MirageClassLoader;
import io.mirage.loader.runtime.MirageProviderImpl;

import java.lang.reflect.Method;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Mirage Loader 入口点。
 */
public final class Launcher {

    public static final String VERSION = "0.4.0";
    public static final String TARGET_MC_MAIN = "net.minecraft.client.main.Main";

    public static void main(String[] args) {
        banner();
        // NeoForge 式早期窗口：MC 主窗口之前显示调试信息
        LoadingScreen.open();
        LoadingScreen.setStage("正在扫描 mods 目录...");
        LoadingScreen.setProgress(3);
        io.mirage.loader.MirageDebug.setStage("扫描 mods 目录");
        io.mirage.loader.MirageDebug.setProgress(3);

        Path gameDir = parseGameDir(args);
        System.out.println("[Mirage] 游戏目录 = " + gameDir);

        // MID 缓存开关（调试用）：--mid-cache-force-regen 忽略缓存重转；--mid-cache-verbose 打印命中
        boolean forceRegen = hasFlag(args, "--mid-cache-force-regen");
        boolean midVerbose = hasFlag(args, "--mid-cache-verbose");
        io.mirage.loader.MidCache midCache = new io.mirage.loader.MidCache(gameDir, "26.2");

        io.mirage.loader.MirageDebug.setStage("解析 mod 清单");
        io.mirage.loader.MirageDebug.setProgress(8);
        LoadingScreen.setStage("解析 mod 清单...");
        LoadingScreen.setProgress(8);

        List<ModContainer> mods = new Loader().discoverMods(gameDir, midCache, forceRegen, midVerbose);
        io.mirage.loader.MirageDebug.setModTotal(mods.size());
        LoadingScreen.setModList(mods.stream().map(m -> m.manifest.id + " v" + m.manifest.version).toList());
        System.out.println("[Mirage] 发现 " + mods.size() + " 个 mod");
        for (ModContainer m : mods) {
            io.mirage.loader.MirageDebug.modNamespaces.add(m.manifest.id);
        }

        // ---- 初始化公共 API 门面（Mirage.* 从此可用）----
        EventBus eventBus = new EventBus();
        EventBusHandleImpl busHandle = new EventBusHandleImpl(eventBus);
        MirageProviderImpl provider = new MirageProviderImpl(
                Dist.CLIENT, gameDir, mods, busHandle, VERSION);
        Mirage.bootstrap(provider);
        System.out.println("[Mirage] 公共 API 门面已就绪 (loader v" + VERSION
                + ", mods=" + mods.size() + ", dist=" + Dist.CLIENT + ")");

        try {
            io.mirage.loader.MirageDebug.setStage("创建类加载器");
            io.mirage.loader.MirageDebug.setProgress(14);
            LoadingScreen.setStage("创建类加载器...");
            LoadingScreen.setProgress(14);

            String cp = System.getProperty("java.class.path");
            String[] entries = cp.split(java.io.File.pathSeparator);
            List<URL> urlList = new ArrayList<>();
            for (String e : entries) {
                if (!e.isEmpty()) urlList.add(new java.io.File(e).toURI().toURL());
            }
            URL[] urls = urlList.toArray(new URL[0]);
            System.out.println("[Mirage] 创建 MirageClassLoader，" + urls.length + " 个 URL");
            MirageClassLoader mcl = new MirageClassLoader(urls, ClassLoader.getSystemClassLoader().getParent());

            // 把每个 Mod jar 加入 classloader
            for (ModContainer mod : mods) {
                mcl.addJar(mod.jarFile.toFile());
                System.out.println("[Mirage] 添加 mod jar: " + mod.jarFile.getFileName());
            }

            // ---- 初始化 SpongePowered Mixin 管线 ----
            java.util.List<String> mixinConfigs = new java.util.ArrayList<>();
            for (ModContainer mod : mods) {
                try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(mod.jarFile.toFile())) {
                    java.util.Enumeration<? extends java.util.zip.ZipEntry> zEntries = zf.entries();
                    while (zEntries.hasMoreElements()) {
                        java.util.zip.ZipEntry e = zEntries.nextElement();
                        String name = e.getName();
                        if (name.endsWith(".mixin.json") && !name.contains("/")) {
                            String configName = name.substring(0, name.length() - ".mixin.json".length());
                            mixinConfigs.add(configName);
                            System.out.println("[Mirage-Mixin] 发现 mixin config: " + configName + " (from " + mod.jarFile.getFileName() + ")");
                        }
                    }
                } catch (Exception e) {
                    System.err.println("[Mirage-Mixin] 扫描 mod jar 失败: " + e);
                }
            }
            // 加上 loader 自身的 mixin config
            mixinConfigs.add("mirage.mixins.json");
            io.mirage.mixin.MirageMixinBootstrap.init(mcl, mixinConfigs);
            System.out.println("[Mirage-Mixin] Mixin 管线初始化完成, " + mixinConfigs.size() + " 个 config");

            // 同时把 mod jar 加入系统 classloader（AppClassLoader），
            // 因为 MC 类可能被 AppClassLoader 加载，资源系统用它的 getResource()
            try {
                ClassLoader appCl = ClassLoader.getSystemClassLoader();
                Class<?> ucpClass = Class.forName("jdk.internal.loader.ClassLoaders$AppClassLoader");
                java.lang.reflect.Field ucpField = ucpClass.getDeclaredField("ucp");
                ucpField.setAccessible(true);
                Object ucp = ucpField.get(appCl);
                java.lang.reflect.Method addURL = ucp.getClass().getMethod("addURL", java.net.URL.class);
                for (ModContainer mod : mods) {
                    addURL.invoke(ucp, mod.jarFile.toUri().toURL());
                    System.out.println("[Mirage] mod jar 已加入系统 classpath: " + mod.jarFile.getFileName());
                }
            } catch (Exception e) {
                System.err.println("[Mirage] 加入系统 classpath 失败: " + e);
            }

            System.out.println("[Mirage] 运行 SharedConstants.tryDetectVersion() ...");
            io.mirage.loader.MirageDebug.setStage("检测 MC 版本");
            io.mirage.loader.MirageDebug.setProgress(22);
            LoadingScreen.setStage("检测 MC 版本...");
            LoadingScreen.setProgress(22);
            Class<?> sc = Class.forName("net.minecraft.SharedConstants", true, mcl);
            sc.getMethod("tryDetectVersion").invoke(null);

            System.out.println("[Mirage] 运行 Bootstrap.bootStrap() ...");
            io.mirage.loader.MirageDebug.setStage("初始化注册表");
            io.mirage.loader.MirageDebug.setProgress(32);
            LoadingScreen.setStage("初始化注册表...");
            LoadingScreen.setProgress(32);
            Class<?> bootstrap = Class.forName("net.minecraft.server.Bootstrap", true, mcl);
            bootstrap.getMethod("bootStrap").invoke(null);

            // M5：Bootstrap 后 registry 已 freeze，反射 unfreeze BLOCK + ITEM + CREATIVE_MODE_TAB，注册 Mod 内容，再 freeze
            System.out.println("[Mirage] 解冻注册表以注册 mod 内容 ...");
            io.mirage.loader.MirageDebug.setStage("解冻注册表");
            io.mirage.loader.MirageDebug.setProgress(42);
            LoadingScreen.setStage("解冻注册表...");
            LoadingScreen.setProgress(42);
            Class<?> builtInReg = Class.forName("net.minecraft.core.registries.BuiltInRegistries", true, mcl);
            Class<?> mappedReg = Class.forName("net.minecraft.core.MappedRegistry", true, mcl);
            java.lang.reflect.Field frozenField = mappedReg.getDeclaredField("frozen");
            frozenField.setAccessible(true);
            java.lang.reflect.Field unregField = mappedReg.getDeclaredField("unregisteredIntrusiveHolders");
            unregField.setAccessible(true);
            java.lang.reflect.Field allTagsField = mappedReg.getDeclaredField("allTags");
            allTagsField.setAccessible(true);
            Class<?> tagSetClass = Class.forName("net.minecraft.core.MappedRegistry$TagSet", true, mcl);
            java.lang.reflect.Method unboundMethod = tagSetClass.getMethod("unbound");
            unboundMethod.setAccessible(true);

            java.util.function.Consumer<Object> unfreeze = reg -> {
                try {
                    frozenField.setBoolean(reg, false);
                    unregField.set(reg, new java.util.HashMap<>());
                    allTagsField.set(reg, unboundMethod.invoke(null));
                } catch (Exception e) { throw new RuntimeException(e); }
            };

            Object blockReg = builtInReg.getField("BLOCK").get(null);
            unfreeze.accept(blockReg);
            Object itemReg = builtInReg.getField("ITEM").get(null);
            unfreeze.accept(itemReg);
            // 创造模式标签页注册表（mod 注册自定义 tab 需要）
            Object tabReg = builtInReg.getField("CREATIVE_MODE_TAB").get(null);
            unfreeze.accept(tabReg);

            // ---- DeferredRegister 闭环 ----
            // 时机：注册表已 unfreeze、mod 尚未初始化；flush() 仍发生在 unfreeze 窗口内。
            List<DeferredRegister<?>> pendingRegisters = new ArrayList<>();
            DeferredRegister.setHookListener(pendingRegisters::add);
            Class<?> registryCls = Class.forName("net.minecraft.core.Registry", true, mcl);
            Class<?> identifierCls = Class.forName("net.minecraft.resources.Identifier", true, mcl);
            java.lang.reflect.Method regRegisterMethod =
                    registryCls.getMethod("register", registryCls, identifierCls, Object.class);
            java.lang.reflect.Method idFactory =
                    identifierCls.getMethod("fromNamespaceAndPath", String.class, String.class);
            DeferredRegister.setNativeBackend((dr, pending) -> {
                // type() 是 Item.class / Block.class ...，取简单名小写映射到 BuiltInRegistries 字段
                String regName = dr.type().getSimpleName().toLowerCase();
                Object reg = switch (regName) {
                    case "item" -> itemReg;
                    case "block" -> blockReg;
                    case "creativemodetab" -> tabReg;
                    default -> null;
                };
                if (reg == null) {
                    // TODO: creativemodetab / enchantment / entity_type 等注册表暂未映射。
                    //       在拿不到 BuiltInRegistries 确切字段名证据前不瞎猜，跳过并打印。
                    System.out.println("[Mirage] DeferredRegister: 跳过未知注册表类型 "
                            + dr.type().getName() + " (mod=" + dr.modId()
                            + ", entries=" + pending.size() + ")");
                    return;
                }
                for (Map.Entry<String, Supplier<?>> e : pending.entrySet()) {
                    try {
                        Object value = e.getValue().get();
                        Object id = idFactory.invoke(null, dr.modId(), e.getKey());
                        regRegisterMethod.invoke(null, reg, id, value);
                        // bug #1 真修复：Properties.setId() 在 Block/Item 构造时创建 intrusive
                        // Holder.Reference 塞进 unregisteredIntrusiveHolders。Registry.register
                        // 在 unfreeze 的 registry 上不总是把它移走，freeze() 会报
                        // "Some intrusive holders were not registered"。这里精准 remove(value)。
                        try {
                            ((java.util.Map<?, ?>) unregField.get(reg)).remove(value);
                        } catch (Exception ignore) {}
                        System.out.println("[Mirage] DeferredRegister: 注册 " + regName
                                + " " + dr.modId() + ":" + e.getKey());
                    } catch (Exception ex) {
                        System.err.println("[Mirage] DeferredRegister 注册失败 "
                                + dr.modId() + ":" + e.getKey() + " -> " + ex);
                    }
                }
            });

            // 实例化每个 Mod 的入口类，按 entrypoints 反射调用 onInitialize()/onInitializeClient()
            // 进度区间 45% ~ 65%，按 mod 数量线性推进
            int modIdx = 0;
            int modTotal = mods.size();
            for (ModContainer mod : mods) {
                // 端侧过滤：客户端运行时跳过 server-only mod
                if ("server".equals(mod.manifest.side)) {
                    System.out.println("[Mirage] 跳过 server-only mod: " + mod.manifest.id);
                    continue;
                }
                // main 侧入口列表：fabric mod 用 entrypoints.main；mirage 原生用 mainClass
                List<String> mainClasses = new ArrayList<>();
                if (mod.fabricEntrypoints != null && mod.fabricEntrypoints.containsKey("main")) {
                    mainClasses.addAll(mod.fabricEntrypoints.get("main"));
                }
                if (mainClasses.isEmpty() && mod.manifest.mainClass != null && !mod.manifest.mainClass.isEmpty()) {
                    mainClasses.add(mod.manifest.mainClass);
                }
                // client 侧入口列表（仅 fabric mod 有；当前 dist=CLIENT 才执行）
                List<String> clientClasses = mod.fabricEntrypoints != null
                        ? mod.fabricEntrypoints.getOrDefault("client", List.of())
                        : List.of();

                if (mainClasses.isEmpty() && clientClasses.isEmpty()) {
                    continue;
                }
                modIdx++;
                int modProgress = modTotal > 0 ? 45 + (int) (20.0 * modIdx / modTotal) : 50;
                io.mirage.loader.MirageDebug.setStage("初始化 mod");
                io.mirage.loader.MirageDebug.setCurrentMod(mod.manifest.id + " v" + mod.manifest.version);
                io.mirage.loader.MirageDebug.setProgress(modProgress);
                LoadingScreen.setStage("初始化 mod " + modIdx + "/" + modTotal + ": " + mod.manifest.id);
                LoadingScreen.setProgress(modProgress);

                // ---- main 侧 ----
                for (String clsName : mainClasses) {
                    System.out.println("[Mirage] 初始化 mod (" + modIdx + "/" + modTotal + "): "
                            + mod.manifest.id + " -> " + clsName);
                    try {
                        Class<?> cls = Class.forName(clsName, true, mcl);
                        Object instance = constructModInstance(cls, mod.fromNeoForge);

                        // 把入口类挂到事件总线，使 @SubscribeEvent 方法生效
                        eventBus.register(instance);

                        // 生命周期：construct -> preInit -> onInitialize -> init
                        eventBus.post(new ModConstructEvent(mod.manifest.id));
                        eventBus.post(new ModPreInitEvent(mod.manifest.id));
                        callOnInitialize(instance);
                        eventBus.post(new ModInitEvent(mod.manifest.id));
                        io.mirage.loader.MirageDebug.setModCount(modIdx);
                    } catch (Throwable t) {
                        System.err.println("[Mirage] 初始化 mod " + mod.manifest.id + " (" + clsName + ") 失败: " + t);
                        t.printStackTrace();
                    }
                }

                // ---- client 侧（当前为物理客户端）----
                for (String clsName : clientClasses) {
                    System.out.println("[Mirage] 调用 client entrypoint: " + mod.manifest.id + " -> " + clsName);
                    try {
                        Class<?> cls = Class.forName(clsName, true, mcl);
                        Object instance = cls.getDeclaredConstructor().newInstance();
                        eventBus.register(instance);
                        if (instance instanceof net.fabricmc.api.ClientModInitializer cmi) {
                            cmi.onInitializeClient();
                        } else {
                            Method m = findMethod(cls, "onInitializeClient");
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
            io.mirage.loader.MirageDebug.setCurrentMod("");

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
            // 向 mod bus post FMLConstructModEvent / FMLCommonSetupEvent，
            // 让 NeoForge mod 的 @SubscribeEvent 方法真正触发。
            postNeoLifecycle("net.neoforged.fml.event.lifecycle.FMLConstructModEvent", mcl);
            postNeoLifecycle("net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent", mcl);

            // 所有 mod 完成 onInitialize 后，再广播一轮 ModPostInitEvent
            // （语义：此时可以安全读取其他 mod 注册的内容）
            for (ModContainer mod : mods) {
                if (mod.manifest.mainClass == null || mod.manifest.mainClass.isEmpty()) continue;
                eventBus.post(new ModPostInitEvent(mod.manifest.id));
            }

            // 补注册 BlockState 到 Block.BLOCK_STATE_REGISTRY（Bootstrap 后新加的 block 不会自动进 IdMapper）
            System.out.println("[Mirage] 修补 mod 方块的 BlockState 注册表 ...");
            io.mirage.loader.MirageDebug.setStage("修补 BlockState 注册表");
            io.mirage.loader.MirageDebug.setProgress(72);
            LoadingScreen.setStage("修补 BlockState 注册表...");
            LoadingScreen.setProgress(72);
            Class<?> blockCls = Class.forName("net.minecraft.world.level.block.Block", true, mcl);
            Object bsr = blockCls.getField("BLOCK_STATE_REGISTRY").get(null);
            java.lang.reflect.Method getIdMethod = bsr.getClass().getMethod("getId", Object.class);
            java.lang.reflect.Method addMappingMethod = bsr.getClass().getMethod("addMapping", Object.class, int.class);
            java.lang.reflect.Field idToTField = bsr.getClass().getDeclaredField("idToT");
            idToTField.setAccessible(true);
            java.util.List<?> idToT = (java.util.List<?>) idToTField.get(bsr);

            // 遍历 BuiltInRegistries.BLOCK 所有 entry
            java.lang.reflect.Field byValueField = mappedReg.getDeclaredField("byValue");
            byValueField.setAccessible(true);
            java.util.Map<?, ?> byValue = (java.util.Map<?, ?>) byValueField.get(blockReg);
            int patched = 0;
            Method holderGetMethod = null;
            try {
                holderGetMethod = Class.forName("net.minecraft.core.Holder$Reference", true, mcl).getMethod("value");
            } catch (Exception e) {
                // fallback: get()
                holderGetMethod = Class.forName("net.minecraft.core.Holder$Reference", true, mcl).getMethod("get");
            }
            for (Object holder : byValue.values()) {
                try {
                    Object block = holderGetMethod.invoke(holder);
                    java.lang.reflect.Method defaultState = blockCls.getMethod("defaultBlockState");
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
                        java.lang.reflect.Method initCache = state.getClass().getMethod("initCache");
                        initCache.invoke(state);
                    } catch (Exception ignore) {}
                } catch (Exception e) {
                    // 跳过不能访问的
                }
            }
            System.out.println("[Mirage] 已修补 " + patched + " 个 BlockState");

            System.out.println("[Mirage] 重新冻结注册表 ...");
            io.mirage.loader.MirageDebug.setStage("冻结注册表");
            io.mirage.loader.MirageDebug.setProgress(85);
            LoadingScreen.setStage("冻结注册表...");
            LoadingScreen.setProgress(85);
            // bug #1 兜底：native backend 已在注册时精准 remove(value)，正常情况下这里应为空。
            // 若仍有残留（mod 自己 new Block(props.setId) 但没调 Registry.register），
            // 打印出来并 clear——不静默吞掉，日志能看到哪些 mod 漏注册了。
            try {
                for (Object regObj : new Object[]{blockReg, itemReg, tabReg}) {
                    java.util.Map<?, ?> unreg = (java.util.Map<?, ?>) unregField.get(regObj);
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

            System.out.println("[Mirage] 通过 MirageClassLoader 加载 " + TARGET_MC_MAIN + " ...");

            // 调试：检查 classpath 能否找到 mod jar 的 assets
            try {
                java.util.Enumeration<java.net.URL> assets = mcl.getResources("assets/");
                System.out.println("[Mirage] getResources(\"assets/\"):");
                while (assets.hasMoreElements()) {
                    System.out.println("[Mirage]   -> " + assets.nextElement());
                }
                // 检查具体贴图
                java.net.URL tex = mcl.getResource("assets/testtab/textures/item/magic_dust.png");
                System.out.println("[Mirage] testtab texture: " + tex);
                java.net.URL lang = mcl.getResource("assets/testtab/lang/zh_cn.json");
                System.out.println("[Mirage] testtab lang: " + lang);
            } catch (Exception e) {
                System.err.println("[Mirage] getResources debug failed: " + e);
            }
            io.mirage.loader.MirageDebug.setStage("启动 Minecraft 客户端");
            io.mirage.loader.MirageDebug.setProgress(95);
            LoadingScreen.setStage("启动 Minecraft 客户端...");
            LoadingScreen.setProgress(95);
            List<String> modIds = new ArrayList<>();
            for (ModContainer m : mods) {
                modIds.add(m.manifest.id + " v" + m.manifest.version);
            }
            io.mirage.loader.MirageDebug.setModList(String.join(", ", modIds));

            // 早期窗口持续显示到 MC 主菜单出现
            LoadingScreen.setStage("即将就绪...");
            LoadingScreen.setProgress(99);
            LoadingScreen.release();

            Class<?> mainClass = Class.forName(TARGET_MC_MAIN, true, mcl);
            java.lang.reflect.Method main = mainClass.getMethod("main", String[].class);
            System.out.println("[Mirage] 移交控制权给 " + TARGET_MC_MAIN + " ...");

            // 客户端启动事件：MC main 尚未运行（资源包/世界均未加载），
            // 此时可注册 Screen / HUD / 按键绑定。
            System.out.println("[Mirage] 派发 ClientStartingEvent ...");
            eventBus.post(new ClientStartingEvent());
            postNeoLifecycle("net.neoforged.fml.event.lifecycle.FMLClientSetupEvent", mcl);

            main.invoke(null, (Object) args);

            // TODO: ClientStartedEvent（主菜单渲染完成后）需要一个 TitleScreen 钩子；
            //       当前 MirageDebug.loaded 已在 TitleScreen.init() 时置位，
            //       但那里拿不到 EventBus 实例，待后续把 bus 做成静态可访问再补发。

            // MC 主循环已进入，标记 loaded=true，TitleScreen 调试面板隐藏
            io.mirage.loader.MirageDebug.setStage("已加载");
            io.mirage.loader.MirageDebug.setProgress(100);
            io.mirage.loader.MirageDebug.setLoaded();
        } catch (Throwable t) {
            System.err.println("[Mirage] 启动 Minecraft 时发生致命错误:");
            t.printStackTrace();
            System.exit(1);
        }
    }

    /**
     * 调用一个 mod 入口类的初始化方法。
     * 优先按 Fabric 接口（{@link net.fabricmc.api.ModInitializer}）强转调用；
     * 不实现该接口时回退到反射找 onInitialize()，兼容现有 mirage 原生 mod。
     */
    private static void callOnInitialize(Object instance) throws Exception {
        if (instance instanceof net.fabricmc.api.ModInitializer mi) {
            mi.onInitialize();
            return;
        }
        Method m = findMethod(instance.getClass(), "onInitialize");
        if (m != null) {
            m.invoke(instance);
        } else {
            System.out.println("[Mirage]   ! " + instance.getClass().getName()
                    + " 没有实现 ModInitializer，也没有 onInitialize()，跳过");
        }
    }

    private static Method findMethod(Class<?> cls, String name) {
        try {
            Method m = cls.getMethod(name);
            m.setAccessible(true);
            return m;
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    /**
     * 实例化 mod 主类：优先无参构造；NeoForge mod 无可用无参构造时，
     * 回退到 {@code public MyMod(net.neoforged.bus.api.IEventBus modBus)} 注入式构造
     * （NeoForge 4.x 标准写法）。
     */
    private static Object constructModInstance(Class<?> cls, boolean fromNeoForge) throws Exception {
        try {
            return cls.getDeclaredConstructor().newInstance();
        } catch (NoSuchMethodException nsme) {
            if (!fromNeoForge) throw nsme;
            ClassLoader cl = cls.getClassLoader();
            Class<?> busCls = Class.forName("net.neoforged.bus.api.IEventBus", false, cl);
            Object ctx = Class.forName("net.neoforged.fml.javafmlmod.FMLJavaModLoadingContext", true, cl)
                    .getMethod("get").invoke(null);
            Object modBus = busCls.cast(ctx.getClass().getMethod("getModEventBus").invoke(ctx));
            System.out.println("[Mirage] " + cls.getName() + " 使用 IEventBus 注入式构造");
            return cls.getConstructor(busCls).newInstance(modBus);
        }
    }

    /**
     * 向 NeoForge mod bus post 一个生命周期事件（反射，避免 launch 模块硬依赖 net.neoforged.*）。
     * 无 NeoForge mod / stub 缺失时仅打印，不影响主流程。
     */
    private static void postNeoLifecycle(String eventClassName, ClassLoader neoLoader) {
        try {
            // net.neoforged.* stub 现在由 MirageClassLoader 加载（与 mod 同 loader），
            // 不能用 SystemClassLoader，否则拿到另一份 Class 对象，post 不到 mod 注册的监听器。
            ClassLoader cl = neoLoader;
            Object ctx = Class.forName("net.neoforged.fml.javafmlmod.FMLJavaModLoadingContext", true, cl)
                    .getMethod("get").invoke(null);
            Object bus = ctx.getClass().getMethod("getModEventBus").invoke(ctx);
            Object event = Class.forName(eventClassName, true, cl).getDeclaredConstructor().newInstance();
            bus.getClass().getMethod("post", Object.class).invoke(bus, event);
        } catch (Throwable t) {
            System.err.println("[Mirage] post NeoForge 生命周期事件 " + eventClassName + " 失败: " + t);
        }
    }

    private static Path parseGameDir(String[] args) {
        for (int i = 0; i < args.length - 1; i++) {
            if ("--gameDir".equals(args[i])) {
                return Paths.get(args[i + 1]);
            }
        }
        return Paths.get(".").toAbsolutePath();
    }

    /** 检查 args 里是否存在某个独立 flag（无值）。 */
    private static boolean hasFlag(String[] args, String name) {
        for (String a : args) if (name.equals(a)) return true;
        return false;
    }

    private static void banner() {
        System.out.println("==================================================");
        System.out.println("  Mirage Loader 幻像加载器 v" + VERSION);
        System.out.println("  独立 · 轻量 · 现代化");
        System.out.println("==================================================");
        System.out.println("[Mirage] 来自 Mirage Launch " + VERSION);
    }

    private Launcher() {}
}
