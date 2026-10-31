# 已知 Bug 与已知限制

> **只放尚未修复的 bug / 已知限制**。修完一条就把它划掉（~~删除线~~），并在 `更新日志.md` 注明"连带修复了 xxx"。
> 不要把"计划中要做的事"写在这里——那是 `PLAN.md` / `docs/plans/` 的事。

## 已知 Bug

| # | 发现日期 | 现象 | 根因 / 位置 | 严重度 | 状态 |
|---|---|---|---|---|---|
| ~~1~~ | ~~2026-10-11~~ | ~~部署含 IR 分支的 fat jar 启动游戏，mdk-example 初始化并 flush DeferredRegister 后，MC 在 `MappedRegistry.freeze()` 抛 `IllegalStateException: Some intrusive holders were not registered: [Reference{null=[unregistered]}]`，游戏致命错误退出~~ | ~~`launcher/mirage-launch` 的 DeferredRegister native backend 只反射写 `BuiltInRegistries.ITEM/BLOCK`，没有把注册结果回填到 MC 的 `Holder.Reference`~~ | ~~高~~ | **已修 `c0a3035`**：native backend 在 `Registry.register()` 后主动 `unregisteredIntrusiveHolders.remove(value)` 精准清理；freeze 前改为打印残留 + 兜底 clear，不再静默吞。需人工双击 bat 实测确认。 |

## 已知限制（设计如此，不是 bug）

- **无 `src/test/` 单元测试**：当前回归依赖 `gradle build` + 启动冒烟。待建。
- **无 Gradle Wrapper**：必须用全局 `gradle` 9.x。待补 `gradlew`。
- **无 `scripts/run_regression.ps1`**：冒烟靠手动跑 `..\启动 26.2-mirage.bat` 看 `..\logs\latest.log`。待补自动化脚本。
- **只支持 MC 26.2**：不向下兼容（决策 D10）。
- **Mojang 官方 ProGuard 映射**：MC 升级时 Mod 需重编译（决策 D2 的代价）。
- **早期窗口要求 GL 3.3+ core**：`MirageEarlyWindow` 依次尝试 4.6…3.3 core 创建隐藏窗口，全部失败时只打一行日志，随后 MC 自己建窗口（无早期界面）。
- **窗口交接依赖 `Window.createGlfwWindow` 的调用点**：`MirageTransformer.transformWindow` 按 `(IILjava/lang/String;JLcom/mojang/blaze3d/systems/GpuBackend;)J` 匹配 `createGlfwWindow`，并把其中的 `GLFW.glfwCreateWindow` 替换为 `MirageEarlyWindow.createOrBorrow`；MC 升级若改签名/改调用方式会静默不交接，退化为原版窗口（早期界面直接销毁，不影响启动）。
- **早期窗口渲染线程与 MC 主线程交替持有 GL 上下文**：交接前必须确保渲染线程完全退出（`renderTask.cancel` + `scheduler.shutdownNow` + `awaitTermination(2s)`，并用 `Semaphore` 防止帧渲染中切上下文）；若超时则销毁早期窗口、回退新建窗口。
- **GL 调用必须在"已绑定 capabilities 的线程"上发起**：LWJGL 的 `GLCapabilities` 存在 `GL.capabilitiesTLS`（ThreadLocal），JNI 侧按当前线程取函数指针表。只 `glfwMakeContextCurrent` 而不在本线程 `GL.createCapabilities()`，第一条 GL 调用会走 `JNIEnv->FatalError` **直接 abort JVM**（无 Java 异常、无 crash report，现象是进程无声退出）。`MirageEarlyWindow` 的 `initWindow`（主线程）与 `renderTick`（`Mirage-EarlyDisplay` 线程）各绑一次；以后任何新开线程画 GL 的地方都要照做。
- **`io.mirage.earlydisplay` 走 SystemClassLoader，`org.lwjgl.*` 在 MirageClassLoader 另有 Java 侧副本**：早期窗口的单例状态靠前者保证，GLFW/GL 靠 native 库共享状态；不能把 earlydisplay 类改成由 MirageClassLoader 加载，否则 `start` 与织入点会拿到两个实例。
- **进程内会加载两份 `glfw.dll`，窗口链表按实例各存一份（2026-10-31 修的退出崩溃）**：`hs_err` 的模块列表可同时看到 `natives\lwjgl\glfw.dll`（Launcher/早期窗口侧）与 `natives\lwjgl\3.4.1-snapshot\x64\glfw.dll`（MC 侧）。两份 dll 各持有自己的 `_glfw.windowListHead`，同一 `GLFWwindow*` 只在其中一份的链表里。`glfwSetWindowTitle`/`glfwPollEvents`/`glfwSwapBuffers` 只操作传入的结构体指针，跨实例调用「看起来正常」；但 `glfwDestroyWindow` 还要**摘链表**，用非创建方实例销毁会走到 `glfw.dll+0x77a0 mov rax,[rcx]` 的 `prev->next` 遍历，而 head 为 NULL → 原生 `EXCEPTION_ACCESS_VIOLATION`（现象：点「退出游戏」必崩，无 crash-report，只有 `hs_err_pid*.log`）。**规则：谁创建谁销毁**——早期窗口的销毁必须回到 `MirageEarlyWindow.destroyWindow(J)`（织入自 `Window.close()`），不要再直接调 `GLFW.glfwDestroyWindow` 处理借用来的句柄。
- **Mixin 回调的 static 必须与目标方法一致**：目标 `static` → 回调必须 `static`；目标实例方法 → 回调必须非 static。写错不会编译报错，只在运行期 `APPLY` 阶段抛 `InvalidInjectionException`，且失败类的转译被 `MirageMixinBootstrap` 吞成一行日志、返回原始字节，故障会以"目标类某静态字段为 null"的形态在很远的调用栈上炸（例：`Game version not set`）。新增/改动 mixin 后务必核对目标签名。
- **`fatJar` 必须显式 `dependsOn(configurations.runtimeClasspath)`**：`filter{}.map{ zipTree(it) }` 会把 `FileCollection` 降级为普通 List 并丢掉其 task 依赖信息，导致单独跑 `:launcher:mirage-launch:fatJar` 时子模块 `jar` 不重建、旧字节码被打进包（且任务显示 UP-TO-DATE）。出包后建议 `javap` 抽查改动过的类。
- **`LoadingOverlay` 阶段禁用 `gge.text()`**：26.2 资源重载期调 `text()` 会提前触发 gui_text 管线，core/text shader 未就绪导致永久失败。该阶段文字一律用 AWT 光栅化成像素块 + `fill` 绘制（见 `MirageDebug.pixelText`），后续勿改成 `text()`。
- **`LoadingOverlay`/`TitleScreen` 的绘制必须注入在 `RETURN` 前**：原版方法末尾会提交全屏背景 `fill`，`visitCode` 注入会被整块覆盖。
- **MSR 沙盒在进程内只能"止血"，不能"隔离"（MSR P1 边界，2026-10-31）**：`System.exit`/`Runtime.exit`/`Runtime.halt` 已由字节码改写拦截（`SandboxGuard`），但下列仍然拦不住，必须由 P3（一次性实例 = 耗材）消化：
  - 文件/网络副作用（mod 构造里写配置、连网、`Runtime.exec`）——进程内无解，沙盒只在 `finally` 里恢复 `Locale`/`TimeZone`/系统属性这三类全局态；
  - 通过**方法引用**（`Runtime.getRuntime()::halt`，走 `invokedynamic`）或**反射**拿到的 `Method` 调 exit——只拦直接调用；
  - mod 用 `catch (Throwable)` 把 `SandboxKillException` 自己吞掉——它继承 `Error` 只是为了不让 `catch (Exception)` 吞，`Throwable` 挡不住。
