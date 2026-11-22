# MG 集成 MixinExtras 0.5.5

> 关联：无上游计划（用户直接需求「MG加MixinExtras」）。背景基线：`docs/2026-11-22_转向C前的现状梳理.md`
> （终极目标＝全部加载器 mod 全部互通；D1「内嵌 SpongePowered Mixin」保留）。
> 取证来源：本计划 §现状分析 引用的 2026-11-22 headless 探针（`%TEMP%`，未进仓库）。

## 目标

让 MG 加载器在运行时提供 **MixinExtras 0.5.5**：mod mixin 里使用的 MixinExtras 注入器
（`@ModifyReturnValue` / `@WrapOperation` / `@ModifyExpressionValue` / `@WrapWithCondition` /
`@ModifyReceiver` / `@Local` 等）在 MG 下真实织入生效；mod mixin config 的
`mixinextras.minVersion` 声明不再被剥掉，并按选定策略校验。

**可验证标准**（done 判据）：
1. headless 门禁 `scripts/verify_mixinextras.py` 全绿（行为层走真实 `MirageClassLoader` +
   `MirageMixinBootstrap.init` 链路，日志出现探针标记字符串 + `RESULT PASS`）；
2. 变异检验全红 + 基线回绿（C104 规矩）；
3. fat jar 内含 `com/llamalad7/mixinextras/MixinExtrasBootstrap.class`，类数/体积对账记录进更新日志；
4. `gradle build` 全绿，既有门禁抽样回归不红。

## 前置条件与阻塞项

无阻塞。工作区干净（仅用户未跟踪的 `scripts/run_w3c_diag2.bat`，不碰）；
HEAD `e7eeeda`（main == origin/main）。

**已完成的关键前置取证（2026-11-22，headless 探针，产物在 `%TEMP%/mexspike` 不进仓库）**：
- MG 实际运行的 Mixin 是 **`org.spongepowered:mixin:0.8.7`**（fat jar 内 `MixinEnvironment.class`
  md5 与 gradle cache 的 mixin-0.8.7.jar 逐字节一致；`mirage-deps/` 里的 mixin-0.8.5 /
  sponge-mixin-0.15.3+mixin.0.8.7 被 `duplicatesStrategy=EXCLUDE` 的先来先得规则盖掉，不生效）。
- MixinExtras 0.5.5 字节码引用的 **64 个** `org.spongepowered.asm.*` 类在 mixin-0.8.7 里
  **全部存在**（类级兼容）；`mixinextras-versions` 包自带
  `MixinVersionImpl_v0_8 / _v0_8_3 / _v0_8_4 / _v0_8_6 / _v0_8_7` 兼容层，0.8.7 在官方支持面上。
- 探针实跑（fat jar + `mixinextras-neoforge-0.5.5.jar` + guava）：`MixinExtrasBootstrap.init()`
  返回 version=0.5.5，`@ModifyReturnValue`（value()=42）与 `@WrapOperation`（calc(-7)=107）
  **均真实织入并触发**，RESULT PASS。即 **0.5.5 on 0.8.7 可行，无需动 Mixin 版本**。
- **init 时序硬约束**：`MixinExtrasBootstrap.init()` 必须在
  `env.setActiveTransformer(transformer)` **之后**调用（内部
  `MixinInternals.getExtensions()` 取 `getActiveTransformer().getExtensions()`，
  过早调用抛 NPE），在 `addConfiguration` 之前调用即可。
- 构件选择：Maven Central `io.github.llamalad7:mixinextras-common:0.5.5`（725 KB，
  pom 无依赖、含完整库 + AP service 条目；平台 glue（platform.forge/neoforge）不需要）。
  README「Any other platform」指引 relocate——**MG 反着做：不 relocate**，理由见 §改动文件清单。
- 顺带发现的存量 bug（探针复现）：`MirageMixinService.getResourceAsStream` 的后处理只匹配
  `name.endsWith(".mixin.json")`（**单数**），mod 常见的 `*.mixins.json`（**复数**）整个绕过
  剥离 ⇒ 带 `compatibilityLevel: JAVA_21` 的 config 直接
  `MixinInitialisationError: The requested compatibility level JAVA_21 could not be set`。
  本次顺带修。

## 现状分析

