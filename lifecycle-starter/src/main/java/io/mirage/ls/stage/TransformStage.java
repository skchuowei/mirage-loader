package io.mirage.ls.stage;

import io.mirage.api.error.MirageErrors;
import io.mirage.api.log.MirageLog;

import io.mirage.loader.ModContainer;
import io.mirage.loader.MirageClassLoader;
import io.mirage.loader.MirageDebug;
import io.mirage.loader.LoadProgress;
import io.mirage.ls.LifecycleContext;
import io.mirage.ls.Stage;
import io.mirage.ls.StageOutcome;

import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * S3 字节码转换（LS-Interfaces.md §7；语义见 MG-Lifecycle.md S3）。
 *
 * <p>phase 2 迁入的 Launcher 逻辑（保序）：MirageDebug 打点 → MirageClassLoader 创建 →
 * mod jar 挂载 → mixin config 扫描 → {@code MirageMixinBootstrap.init} →
 * AppClassLoader addURL（ucp 反射，JDK25 内部 API）。
 *
 * <p>classloader 经 {@code "S3.classLoader"} 交给 S4/S6/S7——它是整条 pipeline 的
 * 类加载中枢（mod 类、net.neoforged/fabricmc stub、MC 类都经它）。
 */
public final class TransformStage implements Stage {

    @Override
    public String id() {
        return "S3";
    }

    @Override
    public String name() {
        return "BytecodeTransform";
    }

    @Override
    public StageOutcome execute(LifecycleContext ctx) {
        return StageSupport.guard(() -> {
            MirageDebug.setPhase(2);
            List<ModContainer> mods = StageSupport.get(ctx, "S1.mods");

            MirageDebug.setStage("创建类加载器");
            MirageDebug.setProgress(14);

            String cp = System.getProperty("java.class.path");
            String[] entries = cp.split(java.io.File.pathSeparator);
            List<URL> urlList = new ArrayList<>();
            for (String e : entries) {
                if (!e.isEmpty()) urlList.add(new java.io.File(e).toURI().toURL());
            }
            URL[] urls = urlList.toArray(new URL[0]);
            MirageLog.MIRAGE.info("创建 MirageClassLoader，" + urls.length + " 个 URL");
            MirageClassLoader mcl = new MirageClassLoader(urls, ClassLoader.getSystemClassLoader().getParent());

            // 把每个 Mod jar 加入 classloader（真实分母 = mods.size()）
            LoadProgress.Meter jarMeter = LoadProgress.begin("挂载 jar", mods.size());
            int jarIdx = 0;
            for (ModContainer mod : mods) {
                LoadProgress.set(jarMeter, ++jarIdx);
                mcl.addJar(mod.jarFile.toFile());
                MirageLog.MIRAGE.debug("添加 mod jar: " + mod.jarFile.getFileName());
            }
            LoadProgress.end(jarMeter);

            // ---- 可选模块懒加载（G9）----
            // 时机：mcl 已建、mod jar 已全部加入 —— 此时加模块 jar 不会与 mod jar 抢优先级，
            // 且 mod 类尚未加载（S6 才实例化），模块的类可以先于 mod 到位。
            // 模块 jar 默认在 <gameDir>/modules/，不进 fat jar、不参与启动期扫描；
            // 只有被某个 mod 在 mirage.mod.json 里声明 "requires" 才会被加进来。
            io.mirage.loader.module.ModuleRegistry.Result moduleResult =
                    io.mirage.loader.module.ModuleRegistry.load(ctx.gameDir(), mcl, mods);
            ctx.properties().put("S3.modules", moduleResult);

            // ---- 初始化 SpongePowered Mixin 管线 ----
            // 收集策略见 ModMixinConfigs（纯逻辑类，离线断言在 scripts/verify_mod_mixin_config.ps1）：
            // fabric.mod.json "mixins" 声明（可含子目录）优先 + 根目录 *.mixin.json 盲扫兜底；
            // 注册名一律完整资源路径（带 .json）——Mixin 0.8.7 对传入名原样查资源、
            // 查不到静默吞（Error encountered reading mixin config），缺后缀 = config 永远不生效。
            java.util.Map<String, io.mirage.loader.mixin.ModMixinConfigs.Result> perMod =
                    new java.util.LinkedHashMap<>();
            LoadProgress.Meter cfgMeter = LoadProgress.begin("收集 mixin 配置", mods.size());
            int cfgIdx = 0;
            for (ModContainer mod : mods) {
                LoadProgress.set(cfgMeter, ++cfgIdx);
                List<String> declared = mod.fabricMixinConfigs != null ? mod.fabricMixinConfigs : List.of();
                java.util.Set<String> rootEntries = new java.util.LinkedHashSet<>();
                java.util.Set<String> allEntries = new java.util.LinkedHashSet<>();
                try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(mod.jarFile.toFile())) {
                    java.util.Enumeration<? extends java.util.zip.ZipEntry> zEntries = zf.entries();
                    while (zEntries.hasMoreElements()) {
                        String name = zEntries.nextElement().getName();
                        allEntries.add(name);
                        if (!name.contains("/") && name.endsWith(".mixin.json")) {
                            rootEntries.add(name);
                        }
                    }
                } catch (Exception e) {
                    MirageLog.MIXIN.warn("扫描 mod jar 失败（" + mod.jarFile.getFileName() + " 的 mixin 不会生效）", e);
                    continue;
                }
                perMod.put(mod.manifest.id, io.mirage.loader.mixin.ModMixinConfigs.collectOne(
                        mod.manifest.id, declared, rootEntries, allEntries));
            }
            LoadProgress.end(cfgMeter);
            for (java.util.Map.Entry<String, io.mirage.loader.mixin.ModMixinConfigs.Result> e : perMod.entrySet()) {
                if (!e.getValue().missing.isEmpty()) {
                    MirageLog.MIXIN.warn("mod '" + e.getKey() + "' 声明的 mixin config 在 jar 中不存在: "
                            + e.getValue().missing);
                }
                for (String cfg : e.getValue().configs) {
                    MirageLog.MIXIN.debug("发现 mixin config: " + cfg);
                }
            }
            List<String> mixinConfigs = io.mirage.loader.mixin.ModMixinConfigs.collectAll(perMod);
            // 加上 loader 自身的 mixin config
            mixinConfigs.add("mirage.mixins.json");
            io.mirage.mixin.MirageMixinBootstrap.init(mcl, mixinConfigs);
            MirageLog.MIXIN.info("Mixin 管线初始化完成, " + mixinConfigs.size() + " 个 config");
            // 实际织入发生在 MC 类加载期（S7 乃至进世界之后），目标类总数拿不到 → 长驻不定条。
            // 计数由 MirageClassLoader 每经过一次 Mixin 管线就 +1，显示在计数行 "mixin N"。
            LoadProgress.begin("织入字节码");

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
                    MirageLog.MIRAGE.debug("mod jar 已加入系统 classpath: " + mod.jarFile.getFileName());
                }
            } catch (Exception e) {
                // JDK25 内部 API 变更时此处失败（diag 日志里的 NoSuchFieldException: ucp）——
                // 不 FATAL：资源查找还有 mcl.getResources 兜底路径。
                MirageErrors.ignore("mod jar 加入系统 classpath 失败（JDK25 内部 API 变更所致；已走 mcl.getResources 兜底路径，不阻断启动）", e);
            }

            ctx.properties().put("S3.classLoader", mcl);
        });
    }
}
