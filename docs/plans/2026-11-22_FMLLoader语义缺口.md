# FMLLoader 语义缺口：getDist() 真值实现与门禁

> 关联：`docs/更新日志.md` 2026-11-22「实机诊断：W3-C2 取真缺口清单」；11-22 真机 `mc-shili/versions/26.2-mirage/logs/latest.log`；`docs/已知bug.md` 已知限制「`FMLLoader` 只实现 `versionInfo()`」。用户裁定：终极目标为全部 mod 兼容，**语义缺口优先于链接类缺口**。

## 目标

给 `net.neoforged.fml.loading.FMLLoader` 补上实例方法 `getDist()`，让 11-22 真机被 `NoSuchMethodError` 打断的 mezz_config → JEI 初始化链路越过这一处语义缺口；返回**加载器已注入的端侧真值**（客户端运行即 `Dist.CLIENT`），不编造、不返回假值。范围严格限于「这一处语义缺口 + 为过初始化所必需的最小方法集」，不顺手补链接期静态缺口。

**可验证标准**：

1. `verify_fml_loader_dist.ps1` 守卫层 + 行为层 + 变异层全绿：真跑 JVM 反射 `FMLLoader.getCurrent().getDist()` 得到 `Dist.CLIENT`，且 `isClient()==true`；变异层全红、源文件字节级还原、基线回绿。
2. 三套既有回归（ NeoCtor / API 表面 / W3-C1 ）全绿，API 表面**相对基线零新增缺失**。
3. `gradle :launcher:mirage-launch:fatJar` 成功，体积与类数对账（本次仅一个方法，类数不应变化）。
4. 未部署、未拉 GUI、未写 `mc-shili/`；真机 JEI 是否因此过初始化由用户实机拍板，本轮不代判。

## 前置条件与阻塞项

- 上游：N1 已建 `Dist` / `FMLEnvironment` / `FMLLoader`；`NeoCompatBootstrap.init` 已在 S6 mod 构造前把 `ctx.dist()` 注入 `FMLEnvironment`；11-22 真机清单已证明语义缺口会先于静态缺口打断 mod。
- 工作区：`git status` 显示用户自有未跟踪文件 `scripts/run_w3c_diag2.bat`（不碰、不 add）；另有并行会话在改 `MirageClassLoader.java`（本轮零接触）。三个独占文件当前均无未完成 WIP，可立即开干。
- 无其它阻塞；本轮是单文件生产改动 + 单文件门禁，不需要用户命令即可跑 headless 门禁 / gradle。

## 现状分析

**as-is**：`FMLLoader.java` 提供 `versionInfo()` / `getCurrent()` / `getCurrentOrNull()` / 实例 `isProduction()` / `getLoadingModList()`，唯独没有 `getDist()`。

**真机证据（决定性）**：11-22 最新 `latest.log:98-114`：

```
Caused by: java.lang.NoSuchMethodError: 'net.neoforged.api.distmarker.Dist net.neoforged.fml.loading.FMLLoader.getDist()'
    at ...ConfigNeoForgePhysicalSideProvider.isPhysicalClient(ConfigNeoForgePhysicalSideProvider.java:18)
    at ...ConfigProvider.<clinit>(ConfigProvider.java:34)
    at io.mirage.ls.stage.NeoLifecycle.constructModInstance(NeoLifecycle.java:57)
...
    jei(java.util.ServiceConfigurationError: net.mezzdev.config.api.Configs$IConfigProvider:
        Provider net.mezzdev.config.registration.ConfigProvider could not be instantiated)
```

即：JEI 本身并没有调 `getDist()`（javap JEI 30.48.0.244 全部类，唯一引用 `FMLLoader` 的是 `ModHelper.isInDev()`，调的是**已存在**的实例 `isProduction()`）；打断 JEI 初始化的是它依赖的 `mezz_config 0.6.11`。所以补 `getDist()` 正是「最小且直击真机」的一处。

**静态证据**：

- javap `loader-11.0.16.jar`：`public net.neoforged.api.distmarker.Dist getDist();` —— **实例方法**，签名必须逐字节一致（`invokevirtual`）。
- javap `mezz_config...jar`：`getCurrent()` → `invokevirtual FMLLoader.getDist()` → `if_acmpne Dist.CLIENT`：调用方明确以 `CLIENT` 为客户端判据；同类的 `isDevelopmentEnvironment()` 用 `isProduction()`、`getConfigRoot()` 用 `FMLPaths.CONFIGDIR`，**后两者在 MG 侧已存在**，故本轮不需要补第二个方法。
- 真值来源已存在：`ModInitStage:103` 在 mod 构造前调 `NeoCompatBridge.init(..., dist.name(), ...)` → `NeoCompatBootstrap.init` → `FMLEnvironment.init(...)`；`FMLEnvironment.getDist()` 是权威端侧。客户端运行即 `Dist.CLIENT`。按 N1 不写死常量、不编造新值：委托这个已有真值源。