- **MSR 超时是 best-effort（`interrupt`），不承诺硬杀**：`join(2000)` 超时后 `interrupt`，拦得住 `sleep/wait` 等可中断阻塞；纯 CPU 自旋或不可中断 IO 的线程会作为 daemon 残留到进程结束（Java 25 已无 `Thread.stop`）。此类 mod 记 `incomplete=true`，**半成品数据一律不写缓存**。
- **`incomplete` 的 mod 每次启动都会重录（最坏 2 s/个）**：半成品不入库换来的是"下次再试"，代价是每次启动重付一次熔断时间。P2「fake pass 按需化 + 被动捕获」落地后这项才消失。
- **fake 表面变更会让 `.mid-cache` 里的 `recorded` 全量作废一次**：`Entry.fakeVersion` 校对只在 `recorded != null` 时生效，所以 fake 类增删改后首次启动会把所有 neo mod 重录一遍（N × ≤2 s），之后恢复命中。这是刻意设计（宁可重录，也不要用与现实不符的录制数据）。
- **被动捕获只看得见"自己的入口"，看不见 mod 直接调 MC 原版方法（MSR P2 边界，2026-10-31）**：`PassiveRecorder` 的挂点全在自有代码里（`io.mirage.api.*` / `DeferredRegister` / `EventBus` / `NeoEventBusImpl` / `ModLoadingContext`）。mod 在构造里直接写 `level.addFreshEntity(...)` / `BuiltInRegistries.X.register(...)` 时，被动侧**看不到**——那需要字节码插桩（roadmap P3）。后果：被动 `touched_apis` 天然比沙盒（fake 表面全量）窄，`MidCache.merge` 因此取并集而不是覆盖。**不要**把被动结果当"完整 API 面"用。
- **`net.neoforged.fml.loading.FMLLoader` 只实现了 `versionInfo()`（MSR P2）**：`VersionInfo` 的 `mcVersion/majorMcVersion/neoForgeVersion/fmlVersion/mavenVersion/buildNumber` 可用且按 mod 上下文伪装；`FMLLoader.getDist()/getGamePath()/getModsPath()/isProduction()` **没有实现**，调到会抛 `NoSuchMethodError`。这是故意的——宁可在日志里看到一个明确的缺失方法名，也不要返回编造的假值让 mod 走错分支。
- **`.mid-cache` schema 升版会让整库重识别一次**：`lookup` 现在要求 `specVersion == "0.4"`，老条目（0.1/0.2/0.3）一律视为未命中。重识别 = ASM 扫 class（秒级），**不**触发 fake pass（区间完整的 mod 按 P2 判据直接跳过），因此这次升版不会再出现"每个 mod 2 秒"的代价。

