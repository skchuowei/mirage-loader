# 2026-10-24 Fabric 式启动 GUI（取消独立窗口）

## 一、目标

- 删除启动阶段的 Swing `LoadingScreen` 独立窗口。
- 游戏窗口一出现，就在原版 GUI 上显示 Mirage 调试进度：
  - `LoadingOverlay`：左上角调试卡片（阶段 / mod 计数 / mod 列表 / 进度条），**只用 `fill` 画**，绝不调用 `gge.text()`（会提前触发 gui_text 管线，26.2 资源重载期永久失败）。
  - `TitleScreen`：左上角 Mirage 徽标 + `Mods` 按钮；点徽标打开详细调试面板，点 `Mods` 打开 mod 列表。
- 启动阶段（MC 窗口出现之前）的进度只写控制台日志，符合 Fabric 模式。

## 二、范围（涉及文件）

| 文件 | 改动 |
|---|---|
| `launcher/mirage-launch/src/main/java/io/mirage/launch/LoadingScreen.java` | 删除 |
| `launcher/mirage-launch/src/main/java/io/mirage/launch/Launcher.java` | 移除全部 `LoadingScreen.*` 调用，只保留 `MirageDebug` 状态更新与控制台输出 |
| `core/mirage-loader/src/main/java/io/mirage/loader/MirageDebug.java` | 新增像素字渲染（AWT 光栅化 + `fill` 反射）、`drawLoadingOverlay`、`drawTitleOverlay`、`handleTitleClick`、`onTitleScreenShown` |
| `core/mirage-loader/src/main/java/io/mirage/loader/MirageTransformer.java` | `LoadingOverlay.extractRenderState` 改为 **每个 RETURN 前**注入；`TitleScreen.init/extractRenderState/mouseClicked` 改调 helper，适配 26.2 新签名 `mouseClicked(MouseButtonEvent, boolean)` |
| `mc-shili/libraries/io/mirage/mirage-launch/0.1.0/mirage-launch-0.1.0.jar` | 备份后热替换 class，并删除残留 `LoadingScreen*.class` |
| `docs/改动登记表.md`、`docs/已知bug.md`、`docs/更新日志.md` | 收尾登记 |

## 三、改动内容

1. **删除 Swing**
   - 删 `LoadingScreen.java`；`Launcher` 中 `open/setStage/setProgress/setModList/release/releaseNow` 调用全部移除。
   - 不再重定向 `System.out/err`，日志回到控制台。

2. **LoadingOverlay 调试卡片**
   - 注入点从 `visitCode` 改到 `visitInsn(RETURN)`：原版 `extractRenderState` 先在 `visitCode` 之后提交全屏背景 `fill`，旧注入会被背景盖住（这也是之前游戏内看不到面板的原因之一）。
   - 面板内容：`MIRAGE LOADER`、版本/MC 版本、阶段、mod 计数、mod 列表、进度条与百分比。
   - 文字用 AWT `Microsoft YaHei` 12px 关闭抗锯齿光栅化成像素块，再用 `GuiGraphicsExtractor.fill` 逐段绘制；不依赖 Font/gui_text 管线。
   - `MirageDebug.loaded` 为 true（已进主菜单）后不再绘制，避免淡出期盖住标题界面。

3. **TitleScreen 徽标与面板**
   - `init()` 调用 `MirageDebug.onTitleScreenShown()`（置 `loaded=true`）。
   - `extractRenderState` RETURN 前调用 `drawTitleOverlay(gge, font)`：绘制左上徽标 `Mirage v0.4.0 | N mods | 100%` 与 `Mods` 按钮。
   - 点徽标打开详细面板（mem/cpu/stage/mods/progress/mod 列表）；点 `Mods` 打开 mod 列表；再点任意处关闭。
   - `mouseClicked` 适配 26.2 的 `(Lnet/minecraft/client/input/MouseButtonEvent;Z)Z`。

