# Lifecycle Starter (LS) — 把 MG+AG 拆分为独立模块、由 LS 决定启动时做什么

> 关联：`docs/agents/A1-Spec/MG-Lifecycle.md`（S1–S7）、`docs/MG-设计脉络与命名体系.md`（AG/MG 分离）、`docs/MG-现状摸底与规划修订.md`
> 本计划只定义方向与拆分方案，不直接动代码。

## 设计原则（来自 Forbrick 失败教训，LS 必须遵守）
1. **MG 是唯一运行时**：AG 只是编译期转译器，不参与运行时；禁止多加载器同进程硬缝（Forge/NeoForge/Fabric 不嵌套）。
2. **严格按设计的阶段顺序执行**：LS 的阶段顺序由 `MG-Lifecycle.md` 冻结，禁止"试错调顺序"、禁止为适配特定 mod 反复改启动时间表。
3. **禁止静默失败**：所有运行时错误必须记录并分级（MEL）；FATAL 立即崩溃，不静默跳过、不兜底调用废弃类。
4. **AG 只产出 MID，MG 只消费 MID**：通过 MID 格式解耦，MG 不关心 mod 来源；AG 不碰运行时状态。
5. **接口先冻结**：LS 接口（`LS-Interfaces.md`）由 A1/MIS 冻结后，各模块按接口实现，禁止各写各的。

## 目标
把 Mirage Loader 从"单体 Launcher + 嵌入式 compat"重构为：**MG（运行时）与 AG（转译器）拆成独立可维护模块，由一个新的 Lifecycle Starter (LS) 在启动时决定当前该干什么（发现/解析/转译/加载/重放）**。LS 是唯一入口，MG 与 AG 各自独立维护、独立发布，通过 MID 格式解耦。

可验证标准：`gradle build` 全绿；启动时 LS 能按模式（native-only / transpile-then-load / sandbox-record）分支执行；MG 与 AG 模块可独立编译不互相依赖。

## LS 周期（Lifecycle Cycles）

LS 不止"启动一次游戏"，而是按周期模板决定"这一轮该干什么"。共四条周期，CLI 入口 `--lifecycle-mode=C1|C2|C3|custom`（缺省 `C2`，兼容现有行为）。每条周期 = 阶段序列（S1–S7 的子集/变体）+ 录制策略 + 失败策略。

### LS-C1 · 直接启动（MD 直启）

| 项 | 内容 |
|---|---|
| 语义 | 不走 AG、不走 MRS：MG 运行时（MRT）直接按 S1–S7 启动游戏。只消费已就绪的 MID / MNF 原生描述符 / 原版 |
| 阶段序列 | S1 Discovery → S2 Parse → S3 Transform → S4 RegistryMerge → S5 EventBusInit → S6 ModInit → S7 Loaded（全走，但无 AG 转译、无沙盒录制） |
| 录制 | 无 |
| 适用 | 纯 MG 原生 mod 冒烟、验证 MRT 本身、转译已完成的环境二次启动 |
| 失败策略 | MID 缺失/损坏 → 按 MEL 分级报错，不静默跳过（原则 3） |

### LS-C2 · 游戏 mod 转译（硬解码 + MRS 含录制）

| 项 | 内容 |
|---|---|
| 语义 | 对非原生 mod（NeoForge/Fabric 等）先走 AG：硬解码（MBD）+ MRS 沙盒录制（MSR-neo 进程内预检 / 注入器深录），产出或补全 MID，再交 MRT 按 S3–S7 重放加载 |
| 阶段序列 | S1 Discovery → S2 Parse（MBD 硬解码字节码）→ **MRS 录制**（C2 与 C3 的唯一差别）→ 组装 MID → S3–S7 重放 |
| 录制 | 开。落 `.mid-cache`（`--sandbox-record=auto`：缺 `recorded` 才录；`force` 重录；`off` 关） |
| 适用 | 新 mod 入库、整库重录、AG 编译期转译需要行为级 MID（见 `2026-10-25_msr-roadmap.md` P3） |
| 失败策略 | 录制失败 → 降级为只存元数据 + WARN（MSR 是探测不是硬前提，不阻断启动） |

### LS-C3 · 游戏 mod 转译（硬解码 + MRS 不含录制）

| 项 | 内容 |
|---|---|
| 语义 | 同 C2，但 MRS 只做"不含录制"的解析：硬解码 + 静态分析 + 被动捕获的存量数据（见 MSR 路线图 P2），**不跑沙盒构造** |
| 阶段序列 | S1 Discovery → S2 Parse（MBD + 静态/被动解析）→ MID 校验 → S3–S7 重放 |
| 录制 | 关（即使缓存有 `recorded` 也不触发新录制；存量数据照常消费） |
| 适用 | 性能敏感首启、录制数据已完备的已知 mod 库 |
| 失败策略 | **MRS 判定"无法完全解析 mod"时三选一**（可配置，缺省 `confirm`）：见下表 |

