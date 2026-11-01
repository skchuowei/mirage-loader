# P1-13 修正版：mod 自带 mixin config 真正生效（注册名修复 + 声明字段消费 + 子目录支持）

> 关联：`docs/Fabric兼容层-功能模块与实现说明.md`（2026-11-01 盘点，其中 §5.3-11 结论被本轮修正）、`docs/Fabric兼容层缺口清单.md` P1-13、`docs/plans/2026-10-31_AG-总计划重梳理.md` F 系问题清单

## 目标

让真实 Fabric mod jar 内的 mixin config **真正被 Mixin 引擎解析并织入**。

现状的残酷事实（本轮字节码取证）：TransformStage 从 LS 拆分起就在扫描并注册 mod 的 `*.mixin.json`，但注册名被**去掉了 `.json` 后缀**，而 Sponge Mixin 0.8.7 的 `Config.create` 对传入名**原样**调 `getResourceAsStream`、查不到抛 `IllegalArgumentException` 并被 `Mixins.createConfiguration` 静默吞掉——**mod mixin config 从初始提交起就一个都没加载成功过**，且没人发现（Mixin 的报错走它自己的 logger，不进 Mirage 日志）。

**可验证标准**：
1. 离线：新回归脚本 `scripts/verify_mod_mixin_config.ps1` 纯 JVM 全绿 + 变异检验变红；
2. 部署后用户双击 bat，`logs/mirage-launch.log` 出现 `后处理 mixin config: <mod的config>.json`（证明 mod config 资源真的被 Mixin 读到并走了 service 后处理分支——该分支此前也从未触发过）；
3. `gradle build` 全绿，fat jar 内含新增类。

## 前置条件与阻塞项

- 基线 `gradle build` 正在跑（本轮开跑前提交 `398717f`，工作区另有 Forge 式界面/早期窗口/LS 阶段类的未提交 WIP）。
- **WIP 边界**：本轮要改的 `TransformStage.java` 在 WIP 列表里（未提交改动仅一行 `MirageDebug.setPhase(2)`，与 mixin 无关）——只叠加本轮改动，**不触碰、不回退**该文件既有 WIP 内容，提交时注明同文件混有他人 WIP（与既有"留工作区随批收口"先例一致的处理：本轮代码不单独 commit 该文件？→ 决策：**照常提交**，diff 中只有本轮 mixin 相关行 + 既有的 setPhase 行会一并进去。为避免替别人提交，采用**先问自己的判据**：setPhase 行是 F1/界面 WIP 的收尾遗留，登记表 2026-10-31 条目已标"留工作区随那批提交"——本轮把它一并提交会让那批 WIP 部分收口但批次不完整。**处置：TransformStage 的提交用 `git add -p` 不可行（非交互），改为：本轮先只提交非 WIP 文件，TransformStage 的 mixin 修改与其他 WIP 文件一样留工作区，登记表注明**。若用户希望立即全部收口，另行指示。）
- 无其他阻塞。

## 现状分析（代码 as-is）

链路与证据：

1. `TransformStage.execute`（lifecycle-starter，已提交）：对每个 mod jar 枚举 zip entry，**只收根目录**（`!name.contains("/")`）的 `*.mixin.json`，去掉后缀存为 `xxx.mixins`；最后 `add("mirage.mixins.json")`（带全名）→ `MirageMixinBootstrap.init(mcl, configs)`。
2. `MirageMixinBootstrap.init`：逐个 `env.addConfiguration(c)` → 反射强制 `processor.select(env)`。
3. Mixin 0.8.7（javap 取证，`$TEMP/mixinprobe/`）：
   - `Mixins.addConfiguration(String)` → `createConfiguration` → `Config.create(name,...)`；
   - `MixinConfig.create`：`getResourceAsStream(name)` **原样查**，null → `IllegalArgumentException("The specified resource '%s' was invalid or could not be read")`；
   - `Mixins.createConfiguration`：**catch 该异常**，打 `Error encountered reading mixin config <name>: ...` 后 return——不中断其他 config。
