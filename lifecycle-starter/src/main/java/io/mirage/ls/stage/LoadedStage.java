package io.mirage.ls.stage;

import io.mirage.api.event.ClientStartingEvent;
import io.mirage.eventbus.EventBus;
import io.mirage.loader.ModContainer;
import io.mirage.loader.MirageClassLoader;
import io.mirage.loader.MirageDebug;
import io.mirage.ls.LifecycleContext;
import io.mirage.ls.Stage;
import io.mirage.ls.StageOutcome;

import java.net.URL;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

/**
 * S7 加载完成（LS-Interfaces.md §7；语义见 MG-Lifecycle.md S7）。
 *
 * <p>phase 2 迁入的 Launcher 逻辑（保序，逐行同日志）：
 * <ol>
 *   <li>classpath 资源调试探测（getResources("assets/") + testtab 贴图/语言文件，
 *       诊断 mod jar 资产是否对 MC 可见——保留拆分前的调试行为）；</li>
 *   <li>MirageDebug 进度推进：{@code 启动 Minecraft 客户端}/95 → mod 列表 → 92；</li>
 *   <li>客户端启动事件：{@link ClientStartingEvent} + FMLClientSetupEvent 桥接；</li>
 *   <li><b>移交 MC 主类</b>：TCCL 切到 MirageClassLoader，反射调用
 *       {@value #TARGET_MC_MAIN} 的 main——正常路径永不返回，MC 主循环阻塞在内
 *       （LS-Interfaces §2 契约允许）；</li>
 *   <li>MC 主循环结束后：{@code 已加载}/100/setLoaded。</li>
 * </ol>
 *
 * <p>异常外壳用 {@link StageSupport#guard}：与拆分前 Launcher 单体 try/catch 一致，
 * 致命错误打印 {@code [Mirage] 启动 Minecraft 时发生致命错误:} + 堆栈后由调度器 exit 1。
 */
public final class LoadedStage implements Stage {

    /** MC 客户端主类（自 Launcher.TARGET_MC_MAIN 迁入，值不变）。 */
    private static final String TARGET_MC_MAIN = "net.minecraft.client.main.Main";

    @Override
    public String id() {
        return "S7";
    }

    @Override
    public String name() {
        return "Loaded";
    }

    @Override
    public StageOutcome execute(LifecycleContext ctx) {
        return StageSupport.guard(() -> {
            List<ModContainer> mods = StageSupport.get(ctx, "S1.mods");
            EventBus eventBus = StageSupport.get(ctx, "S2.eventBus");
            MirageClassLoader mcl = StageSupport.get(ctx, "S3.classLoader");

            System.out.println("[Mirage] 通过 MirageClassLoader 加载 " + TARGET_MC_MAIN + " ...");

            // 调试：检查 classpath 能否找到 mod jar 的 assets
            try {
                Enumeration<URL> assets = mcl.getResources("assets/");
                System.out.println("[Mirage] getResources(\"assets/\"):");
                while (assets.hasMoreElements()) {
                    System.out.println("[Mirage]   -> " + assets.nextElement());
                }
                // 检查具体贴图
                URL tex = mcl.getResource("assets/testtab/textures/item/magic_dust.png");
                System.out.println("[Mirage] testtab texture: " + tex);
                URL lang = mcl.getResource("assets/testtab/lang/zh_cn.json");
                System.out.println("[Mirage] testtab lang: " + lang);
            } catch (Exception e) {
                System.err.println("[Mirage] getResources debug failed: " + e);
            }
            MirageDebug.setStage("启动 Minecraft 客户端");
            MirageDebug.setProgress(95);
            List<String> modIds = new ArrayList<>();
            for (ModContainer m : mods) {
                modIds.add(m.manifest.id + " v" + m.manifest.version);
            }
            MirageDebug.setModList(String.join(", ", modIds));

            // 客户端启动事件：MC 构造器仍被阻塞在主窗口回调里，资源包/世界均未加载，
            // 此时可注册 Screen / HUD / 按键绑定。
            System.out.println("[Mirage] 派发 ClientStartingEvent ...");
            eventBus.post(new ClientStartingEvent());
            NeoLifecycle.postNeoLifecycle("net.neoforged.fml.event.lifecycle.FMLClientSetupEvent", mcl);

            System.out.println("[Mirage] mod 初始化完成，启动 Minecraft 主类（交接早期窗口）...");
            MirageDebug.setStage("启动 Minecraft");
            MirageDebug.setProgress(92);

            // 单线程：直接在 Launcher 线程上跑 MC 主类。Window.createGlfwWindow
            // 中的 GLFW.glfwCreateWindow 已织入 MirageEarlyWindow.createOrBorrow，
            // 会把早期窗口 + 已 current 的 GL 上下文交接给 MC。
            Thread.currentThread().setContextClassLoader(mcl);
            Class<?> mainClass = Class.forName(TARGET_MC_MAIN, true, mcl);
            mainClass.getMethod("main", String[].class).invoke(null, (Object) ctx.args());

            // MC 主循环已结束
            MirageDebug.setStage("已加载");
            MirageDebug.setProgress(100);
            MirageDebug.setLoaded();
        });
    }
}