**C3 失败策略表**（LS-Interfaces.md 冻结的枚举 `ParseFailPolicy`）：

| 策略 | 行为 | 适用 | 默认 |
|---|---|---|---|
| `skip` | 跳过该 mod，继续启动，记 WARN | 已知可缺省的装饰性 mod | 否——违反原则 3"禁止静默失败"，须显式开启 |
| `crash` | 立即崩溃（MEL FATAL），输出缺失项清单 | 无人值守 / CI | 无人值守时自动降级目标 |
| `confirm` | 控制台列出无法解析的 mod + 原因，等待用户确认（继续/跳过/中止） | 交互式启动 | **是** |

**"无法完全解析"的判定标准**（阈值由 LS-Interfaces 冻结，初版建议）：
1. MBD 硬解码覆盖率低于阈值（如关键段未解析出 MID 必需字段）；
2. MID 校验失败（必填字段缺失 / 版本区间为空且静态解析不可得）；
3. 依赖未满足（`requires`/`depends` 有 mod 不在待加载集）；
4. mod 主类定位失败（无 `@Mod` 注解、无可用入口）。

### LS-C4 · 自定义（按配置文件）

| 项 | 内容 |
|---|---|
| 语义 | 按配置文件自定义整轮行为：选周期模板、逐阶段开关、失败策略、录制模式、AG 模块启用集、阈值 |
| 入口 | CLI `--lifecycle-config=<file>`（缺省 `mirage.ls.json`，放实例根目录或 mods 旁） |
| 结构草案 | `{ "cycle": "C2", "ag": ["harddecode","neoforge-compat"], "record": { "mode": "auto" }, "parse": { "failPolicy": "confirm", "minCoverage": 0.8 }, "stages": { "transform": { "enabled": true } } }` |
| 适用 | 高级用户、CI 产线、AG 转译批量作业 |

### 周期对比总表

| 周期 | AG 转译 | MRS 录制 | 解析失败策略 | 典型场景 |
|---|---|---|---|---|
| C1 直启 | 否 | 否 | MEL 分级报错 | 原生 mod 冒烟 |
| C2 转译+录制 | 是 | 是 | 录制失败降级 WARN | 新 mod 入库 |
| C3 转译+不录制 | 是 | 否 | skip/crash/confirm（默认 confirm） | 性能敏感首启 |
| C4 自定义 | 按配置 | 按配置 | 按配置 | CI / 高级用户 |

## 前置条件与阻塞项
- 现有 `Launcher.main` 把 S1–S7 全部硬编码在单文件里，且 AG compat 代码（NeoForge stub、fabric.mod.json 解析）嵌在 `core/mirage-loader` 与 `launcher` 中。
- AG 模块（`modules/mirage-neoforge-compat`、`modules/mirage-harddecode`）已独立，但运行时仍直接反射调用，未通过 LS 调度。
- 无自动化测试，回归靠 `gradle build` + 冒烟。
- 阻塞：需要先冻结 LS 的接口（类似 A1 的 MG-Interfaces），否则各模块各写各的。本计划建议新增 `docs/agents/A1-Spec/LS-Interfaces.md`。

## 现状分析
- MG 运行时（MRT）：`core/mirage-loader`、`core/mirage-mixin`、`core/mirage-eventbus`、`api/`、`launcher/mirage-launch`。
- AG 转译层：`modules/mirage-harddecode`（MBD）、`modules/mirage-neoforge-compat`（MTN）、`modules/mirage-fabric-compat`（MTF，若存在）、`modules/mirage-kubejs-compat`（MKC）。
- 当前 Launcher 直接做：mods 发现 → classloader → mixin → SharedConstants/Bootstrap → unfreeze registry → DeferredRegister → mod init → freeze → MC main。AG 的转译逻辑（NeoForge stub 反射、fabric entrypoints）散落在其中。
- 问题：MG 与 AG 耦合；启动流程不可插拔；"该干什么"由 Launcher 硬编码决定，无法按模式切换。

## 改动文件清单