**历史坑**：`docs/已知bug.md` 记录「调到 `getDist()` 会抛 `NoSuchMethodError`——故意暴露缺口，不静默返回假值」。本轮把该条从「故意缺失」升级为「已实现真值」，但**不改变其它未实现方法的 UOE / 缺失策略**；`getGamePath()` / `getModsPath()` 等没有真机调用证据，继续不做。

## 改动文件清单

| 文件 | 改什么 |
|---|---|
| `modules/mirage-neoforge-compat/src/main/java/net/neoforged/fml/loading/FMLLoader.java` | 仅新增一个实例方法 `public Dist getDist() { return FMLEnvironment.getDist(); }` + import；同步更新类注释里「未实现」清单（去掉 `getDist()`） |
| `scripts/verify_fml_loader_dist.ps1`（新） | 守卫层（源码锚点/签名形态/真值源/最小边界）+ 行为层（真实 javac + JVM 反射调用）+ 变异层（落盘重编译、判红、字节级还原、基线回绿） |
| `docs/plans/2026-11-22_FMLLoader语义缺口.md`（新） | 本计划；收尾追加验证结果 |

> 明确不做：`MirageClassLoader.java`（并行会话独占）、`Dist.java` / `FMLEnvironment.java` / `FMLPaths.java`（已存在且语义够用）、`getGamePath()` / `getModsPath()` / 静态 `getDist()`（无真机证据、真实签名是实例方法）、`AGENTS.md` / `PLAN.md` / 三份台账 / `docs/经验与踩坑.md` / 其它 `verify_*`（主对话统一收尾）、`mc-shili/` 任何文件、fat jar 部署、GUI 实机。

## 每处改动要点

1. **FMLLoader.java**：新增 `import net.neoforged.api.distmarker.Dist;`，新增实例方法 `public Dist getDist()`，方法体 `return FMLEnvironment.getDist();`（同包，无需 import）。`FMLEnvironment` 的值由 `NeoCompatBootstrap.init` 在 S6 之前写入，因此客户端得到的就是 `CLIENT`；不做 `switch`、不做字符串比较、不返回新造枚举。类 Javadoc 中「未实现」列表移除 `getDist()`，其余条目原样保留。
2. **verify_fml_loader_dist.ps1**：
   - 守卫层：行首锚定 `public Dist getDist\(\)`、`return FMLEnvironment\.getDist\(\)`、`public static FMLLoader getCurrent\(\)`；禁止静态 `getDist`（静态会与真实 `invokevirtual` 形态不符）；禁止把 `Dist.CLIENT` 字面量写死在 `FMLLoader`（真值必须来自注入源）；禁止新增 `getGamePath/getModsPath` 等本轮不做的方法（防范围蔓延）。
   - 行为层：从生产源码真实 `javac` 编译（`FMLLoader` + `FMLEnvironment` + `Dist` + 少量纯 JDK 依赖），起一个 headless JVM 跑探针：反射查方法存在且 `Modifier.isStatic == false`、返回类型 `Dist`；`FMLEnvironment.init(Dist.CLIENT)` 后 `getCurrent().getDist() == Dist.CLIENT`；`FMLEnvironment.init(Dist.DEDICATED_SERVER)` 后 `getDist()` 跟随为真值（证明是委托而非硬编码）；`versionInfo()/isProduction()` 仍可用（无回归）。探针只输出 ASCII `KEY=VALUE` / PASS 汇总，脚本按 GBK 优先 / UTF-8 兜底解码。
   - 变异层：三种落盘变异（改返回类型为 `String`、改方法名为 `getDistX`、改成硬编码 `return Dist.CLIENT;`）——每个都在 `%TEMP%` 备份原文件字节 → 落盘修改 → 重编译 + 探针必须判红 → `try/finally` 字节级还原 → 编译路径自检（未变异走同一流程必须绿）→ 基线回绿。
3. **计划文档**：收尾追加「验证结果」小节，记门禁数字、fat jar 体积/类数、commit hash（由主会话补 hash 时可只留占位说明）。

## 回归测试影响面

- **可能被打破的旧行为**：`FMLLoader` 的既有静态方法与实例 `isProduction()` 不能变签名；`FMLEnvironment` 注入时序未动；`Dist` 枚举未动。新增实例方法只扩充符号表，不改变既有常量池解析。
- **门禁覆盖**：新门禁专测本轮语义；`verify_neo_ctor.ps1` 复测 N1 链接期 stub 与构造签名选择；`verify_neo_api_surface.ps1` 用真实 mod jar 差集确认零新增缺失；`verify_w3c_c1_events.py` 复测 29 个 clean-room 事件类（与 FMLLoader 无依赖，属安全网）。
- **零侵入模块**：启动器、核心 loader、mixin、MCL、事件总线、pack 资源、mdk-example 全部不动；`MirageClassLoader.java` 由并行会话改，本轮只 `git add` 三个独占文件。
- **mdk-example 冒烟步骤**：无变化；本轮不拉 GUI，实机验证仍由用户拍板。