## 四、风险与回滚

- **风险 1**：AWT 光栅化在极简 JRE 下不可用 → 像素字整体 try/catch，失败只打一行日志，进度条仍可用。
- **风险 2**：ASM 注入点/新签名不匹配 → 离线用 ASM 对 26.2 的 `LoadingOverlay`/`TitleScreen` 做结构校验，再部署。
- **风险 3**：`COMPUTE_FRAMES` 对 RETURN 注入产生帧错误 → 离线 `CheckClassAdapter` 校验。
- **回滚**：部署前备份 `mirage-launch-0.1.0.jar.bak-20261024-gui`，失败直接覆盖回滚；源码改动单独提交。

## 五、验证方式

1. `gradle :core:mirage-loader:build :launcher:mirage-launch:build` BUILD SUCCESSFUL。
2. 离线自检：用 26.2.jar 读取两个类，经 `MirageTransformer` 转换后 `CheckClassAdapter` 校验通过；用假 `gge` 调 `drawLoadingOverlay/drawTitleOverlay` 不抛异常。
3. 人工冒烟（用户双击 bat）：
   - 启动过程中不再出现 Swing 窗口；
   - MC 窗口出现后左上角出现 Mirage 调试卡片与进度条；
   - 主菜单左上角出现 `Mirage ... | Mods` 徽标，点击可开合调试面板/mod 列表；
   - 日志无 `[Mirage] transform ... failed`。

## 六、时间线

- 2026-10-24：计划、实现、编译、部署、待人工冒烟。

## 七、进度记录

- [x] 研读 Fabric/Forge/NeoForge 启动界面实现，确认 Fabric 式路线
- [x] 删除 Swing 窗口
- [x] MirageDebug GUI 绘制
- [x] ASM 注入改造
- [x] 编译 + 离线自检
- [x] 部署
- [ ] 人工冒烟

## 八、第二轮修订（用户反馈：没有立即窗口 / 要日志+阶段+CPU+内存 / HUD 风格）

**目标**：启动后立刻可见；显示日志节选、当前阶段、CPU/内存；视觉改为原版 F3 HUD 风格，去掉装饰性卡片。

**方案**：
1. 新增 `launcher/.../EarlyHud.java`：AWT 无边框 JWindow，`main()` 开头显示，640×236、纯黑半透明、Consolas；显示阶段/进度条/mod 数/CPU/内存/最近 8 行日志；`MirageDebug.loaded` 为 true 时自动 dispose。
2. `Launcher`：`installLogTee()` 包一层 stdout/stderr，控制台输出不变，整行交给 `MirageDebug.log` 过滤。
3. `MirageDebug`：日志缓冲 8 行 + 连续去重；CPU 用 `getProcessCpuLoad()`；`drawLoadingOverlay`/`drawTitleOverlay` 改 HUD 配色（白/灰/绿/黄/红），加载 HUD 增加日志节选，标题面板增加 CPU/内存/日志。
4. `MirageTransformer` 不变（RETURN 注入已生效，日志已确认）。

**验证**：gradle 编译通过；部署 jar 热替换 `MirageDebug`/`Launcher`/`EarlyHud` 共 5 个 class；待人工冒烟。

**进度**：
- [x] EarlyHud + 日志 tee
- [x] MirageDebug HUD 重构
- [x] 编译
- [x] 部署
- [ ] 人工冒烟（已被第三轮取代）

## 九、第三轮修订（用户反馈：改 MC 本体，启动时直接强制渲染主窗口）

**目标**：不再使用任何独立窗口（AWT/EarlyHud 删除），改为修改 MC 本体，让真正的 MC 主窗口在 mod 初始化**之前**创建并显示；mod 加载期间用窗口标题栏滚动显示阶段/进度/CPU/内存/最后一条重要日志。

