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
5. **模式分支**：
   - `native`：只加载 MNF 原生 mod，跳过 AG。
   - `transpile`：对非原生 mod 调 AG 转译成 MID 再加载。
   - `record`：启用 MSR 沙箱录制（后置，本轮仅预留）。
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