- `core/mirage-mixin`（核心敏感区，26 类）：`MirageMixinBootstrap.init(mcl, configs)` 完成
  `MixinBootstrap.init → setActiveTransformer → addConfiguration → 反射触发 processor.select`；
  **没有任何 MixinExtras 初始化**。`MirageMixinService.getResourceAsStream` 对 `*.mixin.json`
  做后处理：剥 `compatibilityLevel`（0.8.7 在 Java 25 下拒绝 JAVA_17/21 声明）+ **剥
  `mixinextras` 键**（注释「Sponge Mixin 0.8.7 不支持 MixinExtras」——这条注释过时了：
  0.8.7 不认这个键只是 Gson 忽略未知字段，真正的障碍是没人为 MG 初始化 MixinExtras）。
- `mixinextras` 键语义（0.5.5 自带 `com.llamalad7.mixinextras.config.MixinExtrasConfig` +
  `utils.MixinConfigUtils.requireMinVersion`，经字节码取证）：声明
  `"mixinextras": {"minVersion": "0.5.0"}` 表示「本 config 的注入器需要 ≥ 该版 MixinExtras」，
  MixinExtras 自己会在 expression 注入点用到时校验。MG 当前直接把键删掉 ⇒ 连这个校验也
  失效，等于「声明被无视」。正确做法是保留键 + 加载期前置校验（策略见 §每处改动要点）。
- 类加载可见性（分析结论，行为层门禁按真实链路验证）：`MirageMixinBootstrap`
  （`io.mirage.*` → app loader）调 init → 注册进 **app loader 副本**的 Mixin
  `InjectionInfo` 注册表，与实际做转换的 transformer（同一副本）一致；织入后的 MC 类
  由 mcl defineClass，其合并进来的 handler 里对 `com.llamalad7.mixinextras.*` 的引用
  经 mcl `loadClass`（parent=platform 查不到 → `findClass` → mcl URL 列表含 fat jar）
  解析出 mcl 副本，自洽。双副本与现有 gson/asm 同形态，无跨副本静态状态交接
  （`io.mirage.api` 双份 LinkageError 那类问题的前提是同一接口被两边互相传对象，
  这里不存在）。若行为层门禁发现 loader constraint 问题，回退方案见 §回滚方案。
- 与转向 C 的关系：D1（内嵌 Mixin）保留、统一调度层仍需要织入；MixinExtras 是
  Fabric/NeoForge/Forge 三生态 mod 的公共依赖（NeoForge 26.1.2.14 起内置 0.5.4，
  Fabric loader 0.17.0 起内置 0.5.0），**互通目标下 MG 提供它是必做项**。许可证 MIT，
  不碰 LGPL 合规边界。

## 改动文件清单

| 文件 | 改什么 |
|---|---|
| `gradle/libs.versions.toml` | 新增 `mixinextras = "0.5.5"` 与 `mixinextras-common = { module = "io.github.llamalad7:mixinextras-common", version.ref = "mixinextras" }` |
| `core/mirage-mixin/build.gradle.kts` | `implementation(libs.mixinextras.common)`（经 launcher `runtimeClasspath` 自动进 fatJar，无需改 launcher） |
| `core/mirage-mixin/.../MirageMixinBootstrap.java` | `init()` 里 `env.setActiveTransformer(transformer)` 之后、config 注册循环之前，调 `com.llamalad7.mixinextras.MixinExtrasBootstrap.init()`；try/catch 包住，失败 `MirageLog.MIXIN.warn` 不阻断启动；成功打版本号日志 |
| `core/mirage-mixin/.../MirageMixinService.java` | `getResourceAsStream` 后处理：① 后缀匹配从仅 `.mixin.json` 扩为 `.mixin.json` **与** `.mixins.json`（`mirage.mixins.json` 豁免不变）；② **删除剥 `mixinextras` 键的正则**；③ 新增 minVersion 前置校验（正则/Gson 取 `mixinextras.minVersion`，与 `MixinExtrasBootstrap.getVersion()` 比较，不满足按拍板策略处置，满足则 INFO 记一笔）；④ `compatibilityLevel` 剥离保持不变 |
| `scripts/verify_mixinextras.py`（新） | 回归门禁：守卫层 + 行为层 + 变异层（C104） |
| `scripts/fixtures/mixinextras/`（新） | 探针夹具：target 类 + 2 个 MixinExtras mixin（`@ModifyReturnValue`/`@WrapOperation`，handler 打唯一标记）+ `*.mixins.json`（带 `mixinextras.minVersion` 与 `compatibilityLevel` 各字段） |
| 台账 | `docs/更新日志.md`、`docs/改动登记表.md`、`docs/经验与踩坑.md`（新 C 条）、仓外 `MEMORY.md` / `2026-11-22.md` |