4. `MirageMixinService.getResourceAsStream`：对 `endsWith(".mixin.json")` 的名字做后处理（剥 `compatibilityLevel`/`mixinextras`）——因 mod config 查询名不带后缀，**该分支对 mod config 从未触发**；而"剥 compatibilityLevel"是 Java 25 下解析 mod config 的硬前提（0.8.7 拒绝 JAVA_17+ 声明），后处理不触发 ⇒ 即使名字对了、config 带 compatibilityLevel 声明也会解析失败。
5. `FabricModManifest`：**没有** `mixins` 字段；`entrypoints` 用 `List<JsonElement>` 防御式解析（先例）。fabric.mod.json 的 `mixins` 数组元素可为字符串或对象（`{"container":..,"file":..}`），Gson 直读 `List<String>` 遇对象元素会抛 `JsonSyntaxException` 导致**整个 manifest 解析失败、mod 被跳过**——必须沿用 JsonElement 防御。
6. `ModContainer`：字段全 final + 5 层构造重载；`fromNeoForge` 是可写字段先例。

## 改动文件清单

| 文件 | 改什么 |
|---|---|
| `core/mirage-loader/.../FabricModManifest.java` | 新增 `public List<JsonElement> mixins;`（防御式）+ `List<String> mixinConfigPaths()` helper（取 primitive，过滤/WARN 对象元素） |
| `core/mirage-loader/.../ModContainer.java` | 新增可写字段 `public List<String> fabricMixinConfigs;`（null=非 fabric 来源；仿 fromNeoForge 模式，不动构造链） |
| `core/mirage-loader/.../ModDiscoverer.java` | fabric 分支：构造 ModContainer 后 `c.fabricMixinConfigs = fm.mixinConfigPaths()` |
| `core/mirage-loader/.../mixin/ModMixinConfigs.java`（新） | **纯逻辑收集器**：`collectOne(label, declared, rootEntries, allEntries)` → 本 mod 要注册的资源路径列表（声明优先+根目录兜底+统一 .json 后缀+去重+缺失清单）；`collectAll` 全局合并去重。零依赖（只用 JDK），可离线 javac 断言 |
| `lifecycle-starter/.../stage/TransformStage.java` | 收集循环改为：一次 zip 枚举同时收 root `*.mixin.json` 与 all entries → 调 `ModMixinConfigs.collectOne`（declared 来自 `mod.fabricMixinConfigs`）→ 全局合并后**以完整资源路径**注册（如 `mixins/deep.json`、`xxx.mixins.json`） |
| `scripts/verify_mod_mixin_config.ps1`（新） | 纯 JVM 回归：javac 现场编译 `ModMixinConfigs`+检查程序，断言收集/规范化/去重/缺失告警 + 源码守卫（TransformStage 必须调收集器、注册名必须以 .json 结尾、FabricModManifest 必须有 mixins 字段）+ 变异检验 |

> 明确不做：①`fabric.mod.json` `mixins` 的对象形态（`{"container","file"}`）只 WARN 不支持；②refmap 处理（26.2 无混淆，暂不需要）；③`MirageMixinBootstrap`/`MirageMixinService` 的注册与后处理逻辑本体（名字修对后自然生效）；④neoforge/forge 侧 mixin（不同描述符体系）；⑤20+ 回调类（P0-2 另立）；⑥不动 `ModuleRegistry`、不动 G9。

## 每处改动要点

1. **`ModMixinConfigs.collectOne`**（核心纯逻辑）：
   - 输入：`modLabel`（日志用）、`declared`（manifest mixins 声明，可空）、`rootEntries`（jar 根目录 `*.mixin.json` 集合）、`allEntries`（jar 全部 entry 名集合）；
   - 声明路径规范化：不以 `.json` 结尾 → 补 `.json`；在 `allEntries` 命中 → 收；否则尝试 `rootEntries` 命中（声明省略目录的场景）；再否则记入缺失清单（调用方 WARN）；
   - 兜底：`rootEntries` 中未被声明覆盖的 → 收（保持既有盲扫行为，兼容不声明字段的 mod）；
   - 去重保序（LinkedHashSet）；返回 `Result`（configs + missing）。
