# P1-14 · Fabric mod depends 依赖检查（软策略切片）

> 关联：`docs/Fabric兼容层缺口清单.md` P1-14（2026-11-01 新列）；同日 P1-13 修正版（commit 0d3aaa5）同一批新列的姊妹缺口。上游无。

## 目标

让 fabric.mod.json 的 `depends` 字段真正参与加载流程：运行时键（minecraft/java/fabricloader）被识别并忽略，能在已发现 mod 中解析的依赖进入 `ModManifest.depends` 使 `DependencySorter` 拓扑排序生效（依赖先加载），解析不了的 mod 依赖 WARN 指名。

**可验证标准**：`scripts/verify_mod_depends.ps1` 全绿（纯 JVM 断言 + 变异检验）；带依赖声明的两个测试 jar 拓扑顺序正确；依赖缺失时 WARN 出现在 mirage-launch.log 且启动不中断。

## 前置条件与阻塞项

- P1-13（0d3aaa5）已落地并推送，无依赖关系但共用 ModDiscoverer 文件，先后串行避免冲突。
- **工作区有另一会话的 UI WIP**（ForgeStyleLayout/Overlay、DiscoveryStage、ParseStage、ModInitStage 等挂 M）——本轮**只动 core/mirage-loader**（不在其 WIP 列表），不碰 lifecycle-starter。
- 基线 build 已回绿（EarlyFont 笔误修复后）。可立即开干。

## 现状分析

- `FabricModManifest.depends` 是 `Map<String, Object>`，Gson 能填，但 `toMirageManifest()` 置 `m.depends = null`，全链路无人消费（缺口本体）。
- `Loader.java:38` 对全部 mod 跑 `new DependencySorter().sort(raw)`；该排序器对 `manifest.depends` 里**缺失的依赖硬抛 IllegalStateException**（第 24-27 行），NeoForge 路径靠"只放 mandatory 的 mod 间依赖"躲开误杀（NeoForgeModDetector.java:42 注释明示这一设计）。
- Fabric 生态 depends 键分三类：①运行时键 `minecraft`/`java`/`fabricloader`——永远不在 mod 容器集里，透传 = 全体真实 mod 被炸死；②真 mod 依赖（如 `fabric-api`）；③**provides 聚合 id**（如依赖 `fabric-lifecycle-events-v1`，实际由 fabric-api jar 的 `provides` 字段满足）——本加载器不解析 `provides`，③类会误报缺失。
- 结论：直接透传 + 硬抛不可行。切片策略=软：可解析的进拓扑排序；缺失的 WARN 不阻断（等 `provides` 支持后再议升级硬抛）。

## 改动文件清单

| 文件 | 改什么 |
|---|---|
| `core/mirage-loader/.../depends/FabricDepends.java`（新） | 纯逻辑类：`split(modId, rawDepends, availableIds)` → 可解析 Map + 缺失 List + 忽略的运行时键 List；内置键常量表 |
| `core/mirage-loader/.../ModContainer.java` | 加 `public Map<String, Object> fabricDependsRaw;` 字段（仿 fabricMixinConfigs） |
| `core/mirage-loader/.../ModDiscoverer.java` | fabric 分支存 raw depends 到容器；jar 循环结束后（result 全量 id 可见）两阶段：逐容器 split → `m.depends`（空则 null）+ WARN 缺失 + debug 运行时键 |
| `scripts/dependscheck/FabricDependsCheck.java`（新） | 纯 JVM 断言程序 |
| `scripts/verify_mod_depends.ps1`（新） | 编译运行检查程序 + 源码守卫 + 变异检验 + 终验 |

> 明确不做：`DependencySorter` 一字不动（NeoForge 硬抛语义保持）；`provides` 解析不做（边界，见下）；`FabricModManifest` 字段结构不动（Gson 已能填 depends）；lifecycle-starter 全部文件（另一会话 WIP）；版本范围比较（DependencySorter 本来就忽略版本，语义一致）。

## 每处改动要点

1. **FabricDepends.split**（纯函数，无 IO 无日志依赖，可离线断言）：
   - 内置运行时键：`minecraft`、`java`、`fabricloader`（大小写不敏感）→ 归入 ignored；
   - value 规范化：String 原样；List 取第一个 String 元素；其他类型（Boolean/Number）→ 空串（排序只看 key）；
   - key 在 availableIds → resolvable；否则 missing。
2. **ModDiscoverer fabric 分支**：`c.fabricDependsRaw = fm.depends;`（一行）。
3. **ModDiscoverer 收尾**（return result 前）：构建 `Set<String> ids`；对非空 fabricDependsRaw 的容器调 split；resolvable 非空 → `c.manifest.depends = resolvable`；missing 非空 → WARN `"! mod 'x' 声明的依赖未找到: a, b（Fabric provides 聚合语义未建模，若实际由聚合 jar 提供可忽略）"`；ignored → debug 一行。try/catch 包住单容器处理，异常降级 WARN 不阻断发现流程。
4. **日志走 `MirageLog.LOADER`**，与 ModDiscoverer 现有一致。

## 回归影响分析

- `DependencySorter` 输入变化：Fabric mod 的 manifest.depends 从恒 null → 可能非空。sort 的缺失检查只看传入的 key——本轮只放 resolvable 项，**不会触发新抛异常**；拓扑排序对无依赖 mod 是恒等序，对有依赖 mod 才重排，`Loader` 下游按序加载，无副作用。
- NeoForge 路径零改动；mirage.mod.json 路径零改动。
- MID 缓存/沙盒录制不涉及 depends。

## 验证步骤

1. `scripts/verify_mod_depends.ps1`：断言 split 的规范化/内置键过滤/可解析/缺失四象限 + 源码守卫（split 被调用、内置键表存在）+ 4 种变异（去内置键过滤/去缺失收集/去 value 规范化/去 resolvable 赋值）全红 + 终验回绿。
2. `gradle build` 全绿 + `:launcher:mirage-launch:fatJar`。
3. `jar tf` 核对 FabricDepends.class 在包内；部署前核对 ~2.8MB。
4. 实机（用户双击 bat，UI 会话收口后）：无依赖 mod 启动无新 WARN；带 fabric-api 依赖的 mod 缺失时 WARN 可见。

## 合规边界

- 改动限 core/mirage-loader 单模块 + scripts；不碰 UI 会话 WIP 文件；提交只 add 自己的文件。
- 类加载：FabricDepends 在 core/mirage-loader，随既有核心类加载路径，无白名单问题。
- 不改 Mixin 配置格式/类加载隔离规则/entrypoint 语义（规则 4 其余触发项不涉及）。

## 回滚

- 代码回滚：revert 本轮 commit 即可——改动全部收敛在 1 个新类 + 3 处小改，无数据格式变更（MidCache schema 不动）。
- 部署回滚：`mc-shili/libraries/.../mirage-launch-0.1.0.jar.bak-20261101-depends` 备份恢复。
- 行为回退验证：WARN 行在日志中消失、拓扑顺序回恒等序即确认回干净。
