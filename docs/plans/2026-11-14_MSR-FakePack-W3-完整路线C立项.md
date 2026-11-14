# MSR FakePack · W3 完整路线 C 立项与范围裁剪（2026-11-14）

> 关联：W0 设计 `2026-11-13_MSR-FakePack接入真启动类加载器.md`（路线 C / 取舍表）、
> W3-C 子集 `2026-11-14_MSR-FakePack-W3-烘焙内置pack.md`、
> real-shape `2026-11-14_MSR-FakePack-real-shape增强.md`、
> N0–N4 `2026-11-07_NeoForge大规模测试前收口.md`；
> 上游 commit `3e4b601`（real-shape）/ `0b50db5`（补台账哈希）。

## 目标

把 W0 的「路线 C（正解）」从 W3-C 的**诊断子集**推进到「compat 源码真实现」的**可执行分期方案**：
量化范围、切出可独立验收的第一阶段、钉死假覆盖与合规边界。

**可验证标准**：本文交付 ① 一组可脚本复算的量化清单（114 / 6 / 108 / 859 / 34-25-49）；
② 三档分档口径与每档验收判据；③ 明确的「不做」清单。用户选定第一阶段范围后可直接开工，
不必再补调研。**本文及本轮不写任何 `.java`、不改 `.class` 资源、不改构建脚本、不出包、不部署、不拉 GUI。**

## 前置条件与阻塞项

- 工作区干净（HEAD `0b50db5`，已 push）；「进行中」表无他人 WIP 冲突；无需外网；
  真实形状源三件套在 `mc-shili/libraries/net/neoforged/` 本机只读可用（不进版本控制）。
- **输入清单错配（关键阻塞）**：W0 原文「与路线 A 天然衔接：**A 的清单就是 C 的输入**」。
  当前 114 类来自 P3 沙盒录制 + `scripts/verify_msr_real_mods.ps1` 的 6 个测试 mod 常量池差集，
  **不是真实 MC 客户端的全量缺口**。W1 已实现 `MissingNeoTracker`（`-Dmirage.msr.missingneo=on`
  + `.out`，只对 neo 记账仍抛 CNFE，清单可喂 `--only-file`），但**从未在真实客户端跑过**，真清单为空。
- 按 AGENTS 第 2 节启动判据，取真清单需要完整客户端启动（会拉 GUI）⇒ **须用户明确命令 / 双击 .bat**；
  AI 不自行拉 `java`。因此第一阶段只能按「离线可判定的语义簇」排序，**不得宣称覆盖全部缺口**。
- 合规：compat 层属 LGPL 2.1 clean-room 边界（见专节），实现不能从真实 jar 抄代码。

## 现状分析

### 硬数据（2026-11-14 实算，可复算）

| 项 | 值 |
|---|---|
| fat jar 内置 pack | **114 类 / 213 848 字节**，`net.minecraft.*` 类 0 个；fat jar 3 089 637 字节 |
| 类型分布 | 59 `class` + 26 `abstract` + 25 `interface` + 4 `enum`（annotation 0） |
| API 面 | **859 方法 / 57 字段**；66 个真 neo 超类、33 个真 neo 接口；零方法类 2 个 |
| compat 源码 | `modules/mirage-neoforge-compat/src/main/java/net/neoforged/**/*.java` **77 个** |
| 交集（已有 clean-room 实现） | **6**：`Event` / `ICancellableEvent` / `IConfigSpec` / `IModBusEvent` / `IConfigurable` / `IModFileInfo` |
| 待实现 | **108 类**（pack 有 UOE 骨架、compat 无 `.java`） |
| compat 有而 pack 无 | 71 个（说明 pack 只覆盖 6 mod 缺口，compat 覆盖面更广） |
| 重方法 TOP | `ModConfigSpec$Builder` 81、`IItemExtension` 47、`FluidStack` 45、`ItemResource` 36、`FluidResource` 35、`ModFileInfo` 19、`ClientTooltipFlag` 16、`ModConfigSpec` 16 |

> 口径注：W0 记「fat jar 根路径 141 个 neo stub」是 **class 数**，本表 77 是 **`.java` 源文件数**——
> 一个 `.java` 可含多个嵌套/内部类，两者不矛盾，不要拿来对账说少了 64 个。

### 为什么「逐个审」不能等价成「批量生成实现」

1. W0 取舍表里路线 C 的「假覆盖风险 = 无」前提是**逐个审**；批量把 UOE 换成编造返回值，
   反而制造 C84 型假覆盖——半实现比全 UOE 更危险：mod 拿到编造值会走错分支，且日志无异常。
2. N1 既有原则（收口计划 §N1）：**未知方法一律抛 `UnsupportedOperationException` 带类名+方法名，
   不返回编造值**。所以「有实现的 stub」本就是「链接形状对 + 关键方法有行为 + 其余显式 UOE」的
   **部分实现**，不是复刻 NeoForge 功能。
