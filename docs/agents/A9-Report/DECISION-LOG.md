# MG 项目决策记录

> 本文件记录项目期间所有需要留痕的决策：包括用户拍板的技术方向、接口变更、风险处理方案、许可证策略等。由 A9-Report 维护，每次决策后追加一行，不修改历史记录。

| 日期 | 决策人 | 决策内容 | 影响范围 | 备注 |
|---|---|---|---|---|
| 2026-10-25 | 用户 | LS 启动周期定四条：C1 直启（无 AG 无 MRS）/ C2 转译+含录制（**缺省**，兼容现有行为）/ C3 转译+不含录制 / C4 自定义（mirage.ls.json）；取代 v0.1 的 NATIVE/TRANSPILE/RECORD 三模式，保留 native/transpile/record 别名 | `io.mirage.ls.LifecycleMode`；LS-Interfaces §1 | CLI `--lifecycle-mode=`；非法值 FATAL 不归默认 |
| 2026-10-25 | 用户 | 解析失败策略 ParseFailPolicy = skip/crash/confirm 三选一；C3 缺省 confirm；无人值守（无控制台）confirm 自动降级 crash；skip 用于 C3 须显式开且必带 WARN | `io.mirage.ls.ParseFailPolicy`；S2 检测主类定位失败 | CLI `--lifecycle-parse-fail=` 可覆盖；优先级 CLI > C4 > 周期缺省 |
| 2026-10-25 | 用户 | phase 2 迁移采「保序优先」：逐阶段保持日志关键字与 mod 可观察时序不变，冒烟等价优先于规范表语义纯度；三处偏差（门面落 S2 / register 留 S6 / 关窗落 S6 尾）记 LS-Interfaces 附录 B，不静默修正 | S1–S7 阶段类；MG-Lifecycle 规范表未改 | S5 退为不变量检查点 |
| 2026-10-25 | 用户 | Launcher 降级 thin wrapper：只留 installLogTee/banner/早期窗口，随后单向委托 `LifecycleStarter.main`；依赖换向（launcher→LS），LS 不反向依赖 launcher（防 Gradle 循环 + §9 反递归）；LegacyBootStage 删除 | `io.mirage.launch.Launcher`；`launcher/mirage-launch/build.gradle.kts`；启动脚本不变 | Main-Class 不变；fat jar 经 runtimeClasspath 自动 shade |
| 2026-10-25 | A1 | LS-Interfaces v0.1 → v0.2：周期改四周期、补 ParseFailPolicy/LsConfig 冻结、附录 A key 清单（6 个 properties key + skippedMods）、附录 B 偏差记录、§6 登记 LegacyBootStage 删除 | `docs/agents/A1-Spec/LS-Interfaces.md` | 与代码 javadoc 引用编号一致 |
| 2026-11-22 | 用户 | **终极目标定为「全部加载器 mod 全部互通」，据此裁定松动 D11、转路线 C（复用真实加载器运行时 / 统一调度层），替代 clean-room 穷举补类路线**。PLAN.md D11 原文「零依赖：不加载/不反射/不 classpath 引用任何 Fabric/NeoForge/Forge 代码」自此作废（历史保留），新方向为按 mod 原生类型调度真实运行时、Mirage 做统一入口。D9 单层隔离需重设计；其余 D0–D8/D10/D12 保留。转向前先出完整现状梳理基线 | `PLAN.md` D11、D9；项目定位；`docs/2026-11-22_转向C前的现状梳理.md`（基线） | 依据：NeoForge 1741 类/MC 10964 类/6 mod→56 缺口/GUI 注入点 0，穷举不可达 100% 且与 D11 互斥；LGPL 合规与注册表统一是主要风险；先定 Q1 判据、合规评估、单运行时 PoC。**【本条已被下一条修正·2026-11-22 13:xx】** |
| 2026-11-22 13:xx | 用户 + AI | **上一条「松动 D11、转多运行时统一调度」作废——该路线被反面教材证伪**。用户提供《智能纪要：MC模组加载器相关内容分析 2026-10-07》，其批判的 Forbrick/Forge Kernel 死因逐条命中我们的设计：强行焊接三加载器、事件桥（103 Forge 事件不可触发）、手写试错启动时间表、Mixin 非必须+静默跳过、废弃代码兜底。最终方向改为：**D11 实质恢复生效（clean-room 不加载真实运行时）；mod 原生字节码直接跑；MG 提供 API 语义层；MG 不碰原版 MC 字节码；mod Mixin 与 MG 织入冲突时 mod 优先、MG 让路且可观测**。P3「永久不做」改为纳入范围排期靠后；Q1 判据钉死为 L3。GitHub `Sinytra/Connector@26.2.x` 公开源码调研：借鉴 OrderingHint+拓扑排序、目标精确匹配、失败集中仲裁；避开其 `silenceClasses` 静默降级 | `PLAN.md` D11（追加作废标注）；`AGENTS.md` 四条禁令；`docs/经验与踩坑.md` C109；C0 立项与 C2 清单（P3 标注）；`docs/plans/2026-11-22_C决策复盘与计划补丁.md` | 这是项目定位的第二次修正（第一次 = 松动 D11，本次 = 推翻第一次）。两次原文均保留，按只标不删 |
