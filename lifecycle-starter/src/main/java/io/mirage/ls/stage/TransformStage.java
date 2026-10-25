package io.mirage.ls.stage;

import io.mirage.loader.ModContainer;
import io.mirage.loader.MirageClassLoader;
import io.mirage.loader.MirageDebug;
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
            System.out.println("[Mirage] 创建 MirageClassLoader，" + urls.length + " 个 URL");
            MirageClassLoader mcl = new MirageClassLoader(urls, ClassLoader.getSystemClassLoader().getParent());

            // 把每个 Mod jar 加入 classloader
            for (ModContainer mod : mods) {
                mcl.addJar(mod.jarFile.toFile());
                System.out.println("[Mirage] 添加 mod jar: " + mod.jarFile.getFileName());
            }

            // ---- 初始化 SpongePowered Mixin 管线 ----
            List<String> mixinConfigs = new ArrayList<>();
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
                // JDK25 内部 API 变更时此处失败（diag 日志里的 NoSuchFieldException: ucp）——
                // 不 FATAL：资源查找还有 mcl.getResources 兜底路径。
                System.err.println("[Mirage] 加入系统 classpath 失败: " + e);
            }

            ctx.properties().put("S3.classLoader", mcl);
        });
    }
}
