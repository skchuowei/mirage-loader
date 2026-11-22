# MG 项目决策记录

> 本文件记录项目期间所有需要留痕的决策：包括用户拍板的技术方向、接口变更、风险处理方案、许可证策略等。由 A9-Report 维护，每次决策后追加一行，不修改历史记录。

| 日期 | 决策人 | 决策内容 | 影响范围 | 备注 |
|---|---|---|---|---|
| 2026-10-25 | 用户 | LS 启动周期定四条：C1 直启（无 AG 无 MRS）/ C2 转译+含录制（**缺省**，兼容现有行为）/ C3 转译+不含录制 / C4 自定义（mirage.ls.json）；取代 v0.1 的 NATIVE/TRANSPILE/RECORD 三模式，保留 native/transpile/record 别名 | `io.mirage.ls.LifecycleMode`；LS-Interfaces §1 | CLI `--lifecycle-mode=`；非法值 FATAL 不归默认 |
| 2026-10-25 | 用户 | 解析失败策略 ParseFailPolicy = skip/crash/confirm 三选一；C3 缺省 confirm；无人值守（无控制台）confirm 自动降级 crash；skip 用于 C3 须显式开且必带 WARN | `io.mirage.ls.ParseFailPolicy`；S2 检测主类定位失败 | CLI `--lifecycle-parse-fail=` 可覆盖；优先级 CLI > C4 > 周期缺省 |
| 2026-10-25 | 用户 | phase 2 迁移采「保序优先」：逐阶段保持日志关键字与 mod 可观察时序不变，冒烟等价优先于规范表语义纯度；三处偏差（门面落 S2 / register 留 S6 / 关窗落 S6 尾）记 LS-Interfaces 附录 B，不静默修正 | S1–S7 阶段类；MG-Lifecycle 规范表未改 | S5 退为不变量检查点 |
| 2026-10-25 | 用户 | Launcher 降级 thin wrapper：只留 installLogTee/banner/早期窗口，随后单向委托 `LifecycleStarter.main`；依赖换向（launcher→LS），LS 不反向依赖 launcher（防 Gradle 循环 + §9 反递归）；LegacyBootStage 删除 | `io.mirage.launch.Launcher`；`launcher/mirage-launch/build.gradle.kts`；启动脚本不变 | Main-Class 不变；fat jar 经 runtimeClasspath 自动 shade |
| 2026-10-25 | A1 | LS-Interfaces v0.1 → v0.2：周期改四周期、补 ParseFailPolicy/LsConfig 冻结、附录 A key 清单（6 个 properties key + skippedMods）、附录 B 偏差记录、§6 登记 LegacyBootStage 删除 | `docs/agents/A1-Spec/LS-Interfaces.md` | 与代码 javadoc 引用编号一致 |
| 2026-11-22 | 用户 | **终极目标定为「全部加载器 mod 全部互通」，据此裁定松动 D11、转路线 C（复用真实加载器运行时 / 统一调度层），替代 clean-room 穷举补类路线**。PLAN.md D11 原文「零依赖：不加载/不反射/不 classpath 引用任何 Fabric/NeoForge/Forge 代码」自此作废（历史保留），新方向为按 mod 原生类型调度真实运行时、Mirage 做统一入口。D9 单层隔离需重设计；其余 D0–D8/D10/D12 保留。转向前先出完整现状梳理基线 | `PLAN.md` D11、D9；项目定位；`docs/2026-11-22_转向C前的现状梳理.md`（基线） | 依据：NeoForge 1741 类/MC 10964 类/6 mod→56 缺口/GUI 注入点 0，穷举不可达 100% 且与 D11 互斥；LGPL 合规与注册表统一是主要风险；先定 Q1 判据、合规评估、单运行时 PoC |