### 要改
| 文件/模块 | 改什么 |
|---|---|
| `launcher/mirage-launch` → 拆出 `lifecycle-starter` (LS) 模块 | LS 成为唯一入口 `io.mirage.ls.LifecycleStarter.main`，负责按模式调度 |
| `core/mirage-loader` | 去除 AG 耦合（NeoForge stub 反射、fabric entrypoints 解析）；只保留 MRT 运行时（类加载、注册表、事件总线、Mixin 服务） |
| `modules/mirage-harddecode` (MBD) | 独立维护，LS 通过接口调用，不内嵌 |
| `modules/mirage-neoforge-compat` (MTN) | 独立维护，LS 在 transpile 模式下调用 |
| `modules/mirage-fabric-compat` (MTF) | 独立维护 |
| `docs/agents/A1-Spec/LS-Interfaces.md`（新建） | 冻结 LS 接口：`LifecycleMode`、`Stage`、`ModuleHandle`、调度契约 |
| `settings.gradle.kts` | 新增 `:lifecycle-starter` 模块 include |

### 明确不做
- 不改 MC 原版类、不改 `mc-shili/`、不改 `refs/`、`backups/`。
- 不重写 Mixin 集成（MMS 不变）。
- 不在本轮引入 KubeJS 承载（MKC 独立模块但 LS 仅预留钩子）。
- 不做 MID-S/MID-B 语义编译器（MSCP）本轮不动。

## 每处改动要点
1. **LS 模块**：新建 `lifecycle-starter/`，依赖 MRT（runtime）+ 可选 AG 模块（通过 ServiceLoader/接口）。入口 `LifecycleStarter.main(args)` 解析 `--lifecycle-mode=native|transpile|record`，按模式执行阶段。
2. **阶段抽象**：把 S1–S7 提炼为 `Stage` 接口，每个阶段一个实现类（`DiscoveryStage`、`ParseStage`、`TransformStage`、`RegistryMergeStage`、`EventBusStage`、`ModInitStage`、`LoadedStage`）。AG 模块注册为 `StageContributor`，LS 在对应阶段调用。
3. **MG/AG 解耦**：MRT 不再 import `net.neoforged.*`、`net.fabricmc.*` 的转译逻辑；这些全部移到 AG 模块，通过 `Transmuter` 接口产出 MID，LS 把 MID 交给 MRT 重放。
4. **MID 作为解耦格式**：AG 输出 `*.mid.json`（或 in-memory MID），MRT 只认 MID，不关心来源。本轮先支持 in-memory MID，文件持久化后置。
5. **周期分支**（替代早期 native/transpile/record 三模式草案，四周期语义见上文"LS 周期"）：
   - `C1`（= 原 native）：只加载已就绪 MID / MNF 原生 mod，跳过 AG 与 MRS。
   - `C2`（= 原 transpile + record）：非原生 mod 走 AG 硬解码 + MRS 录制成 MID 再加载。
   - `C3`（= 原 transpile，不录制）：只做硬解码 + 静态/被动解析；解析失败按 `ParseFailPolicy`（skip/crash/confirm）处理。
   - `C4`：按 `mirage.ls.json` 自定义。
   - 缺省 `C2`（兼容现有行为，用户无感知）。
6. **保留现有 Launcher**：把 `launcher/mirage-launch` 降级为 thin wrapper，委托给 LS，保证启动脚本 `启动 26.2-mirage.bat` 不变。

## 回归测试影响面
- 风险：拆分过程中 LS 调度顺序若与原 Launcher 不一致，会导致注册表/事件/entrypoint 时序变化 → 游戏崩溃或 mod 不加载。
- 需要：冒烟启动 MC 主菜单；grep 日志确认所有 mod entrypoint 被调用；对比拆分前/后的 `logs/latest.log` 关键字集合。
- 零侵入模块：`core/mirage-mixin`、`core/mirage-eventbus`、`api/`（仅调整包依赖，不改逻辑）。
- mdk-example 冒烟步骤不变（仍走 LS）。

## 验证步骤
1. `gradle build` 全绿（所有模块）。
2. 用户双击 `启动 26.2-mirage.bat`，读 `logs/latest.log`，确认：
   - `[LS] mode=...` 启动行；
   - 所有 mod entrypoint 调用日志；
   - MC 主类移交成功（`移交控制权给 net.minecraft.client.main.Main`）。
3. 对比拆分前日志，关键关键字集合一致。

## 合规边界
不涉及 LGPL 代码。NeoForge/Forge 兼容仅通过 clean-room 行为观察与接口枚举实现，不抄其实现。Fabric/Mixin 系按 Apache 2.0/MIT 参考。

## 回滚方案
- 本轮为架构级重构，建议分多次 commit：先建 LS 模块 + Stage 接口（commit 1），再迁移 Launcher 逻辑（commit 2），再解耦 AG（commit 3）。
- 若任一 commit 破坏冒烟，revert 该 commit 即可；MG 运行时核心（MRT）保持不变，回滚成本低。
- 回滚后按规则 6 写回退报告。

---