3. 108 类里 C 档 49 个集中在 `ScreenEvent` 家族 / `FluidStack` / `*Resource*` / `IItemExtension` /
   `RenderTooltip*` / `ModConfigSpec*`：这些需要真实 MC 状态、渲染管线与流体子系统，
   clean-room 半实现既不可靠也无验收口径 ⇒ 应明确列为**永久缺口**，而不是「以后补」。
4. 6 个交集类在 pack 里是**冗余 UOE 骨架**：`RuntimeFakePack` 的插入点在 `readClassBytes==null`
   之后，compat 根路径类天然优先，fallback 永远命中不到它们（C101 的探针正是因此才要跳过 compat root）。

### 自动分档口径（仅供排序，最终仍须逐个人审）

对 108 个待实现类按「方法数 + 名称关键词」粗分，复算方式见 §验证步骤：

| 档 | 口径 | 类数 | 方法数 | 代表 |
|---|---|---|---|---|
| **A** | 其余（事件载体 / 生命周期类 / 标记接口 / 枚举），方法数 ≤ 6 且不命中 B/C 关键词 | **34** | **110** | `FMLLoadCompleteEvent`、`ClientTickEvent`、`InputEvent`、`RegisterClientCommandsEvent`、`ClientStoppingEvent` |
| **B** | 方法数 7–19，或命中 `Capability` / `ModFileInfo` / `Config` / `RegisterEvent` / `Extension` / `ModLifecycle` / `ParallelDispatch` / `ModFileScanData` | **25** | **180** | `ModLifecycleEvent`、`ParallelDispatchEvent`、`BlockCapability`、`EntityCapability`、`ModFileInfo`、`KeyModifier` |
| **C** | 方法数 ≥ 20，或命中 `FluidStack` / `IItemExtension` / `*Resource*` / `ModConfigSpec` / `ScreenEvent` / `RenderTooltip` / `IClientItemExtensions` / `MutableDataComponentHolder` / `IPayloadContext` / `ClientTooltipFlag` | **49** | **549** | `ScreenEvent$*`、`RenderTooltipEvent*`、`FluidStack`、`ItemResource`、`ModConfigSpec$Builder` |

## 分期方案（补充节）

| 阶段 | 内容 | 输入 | 交付判据 | 状态 |
|---|---|---|---|---|
| **C0** | 立项 + 量化清单（本文） | 现有 pack / compat 源码 | 数字可复算、九节齐全、不做清单明确 | **本轮** |
| **C1（推荐第一阶段）** | A 档 34 类按 N1 风格 clean-room 落地到 `net/neoforged/**` 根路径源码 | 本文 A 档清单 + 三个形状源 jar（只核签名） | 新增门禁：DEFAULT（无 fallback）下 34 类由 compat 直接提供、真实继承形状一致、未支持方法抛 UOE 而非返回默认值；`compileJava`/`fatJar` 核包；变异检验全红；pack 与 fallback **一律不动** | 待用户拍板 |
| **C2** | 取真清单 → 重排 B 档优先级，25 类按需接 MG 现有桥 | W1 `MissingNeoTracker` 实机 `.out`（**须用户双击 .bat**） | 真清单落盘并喂 `--only-file`；每类标注接哪条现有桥（`ModConfigSpec`→`TomlConfigData`、`RegisterEvent`→`DeferredRegister`、`*Capability*`→现有 capability 桥） | 阻塞于实机 |
| **C3** | C 档 49 类明确不做，登记为永久缺口 | 本文 C 档 | 文档列明「为何不实现」；保留 UOE 骨架仅作诊断；**不得**以支持率名义回灌运行时 | 已裁决（不做） |
| **C4** | 完整客户端实机矩阵 | 用户明确命令 | 按 N4 矩阵口径，只报事实不判「跑通」（C93） | 未开始 |

每阶段独立 commit、独立回滚；C1 不通过时可整体 revert 而不影响 W3-C / real-shape 产物。

## 改动文件清单

| 文件 | 改什么 |
|---|---|
| `docs/plans/2026-11-14_MSR-FakePack-W3-完整路线C立项.md` | 新建：本文（九节 + 分期补充节） |
| `docs/改动登记表.md` | 「进行中」加本轮一行 |
| `docs/更新日志.md` | 顶部追加本轮条目 |
| `docs/经验与踩坑.md` | 追加 C103（范围量化 / 输入清单错配）与变更记录一行 |
| `../.workbuddy/memory/MEMORY.md`（仓外） | 结论句，不进 git |

> **明确不做**：`modules/mirage-neoforge-compat/src/main/java/**`、内置 pack 的
> `mirage/fakepack/**/*.class`、`core/.../RuntimeFakePack.java`、`MirageClassLoader.java`、
> 任何 `scripts/verify_*.ps1` / fixture、Gradle 构建脚本、fat jar、`mc-shili/` 下任何文件、
> 根目录启动脚本、`.gitignore`。也不新增 `.py`/`.ps1` 清单脚本——复算口径写在 §验证步骤，
> 保持本轮为纯文档轮。

## 每处改动要点

- **计划文档**：把 W0 一句「逐个审成有实现的 stub」展开成可排期的 5 阶段；把「114 类」拆成
  「6 冗余 + 108 待实现」，再拆成 A/B/C；明确 C 档永久不做而非待办。