## 验证步骤

以下全部是 headless（不创建窗口、不写 `mc-shili/`），可自行运行：

1. 基线：`gradle :modules:mirage-neoforge-compat:compileJava --no-daemon` 先确认当前源码编译过（改核心敏感区前的规则 2.4）。
2. 改完后跑新门禁：`powershell -ExecutionPolicy Bypass -File .\scripts\verify_fml_loader_dist.ps1`（应 PASS）；三个变异模式 `-Mutation no-method|wrong-return|hardcode` 应各自退出 1 且判红原因一一对应；无变异自检同路径应绿。
3. 回归：`powershell -ExecutionPolicy Bypass -File .\scripts\verify_neo_ctor.ps1`；`powershell -ExecutionPolicy Bypass -File .\scripts\verify_neo_api_surface.ps1 -Baseline ..\docs\neo-api-surface-baseline.json`（必须出现「相对基线无新增缺失」）；`python .\scripts\verify_w3c_c1_events.py`（36/36 基线）。
4. 出包：`gradle :launcher:mirage-launch:fatJar --no-daemon`；只认 fatJar 任务（**不要**跑会覆盖成瘦 jar 的 `gradle build`），核 `stat -c%s` 与 `unzip -l | ...` 的类数（当前基线 3 835 633 字节 / 2 897 entries，本轮仅加一个方法，类数应不变、体积微增）。
5. **GUI 实机不启动**：按 C93/C107，真机 JEI 是否过初始化、是否出现下一个缺口，写 `.bat` 由用户双击或直接给命令，AI 不拉 MC 客户端；本轮只交付 headless 证据。

## 合规边界（条件必填）

改动位于 `modules/mirage-neoforge-compat/` 并参照 `refs`/用户游戏目录中的 NeoForge jar 做签名观察。NeoForge / Forge 为 **LGPL 2.1**：本轮只做公开字节码签名观察（`javap`）、真机日志行为观察和 clean-room 接口实现；**不复制、不反编译移植、不引用其实现代码**。`getDist()` 仅委托 MG 自有的 `FMLEnvironment` 真值源，不含任何 NeoForge 实现逻辑。产物仍保持 MG 自有 MIT 授权边界。

## 回滚方案

- 本轮改动集中在 1 个生产方法 + 1 个新门禁 + 1 个计划文档。若行为层/变异层不过或回归出现新增缺失：`git revert <本轮 commit>` 即可；不影响前面 W3-C1 / MixinExtras 的已修内容。
- 保留的已修部分：`FMLLoader` 既有 `versionInfo/getCurrent/getCurrentOrNull/isProduction/getLoadingModList`、`Dist`、`FMLEnvironment` 注入链路全部不动，回滚不会碰到它们。
- 无 jar/文件级备份需求（fat jar 可由 gradle 重建）；未部署到 `mc-shili/`，因此不存在线上包回退。若真机出现新症状（例如 `getDist()` 返回错端），先查 `FMLEnvironment` 注入值，再决定是否单独回退本 commit，不静默 `reset --hard`。

## 验证结果（2026-11-22 收尾）

- **新门禁** `verify_fml_loader_dist.ps1`：**PASS 30 / FAIL 0**（守卫 11 + 行为 1 + 探针内部 8 + 变异/还原/自检 10）。JDK 25.0.4 实跑探针；三个落盘变异 `no-method` / `wrong-return` / `hardcode` 分别以 `METHOD_PRESENT` / javac 类型错误 / `DELEGATES_TO_ENVIRONMENT` 判红，源文件字节级还原，还原后编译路径逐次回绿。
- **回归**：`verify_neo_ctor.ps1` **42/42**；`verify_neo_api_surface.ps1` **PASS 1 / FAIL 0，相对基线零新增缺失**（另报已修复 15 个，均为 11-14 C1 的存量收益，本轮未改类集合）；`verify_w3c_c1_events.py` **RESULT PASS 36/36**。
- **出包对账**：`:launcher:mirage-launch:fatJar` SUCCESSFUL，jar **3 846 819 字节 / 2 903 entries**，`javap` 从包内确认 `public Dist getDist()` 已进包。相对 11-22 早前基线 3 835 633 / 2 897 的差额来自**并行会话**正在改的 `MirageClassLoader.java` / `MirageMixinBootstrap.java` 及新增 `ModMixinTargets.java` / `WeavePriority.java`（本轮零接触、未 add）；本轮自身只增加一个方法、零新增类。
- **边界**：未部署、未拉 GUI、未写 `mc-shili/`；JEI 能否真正越过初始化、下一个缺口是什么，仍由用户实机拍板。