## 已决定（用户：改名；其余按现有情况决定）
1. **LS 模块命名**：`lifecycle-starter`，包 `io.mirage.ls`，Gradle 模块 `:lifecycle-starter`。`mirage-launch` 保留为 thin wrapper 委托给 LS，启动脚本不变。理由：与 Mirage 命名体系（MLC 生命周期）一致，且不破坏现有启动链路。
2. **AG 模块拆分粒度**：保留在 `modules/` 下，不独立 gradle 子项目。理由：现有 `modules/mirage-harddecode`、`modules/mirage-neoforge-compat` 已是独立子项目，无需再拆；降低构建复杂度。
3. **第一阶段范围**：先做"LS 调度 + Stage 抽象"，不动 AG（NeoForge stub 解耦后置）。理由：降低风险，保证现有冒烟通过；AG 解耦在第二阶段。
4. **默认模式**：`transpile`（兼容现有行为）。理由：不破坏现有启动，用户无感知。
5. **LS 周期（2026-10-25 用户决定，四条）**：
   - **C1 直接启动（MD 直启）**：不走 AG、不走 MRS，MG 运行时直接按 S1–S7 启动，只消费已就绪 MID / 原生描述符。
   - **C2 游戏 mod 转译（硬解码 + MRS 含录制）**：非原生 mod 先走 AG 硬解码（MBD）+ MRS 沙盒录制成 MID，再交 MRT 重放；录制失败降级为只存元数据 + WARN，不阻断启动。
   - **C3 游戏 mod 转译（硬解码 + MRS 不含录制）**：同 C2 但不跑沙盒构造；MRS 判定无法完全解析 mod 时按 `ParseFailPolicy` 三选一——`skip` / `crash` / `confirm`，**缺省 `confirm`**（交互式等待用户确认；无人值守自动降级为 `crash`；`skip` 须显式开启，因违反原则 3"禁止静默失败"）。
   - **C4 自定义（按配置文件）**：`mirage.ls.json` 自定义周期模板、逐阶段开关、失败策略、录制模式、AG 启用集、阈值。
   - 缺省周期 = `C2`（兼容现有行为）。
6. **C3 失败策略归 LS-Interfaces 冻结**：`ParseFailPolicy` 枚举（skip/crash/confirm）与"无法完全解析"的判定阈值（MBD 覆盖率下限、MID 必填校验、依赖满足、主类定位）由 A1/MIS 在 `docs/agents/A1-Spec/LS-Interfaces.md` 冻结，禁止各模块自定义。

---

## phase 2 落地记录（2026-10-25，用户指令「完善LS」）

**状态：代码完成，离线编译通过；运行时冒烟待用户双击 bat。**

- **S1–S7 全部真实化**：七个阶段类从 `skipped("phase-2 迁移未完成")` 占位改为保序迁入的 Launcher 单体逻辑（逐阶段同日志关键字）；共享支撑 `StageSupport`/`ModEntrypoints`/`NeoLifecycle`/`RegistryWindow` 新建；`LegacyBootStage` 删除，pipeline 不再经过委托跳转。
- **四周期接线**：`LifecycleMode` = C1/C2/C3/CUSTOM（别名 native/transpile/record；缺省 C2）；C1 在 S1 做原生过滤；各周期录制缺省 C1/C3=off、C2/CUSTOM=auto（C4 可覆盖）。
- **ParseFailPolicy 接线**：CLI `--lifecycle-parse-fail=` > C4 配置 > 周期缺省（C3=confirm，其余=skip）；无人值守 confirm→crash；S2 应用策略，`ctx.skippedMods()` 供 S6 消费。
- **C4 配置**：`mirage.ls.json`（`--lifecycle-config=` 或 `<gameDir>/mirage.ls.json`）；零依赖最小 JSON 解析器；校验失败 FATAL。
- **依赖换向**：`launcher/mirage-launch` 加 `implementation(project(":lifecycle-starter"))`；`lifecycle-starter` 去掉对 launcher 的依赖；`Launcher.main` = installLogTee + banner + 早期窗口 → 单向委托 `LifecycleStarter.main`。
- **与规范表的三处保序偏差**（门面落 S2 / register 留 S6 / 关窗落 S6 尾）：记入 LS-Interfaces 附录 B，MG-Lifecycle 规范表未改。
- **接口文档**：LS-Interfaces v0.1 → v0.2（含附录 A 六个 properties key）；DECISION-LOG 追加 5 条。
- **编译验证**：`.gradle-dist/gradle-9.1.0` + JDK25 离线 `:lifecycle-starter:compileJava` + `:launcher:mirage-launch:compileJava` BUILD SUCCESSFUL。
- **提交卫生**：Launcher.java / launcher build.gradle.kts 与 Fabric P1 WIP 同文件，留工作区不提交；本轮只提交 `lifecycle-starter/**` + LS-Interfaces.md + DECISION-LOG.md。