> **明确不做**：
> 1. **不 relocate** `com.llamalad7.mixinextras`（README 对「其他平台」建议 relocate 是防
>    mod JiJ 自带副本撞名；MG 的 mod 是扁平 jar、按原包名链接，relocate 会让所有 mod 二进制
>    找不到类）。mod 自带 MixinExtras 副本的版本仲裁/JiJ 不做——记已知限制。
> 2. 不动 Mixin 版本（继续 0.8.7）、不动 `mirage-deps/`、不动 `mirage.mixins.json` 自身。
> 3. 不引入 platform.forge / platform.neoforge glue（`mixinextras-common` 本来就没有）。
> 4. **MDK / gradle-plugin 编译期暴露（mod 开发者注解处理器）不在本轮**——待拍板后另开小轮。
> 5. 不部署 fat jar 进 `mc-shili/`、不拉 GUI、不写 `mc-shili/`；实机验收由用户拍板。
> 6. 不动 mods 目录、不动 `run_w3c_diag2.bat`（用户文件）。

## 每处改动要点

1. **依赖与打包**：`mixinextras-common:0.5.5` 进 `core/mirage-mixin` 的 `implementation`，
   launcher fatJar 走 `configurations.runtimeClasspath` 自动 shade（与 mixin/asm 同路径）。
   fat jar 里 `META-INF/services/javax.annotation.processing.Processor` 会被带进来——运行期
   无害（AP 只在编译期被 javac 消费）。出包后核对：
   `unzip -l` 见 `com/llamalad7/mixinextras/MixinExtrasBootstrap.class` 等、体积约 +700 KB、
   类数增量记录。
2. **`MirageMixinBootstrap.init`**：插入点必须是 `env.setActiveTransformer(transformer)` 之后
   （探针实证：早于此 NPE `getActiveTransformer().getExtensions()`）。直接编译期调用
   `MixinExtrasBootstrap.init()`（同模块已依赖），`catch (Throwable)` 记 warn 继续——
   理由：万一未来 fat jar 被裁掉该库，MG 仍能启动（只是 MixinExtras 注入器失效），
   与「关键织入失败才致命」的现行分级一致；成功路径 INFO 打
   `MixinExtras 0.5.5 已初始化`（门禁锚点）。
3. **`MirageMixinService` 后处理**：
   - 后缀：`(name.endsWith(".mixin.json") || name.endsWith(".mixins.json")) && !name.equals("mirage.mixins.json")`；
   - `compatibilityLevel` 剥离逻辑原样保留（0.8.7 限制仍在）；
   - **删掉** `,?\s*"mixinextras"\s*:\s*\{[^}]*\}` 这条剥离——键要留给 MixinExtras 自己
     `MixinConfigUtils` 读（它经 `MixinService.getResourceAsStream` 复读同一资源）；
   - minVersion 前置校验：正则抽 `"mixinextras"\s*:\s*\{[^}]*"minVersion"\s*:\s*"([^"]+)"`，
     与 `MixinExtrasBootstrap.getVersion()`（如 "0.5.5"）做数字段比较（或用
     `com.llamalad7.mixinextras.service.MixinExtrasVersion` 枚举 API，实现时 javap 择优）；
     不满足 → **按用户拍板策略**（默认提案：启动致命 + 明确报「mod/config 名、要求版本、
     内置版本」——与 MirageMixinBootstrap「宁可响亮地死不要静默地坏」一致；备选：跳过该
     config + ERROR / 仅 WARN）。满足 → INFO 记「config X 要求 mixinextras ≥ Y，满足」。