**方案**：
1. `MirageTransformer` 新增 `transformMinecraft`：只对 `Minecraft.<init>(Lnet/minecraft/client/main/GameConfig;)V` 织入，在 `putfield window` 之后插入 `MirageDebug.onMainWindowCreated(this.window)`；用 `COMPUTE_MAXS`（不重算帧）避免大构造器触发 `getCommonSuperClass`。
2. `MirageDebug` 新增主窗口交接：`onMainWindowCreated` 反射 `Window.handle()` + `GLFW.glfwShowWindow` 强制显示窗口，阻塞等待 mod 初始化完成（`MOD_LOADING_DONE`），期间每 100ms 刷新窗口标题；`awaitMainWindow`/`releaseModLoading` 给 Launcher 用。
3. `Launcher` 改为两线程：主线程只做发现/类加载器/Mixin，然后启动 `Mirage-MC` 线程跑 `Main.main`；主线程 `awaitMainWindow()` 后解冻注册表、初始化 mod、派发事件，再 `releaseModLoading()` 放行 MC，最后 `mcThread.join()`。
4. 删除 `EarlyHud.java`；游戏内 `LoadingOverlay`/`TitleScreen` HUD 保持不变。

**验证**：`gradle :core:mirage-loader:build :launcher:mirage-launch:build` 通过；离线下用 26.2.jar 的 `Minecraft.class` 经新 Transformer 织入（148439 → 148512 字节，含 `onMainWindowCreated`）并跑 `CheckClassAdapter.verify`（129 个 jar 的类路径）无结构/数据流错误；已重建 fat jar 部署（`.bak-20261024-window`），`EarlyHud*.class` 已移除。

**风险**：
- MC 跑在 `Mirage-MC` 线程而非主线程，需实测 GLFW/渲染线程兼容性；
- mod 初始化期间窗口客户区无内容，只有标题栏进度；
- `awaitMainWindow` 180s 超时后仍继续初始化 mod（避免死锁）。

**进度**：
- [x] 删除 EarlyHud
- [x] ASM 织入 `Minecraft.<init>`
- [x] MirageDebug 主窗口交接 + 标题进度
- [x] Launcher 线程重构
- [x] 编译 + 离线校验
- [x] 部署
- [ ] 人工冒烟（已被第四轮取代）

## 十、第四轮修订（用户：不用方案 A，使用 Forge 那套方案）

**目标**：照 NeoForge/Forge 的 `fmlearlydisplay`，在 Launcher 最早期就创建**真实的 GLFW + OpenGL 早期窗口**，1:1 还原 NeoForge 早期加载界面（红底 `#EF323D`、顶部内存条 + Memory/CPU 文本、Loading mods 不确定条、Config loading 进度条、右下铁砧动画 + 版本号）；**左下角滚动日志动画不做**。等 MC 的 `Window.createGlfwWindow` 被调用时，把同一个 window handle + GL 上下文交接给 MC，之后由原版加载界面接管。第三轮的“MC 线程 + 标题栏进度”整体废弃。

**实现**：
1. `launcher/mirage-launch/.../earlydisplay/MirageEarlyWindow.java`（新）：
   - `start(mcVersion, loaderVersion, width, height)`：`glfwInit` → 依次尝试 4.6…3.3 core → 建隐藏窗口 → 居中 → `glfwMakeContextCurrent` + `GL.createCapabilities()` → 初始化 `EarlyGl`/`EarlyFont`/铁砧贴图 → 显示窗口 → 20fps 渲染线程（`Semaphore` 保护，帧末 `glfwMakeContextCurrent(NULL)`）。
   - `createOrBorrow(...)`：织入点调用；停渲染线程（cancel + shutdownNow + awaitTermination）→ 主线程 `glfwMakeContextCurrent` → 设置 MC 的标题/尺寸 → `glfwSwapInterval(0)` → `glfwShowWindow` → 返回同一 handle；未启动/已交接/异常时回退原版 `glfwCreateWindow`。
   - 渲染布局逐项照搬 Forge `RenderElement`：`BAR_WIDTH=400`、`BAR_HEIGHT=20`、内存条 y=10、进度条 y=250 起、`barSpacing=lineSpacing-descent+20`、铁砧 128x128（32 帧竖排 128x4096）、版本号右下 `x=W-w-10 / y=H-lineSpacing+descent-10`。