- **登记表**：登记为「进行中 / 计划已出，待用户拍板第一阶段范围」，不预填 commit 哈希（规则 2.1）。
- **更新日志**：只记本轮文档范围与取证数字，不改历史条目；对 W3-C / real-shape 条目里
  「W3 完整路线 C 待做」的表述不删，按「只标不删」在本文标注仍在推进。
- **经验 C103**：记录「用户说做 X 时，先用常量池/源码求差集量化 X 的真实规模；输入清单的来源
  （沙盒 vs 实机）比数量更决定可行性」，以及「`.java` 源文件数 ≠ class 数」的口径坑。

## 回归测试影响面

**本轮零代码影响**：现有门禁 `verify_fakepack_w3_c.ps1` 33/33、
`verify_fakepack_realshape.ps1` 29/29、`verify_fakepack_wiring.ps1` 90/90、
W1 变异 7/7、P4 四模式 37/48/52/45、p2 60/60、p3 53/53、real_mods 27/27、
W2 10/10 全部不受影响，无需重跑。

**C1 阶段可能打破的旧行为（提前登记，实施时必须覆盖）**：

1. 新增 34 个根路径 `net/neoforged/**` 类会与内置 pack 同名 ⇒ `W3CBuiltinProbe` 的
   `listRootNeoClasses` 跳过集合变大，**DEFAULT 的「仅 6 个 compat 原生可加载」断言会红**，
   `B_HITS` 下降属预期。必须同步改 w3_c / realshape 门禁的口径，否则又是 C101 型假绿。
2. `MirageClassLoader.readClassBytes` 会优先命中根路径类 ⇒ fallback 永不服务这 34 类，
   这是路线 C 的目的，但要断言「命中数下降 ≠ fallback 失效」（保留 kill switch 与横幅）。
3. `NeoCompatBootstrap` 的 neo 静态态唯一性（W0 §1.5）：新类必须由 MCL 加载，
   **不得**落到 app loader，否则出现两份静态态 → LinkageError。
4. 新增类若被 `verify_neo_api_surface.ps1` 的基线收录，API 表面数字会变，需同步基线并注明口径。

**零侵入**：Fabric 兼容层、事件总线、注册表桥接、早期窗口、加载界面、性能脚本、mdk 冒烟步骤。

## 验证步骤

1. **复算清单（headless，可自行启动）**：对 114 个 `.class` 解析 constant pool / access_flags /
   `methods_count` / `fields_count`，与 `net/neoforged/**/*.java` 求差集；口径：
   `ACC_INTERFACE=0x0200`、`ACC_ANNOTATION=0x2000`、`ACC_ENUM=0x4000`、`ACC_ABSTRACT=0x0400`；
   判「已有源码」用 `res/mirage/fakepack/<rel>.class` → `java/<rel>.java` 的同路径映射。
   预期输出：`TOTAL 114`、`METHODS_TOTAL 859`、`FIELDS_TOTAL 57`、`ALREADY_HAS_JAVA_SRC 6`、
   `NO_JAVA_SRC 108`、`NEO_SUPER 66`、`NEO_ITF 33`。
2. **纯文档轮按规则 2 豁免 `gradle build`**；不跑 `fatJar`、不部署、不碰 `mc-shili/`。
3. **不涉及任何 GUI 启动**。C2 取真清单与 C4 实机矩阵都需完整客户端：按 AGENTS 第 2 节
   **优先不启动，写好带 `-Dmirage.msr.missingneo=on` 的 `.bat` 交用户双击**，AI 不直接拉 java。
4. 收尾 `git status --short` 确认只暂存本轮 4 个仓库内文档，提交后 push。

## 合规边界（条件必填）

涉及 `modules/mirage-neoforge-compat/` 与本机 NeoForge 26.2.0.90 三件套 jar。
NeoForge / Forge 为 **LGPL 2.1**：C1/C2 只做**行为观察 / 接口签名枚举 / clean-room 实现**，
**不抄其实现代码、不把反编译产物写进仓库**；三个 jar 仅用于核对类名、包名、方法签名、
继承形状与 enum 常量，只读、不进版本控制。新增 stub 的 Javadoc 注明 clean-room 来源边界
（沿用 N1 既有做法）。`THIRD_PARTY_NOTICES.md` 的授权口径本轮不改变。
若 C2 需要理解某方法语义，以**实机观测日志 + 公开 API 文档**为依据，不以反编译实现为依据。

## 回滚方案

本轮纯文档：`git revert <本轮 commit>` 即可全部撤回，无 jar / 文件级备份，
不影响已 push 的 `3e4b601`（real-shape）与 `0b50db5`（W3-C 收尾）。
C1 阶段按单阶段 commit 整体 `git revert`，保留 C0 文档与 W3-C / real-shape 产物；
若 C1 只坏在个别类，优先按规则 6「补全/修复」而不是连坐回退整阶段。
回滚后按规则 6 在 `docs/` 下写回退报告。