2. **`TransformStage`**：zip 枚举一次，`rootEntries`/`allEntries` 两个 Set；declared 来自 `mod.fabricMixinConfigs`（null 视为空）；每 mod 打 debug（含 registered/missing 数）；`missing` 非空时 WARN 指名 mod 与文件。
3. **注册名**：一律完整资源路径（带 `.json`）——这是修复本体；`mirage.mixins.json` 保持不变。
4. **`FabricModManifest.mixinConfigPaths`**：`mixins` 为 null → 空表；元素 `isJsonPrimitive` && `getAsString` 非空 → 收；对象元素 → WARN 跳过（调用方带 mod 名）。

## 回归测试影响面

- **行为变化**：mod mixin config 从"注册必失败"变"注册成功"——已注册进 Mixin 环境后，若 mod 的 mixin 目标类后续被加载且注入失败，会走 `MirageMixinBootstrap.transform` 的 FATAL 判定（2026-10-31 起 InjectionError 升级致命）。**风险评估**：Fabric 官方生态 mod 用 intermediary 映射名（`class_1234`），这些目标类在 Mirage 的 26.2（Mojang 名）环境**根本不会被加载**，因此不会触发 transform、不会 FATAL；真正可能炸的是"以 Mojang 映射重编译的 Fabric mod"（少数）——其 mixin 现在第一次真的会跑，写错的会 FATAL。这正是 FATAL 政策的预期行为（宁响亮死不静默坏），但要在已知限制里写明。
- service 后处理分支首次对 mod config 生效：config JSON 的 `compatibilityLevel`/`mixinextras` 被剥离——与 Fabric 官方 loader 行为差异（官方用自己的 mixin fork 支持），本项目 0.8.7 upstream 的既定兜底。
- `gradle build` 必须全绿；`verify_mixin_registry.ps1`（126 断言）不受影响（mirage.mixins.json 未动）；`verify_module_loading.ps1` 不受影响。

## 验证步骤

1. `scripts/verify_mod_mixin_config.ps1`：全绿 + **4 种变异检验全红**（①collectOne 丢掉缺失检测 ②去掉 `.json` 补全 ③去掉根目录兜底 ④TransformStage 改回无后缀注册名——用源码守卫断言）；
2. `gradle --no-daemon build` 全绿；`:launcher:mirage-launch:fatJar` 出包后 `jar tf` 核对 `ModMixinConfigs.class`、`FabricModManifest` 等新增/改动类在包内；核对 ~2.6-2.9MB；
3. 部署到 `mc-shili/libraries/io/mirage/mirage-launch/0.1.0/`（先备份旧版 `.bak-20261101-modmixin`）；
4. **实机**（虚拟桌面规则，用户双击 bat，AI 不直接拉 java）：放一个带 mixin 的测试 mod 后看 `logs/mirage-launch.log`：
   - `后处理 mixin config: <name>.json` 出现 ⇒ config 真被 Mixin 读到；
   - 无 `Error encountered reading mixin config`；
   - 既有 16 类 `[Fabric] xxx fired` 不回归。
5. 冒烟 mod 来源与清理按 AGENTS.md「测试 mod 获取规则」执行。

## 合规边界

不涉及 LGPL 代码：Sponge Mixin（MIT）与 fabric-loader/fabric-api（Apache 2.0）仅通过已发布 jar 的**运行时行为**（javap 反汇编公开字节码）取证，未参考 NeoForge/Forge 源码。

## 回滚方案

- `git revert` 本轮 commit 即可整体回退（改动集中、无数据迁移）；
- 部署侧还原 `.bak-20261101-modmixin` 备份 jar；
- 回退后回到"mod mixin config 注册必失败"的现状——注意**不是**回到某个更好的中间态；
- 按规则 6 写回退报告。