4. **门禁 `scripts/verify_mixinextras.py`**（Python，C104 全套）：
   - **守卫层**（内存副本，不动盘）：锚点断言——toml 有 0.5.5；mirage-mixin build 有依赖；
     `MirageMixinBootstrap` 的 init 调用行在 `setActiveTransformer` 行之后（行序断言）；
     `MirageMixinService` 不再含剥 `mixinextras` 的正则、后缀匹配含 `.mixins.json`、
     `mirage.mixins.json` 豁免仍在；fat jar 含 `MixinExtrasBootstrap.class`。
   - **行为层**（真实链路）：javac 编译夹具（target + 2 mixin + json，json 里
     `compatibilityLevel: JAVA_21` + `mixinextras.minVersion`），以 fat jar 为 classpath 用
     `MirageClassLoader`（parent=platform，与 TransformStage 同构造）+ `MirageMixinBootstrap.init`
     跑 transform → defineClass → 调方法，断言两条标记字符串出现 + 返回值被改写 +
     minVersion 满足时的 INFO 出现；另跑一个「minVersion 高于内置」负向用例断言按策略处置。
   - **变异层**（落盘重编译 + `try/finally` 字节级还原 + 编译路径自检 + 基线回绿）：
     ① 注释掉 `MirageMixinBootstrap` 的 init 调用行 → 行为层标记不出现、门禁红；
     ② 把后处理改回剥 `mixinextras` 键 → minVersion 负向/键保留断言红；
     ③ 后缀匹配退回仅单数 → `.mixins.json` 用例红。变异只重编译 `core/mirage-mixin`
     单模块 class 并打进 fat jar **临时副本**（不整仓 gradle build，控制时长）。
5. **日志/台账**：更新日志记 fat jar 前后体积/类数；经验与踩坑新增一条（init 时序约束 +
   `.mixins.json` 后缀坑 + 双副本分析结论）；MEMORY.md 记结论句。

## 回归测试影响面

- **行为变化点**：`*.mixins.json`（复数）从「完全绕过后处理」变为「走后处理」——
  以前这些 config 的 `compatibilityLevel` 会炸（探针复现），现在被剥离，属**修复**；
  但若有 config 依赖旧绕过行为（如靠 `compatibilityLevel: JAVA_8` 精确控制的），会被剥掉
  字段——Mixin 0.8.7 默认级别 JAVA_8，语义等价，风险低。
- `mixinextras` 键不再被删：带键的 config 多一条 INFO/校验日志，无注入行为变化（键本身
  Mixin 0.8.7 Gson 忽略）。
- fat jar 体积 +~700 KB（3 109 057 → 约 3.8 MB），所有引用 fat jar 尺寸断言的门禁
  （`verify_fakepack_w3_c.ps1` 等记录体积的）需确认是**记录**还是**阈值**——是阈值的同步。
- 旧门禁抽样回归：`verify_fakepack_w3_c.ps1` 快速档、`verify_w3c_c1_events.py`、
  `verify_bat_launcher.py`（本轮不动 bat，预期不动自绿）、wiring 门禁。
- mdk-example 冒烟不变（本轮不碰 MDK）。

## 验证步骤

1. 改动前基线（规则 2.4，核心敏感区）：`gradle :launcher:mirage-launch:fatJar` 确认编译过。
2. 改完 `gradle build` 全绿；`gradle :launcher:mirage-launch:fatJar` 出新包。
3. fat jar 对账：`unzip -l` 查 `com/llamalad7/mixinextras/` 类数、体积、
   `META-INF/services` 无意外丢失；javap 抽查 `MirageMixinBootstrap` 字节码含 init 调用。
4. `python scripts/verify_mixinextras.py` 全绿 + 变异全红 + 基线回绿。
5. 既有门禁抽样（§回归测试影响面所列）。
6. **全部 headless**（探针/门禁/gradle，C107 授权内）；不部署 fat jar、不拉 GUI、
   不写 `mc-shili/`。实机（部署新 fat jar + 拉客户端）留用户命令后另行验收。

## 合规边界（条件必填）

不涉及 LGPL 代码。MixinExtras 为 **MIT**（`io.github.llamalad7:mixinextras-common`），
不引用 `refs/neoforge/`、`refs/forge/` 源码，不引入 NeoForge/Forge 运行时；
本轮与 `modules/mirage-neoforge-compat/`、`modules/mirage-forge-compat/` 无关。

## 回滚方案

- 单 commit 落地，`git revert` 即整体回滚（含 toml/构建/两 java/门禁）；
- fat jar 重打回旧版即恢复运行时（部署本来就是旧包，未部署则零回滚动作）；
- 门禁与夹具是新增文件，revert 后无残留；
- 若行为层发现类加载双副本 LinkageError（分析认为不会，但留口）：把
  `com.llamalad7.mixinextras.` 加进 `MirageClassLoader.loadClass` 的 app-loader 强制委派
  名单（与 `io.mirage.*` 同款处理），一次改动即可收敛为单副本——作为修复项追加，
  不需要整体 revert。