2. `EarlyFont.java`（新）：移植 `SimpleFont`（Monocraft 24px、256x128 R8 图集、`stbtt_PackFontRanges`、`stbtt_GetPackedQuad`）。
3. `EarlyGl.java`（新）：移植 `ElementShader` 思路但**不经过 FBO**，直接画默认帧缓冲，顶点 shader 翻转 Y；四边形在 CPU 展开为三角形，免 IBO；三种 rendertype（0 字体 .r / 1 RGBA 贴图 / 2 纯色）。
4. 资源：`launcher/mirage-launch/src/main/resources/{Monocraft.ttf, forge_anvil.png}`（从 `refs/forge/fmlearlydisplay` 复制）。
5. `MirageTransformer`：删除 `transformMinecraft`；新增 `transformWindow`，把 `Window.createGlfwWindow`（`(IILjava/lang/String;JLcom/mojang/blaze3d/systems/GpuBackend;)J`）里的 `GLFW.glfwCreateWindow` 调用替换为 `MirageEarlyWindow.createOrBorrow`（`COMPUTE_MAXS`）。
6. `Launcher`：恢复单线程——启动即 `MirageEarlyWindow.start(...)`，然后 discoverMods → 类加载器 → Mixin → Bootstrap → 解冻/注册/冻结 → mod 事件 → `ClientStartingEvent` → 反射 `Main.main`（在 Launcher 线程上跑），返回后 `setLoaded()`；删除 `Mirage-MC` 线程与 `awaitMainWindow/releaseModLoading`。
7. `MirageDebug`：删除主窗口 latch/标题进度相关字段与 `onMainWindowCreated/forceShowWindow/updateWindowTitle`；保留游戏内 HUD。
8. `build.gradle.kts`：新增 LWJGL 3.4.1 `compileOnly`（lwjgl/glfw/opengl/stb + unsafe），不打进 jar（运行时 classpath 已有）。

**验证**：`gradle :core:mirage-loader:build :launcher:mirage-launch:build` 通过；离线用 26.2.jar 的 `Window.class` 过新 Transformer：`createGlfwWindow` 中 `glfwCreateWindow` 已被替换为 `MirageEarlyWindow.createOrBorrow(IILjava/lang/CharSequence;JJ)J`（count=1，24705→24778 字节），ASM round-trip OK；fat jar 全量重打包（1305 条目）并部署（备份 `.bak-20261024-earlywindow`），包内 5 个新 class + 字体 + 铁砧贴图 SHA256 与构建输出一致。

**风险**：
- 复用窗口后 MC 的 `GlBackend.setWindowHints` 不再参与创建，但早期窗口同样请求 3.3+ core，`GlDevice` 会自行 `glfwMakeContextCurrent` + `createCapabilities`；
- `io.mirage.*` 走 SystemClassLoader，`org.lwjgl.*` 在 MirageClassLoader 里另有一份 Java 侧包装，但 native GLFW 库状态共享，handle/上下文可跨副本使用；
- 早期窗口渲染线程与 MC 主线程切换 GL 上下文，必须保证渲染线程已完全退出（已用 Semaphore + awaitTermination 保证）。

**进度**：
- [x] earlydisplay 三个类 + 资源
- [x] Window 织入（替换 Minecraft 织入）
- [x] Launcher 恢复单线程 + 最早启动早期窗口
- [x] MirageDebug 清理交接代码
- [x] 编译 + 离线校验
- [x] 重打包部署
- [ ] 人工冒烟
