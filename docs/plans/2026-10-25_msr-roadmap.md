# MSR 录制器路线图（P0–P4）

> 关联：`docs/plans/2026-10-17_sandbox-neo.md`（MSR 设计）、`docs/plans/2026-10-18_sandbox-neo-impl.md`（P0 落地）、`docs/MG-项目总纲.md`（A3/MSR 定位）
> 日期：2026-10-25

## 目标

把 MSR（虚幻沙盒记录器，A3）从"能跑的 fake pass 预检器"推进为"可靠的 MID 录制体系"。本路线图回答三个问题：**每阶段做什么、做到什么程度算完、什么信号触发升级**。

**总原则**：进程内止血 → 消灭双跑 → 录制搬出运行时 → 盲区自动化兜底。

## 现状（P0 已落地）与缺陷清单

P0 = MSR-neo：进程内一次性 `SandboxClassLoader` + fake 表面 + 2 秒超时熔断 + `.mid-cache` 0.2（`recorded` 子对象）+ `--sandbox-record=off|auto|force`。**定位是轻量预检器，不是录制器。**

| # | 缺陷 | 层级 | 严重度 |
|---|---|---|---|
| 1 | mod 构造调 `System.exit`/`Runtime.halt` 会杀死整个 launcher 进程 | 进程级 | 高 |
| 2 | mod 构造函数跑两遍（沙盒一遍 + 真实加载一遍），副作用双发（读配置两次、写文件、起线程） | 实现 | 高 |
| 3 | 超时不 interrupt，线程残留与真实加载并行；半成品 `touched_apis` 照常入库 | 实现 | 中 |
| 4 | fake 表面手工枚举：Mixin/setup 阶段/lambda 体/反射在设计上录不到，CNFE 误标 failed | 设计 | 中 |
| 5 | `recorded` 无消费方（版本伪装未实现），数据空转 | 数据 | 中 |
| 6 | 缓存 key 无 fake 版本维度：fake 扩充后老缓存不失效 | 数据 | 低 |
| 7 | `recorded_loader_version` 与 `NeoForgeModDetector` 静态解析重复（录制里冗余） | 数据 | 低 |

## P1 · 进程内加固（下一步，先做）

**目标**：在不 fork JVM 的前提下止住进程级与数据级出血。

| 动作 | 要点 |
|---|---|
| ASM 改写 exit/halt | 挂点是**重写 `SandboxClassLoader.findClass`**（不是 `defineClass`——非 fake 包走 `super.loadClass` 即 URLClassLoader 父优先路径，mod 类由继承的 `URLClassLoader.findClass` 定义，不过我们的 defineClass）。自己查 URL 资源 → 读字节码 → 改写 → defineClass。把 `System.exit(I)V`/`Runtime.halt(I)V` 调用改写为 throw `SandboxKillException`（带原退出码），被沙盒线程 catch(Throwable) 接住，照常降级 `failed=true, error="exit(code)"` |
| 超时 best-effort | `join(2000)` 超时后 `t.interrupt()` + 置 `incomplete=true`；`incomplete` 时 `touched_apis` 不入库或打标入库。**注意**：interrupt 拦不住不可中断阻塞，Java 25 无 `Thread.stop`，进程内不承诺硬杀 |
| 全局态快照/恢复 | 录前存 `Locale.getDefault()`/`TimeZone.getDefault()`/`System.getProperties()` 拷贝，finally 恢复 |
| fakeVersion 进缓存 key | `.mid-cache` key 增加 fake 表面构建号，fake 扩充后老缓存自动失效重录 |
| 验证矩阵 | 四个测试 mod 挂进 `scripts/verify_sandbox_neo.ps1`：构造里 `System.exit(1)`（断言 launcher 不死）、构造死循环（断言 best-effort + incomplete）、构造写文件（断言降级 + 恢复）、带 Mixin 的 mod |

**明确不做**：`Runtime.exec` 拦截、文件副作用隔离——进程内无解，接受并在文档标注，由 P3"实例即耗材"消化。

**验收标准**：四个测试 mod 全部按断言通过；现有 mod（ExpOre/ferritecore）行为不回退；`gradle build` 成功。

> **【实施记录·2026-10-31】** P1 已落地，计划书见 `2026-10-31_msr-p1.md`，验证脚本 `scripts/verify_msr_p1.ps1`（26 项断言 + 变异检验），实机冒烟待用户双击 bat。
> 与原文的偏差（如实记）：① 测试 mod 从"4 个"扩到 5 个（多一个"fake 表面未覆盖 → 诚实降级"）；② 验收手段不是 `gradle build` 而是 `:core:mirage-loader:compileJava` + `:launcher:mirage-launch:fatJar`（`build` 会把 fat jar 覆盖成瘦 jar，见 C12）；③ "现有 mod 行为不回退"目前只有纯 JVM 用例覆盖，实机未验（等用户冒烟）。
> 原文第 4 条"带 Mixin 的 mod"在本轮的实现口径是"自带 mixin 配置的常规 mod 录制不崩"——MSR 本身不执行 mixin，真跑 mixin 要等 P3 的游戏侧观测。

## P2 · 单次运行原则（核心，根治双跑）

**目标**：让构造函数只跑一遍，录制成为真实加载的副产品。

1. **fake pass 按需化**（`ModDiscoverer`）：`NeoForgeModDetector` 静态解析出完整版本区间 → 跳过录制；仅当 toml 缺版本区间、P1 报覆盖不足信号、或 `--sandbox-record=force` 时跑 fake pass。同时消灭缺陷 2 与首启慢（N 个 mod × 最多 2 秒）。
2. **被动捕获**：在 `MirageClassLoader`、注册桥（`hook→flush→nativeBackend`）、事件桥埋点，真实加载时逐 mod 记录；逐 mod `complete` 标记 + 原子写盘，launcher 中途崩溃不留半成品缓存。
3. **消费方落地**：版本伪装回放（运行时按 MID 向 mod 报录制版本）+ 二启 diff——缺陷 5 在此关闭。

**验收标准**：带版本区间的 NeoForge mod 首启**零** fake pass 日志；缺区间的 mod 按需录制；二启产生 diff 报告；崩溃模拟（kill -9）后缓存无半成品。

> **【实施记录·2026-10-31】** P2 已落地，计划书见 `2026-10-31_msr-p2.md`，验证脚本 `scripts/verify_msr_p2.ps1`（43 项断言 + 变异检验），实机冒烟待用户双击 bat。
> 与原文的偏差（如实记）：
> 1. **"首启零 fake pass"的判据**落在 `[Sandbox] 开始录制` 不出现 + 一行 debug 汇总 `fake pass: 0 次`；缺区间的 mod 仍会录，且**录了也补不出区间**（fake pass 只产生 touched 数据，版本信息全来自静态 toml 解析）——原文把"缺区间→跑 fake pass"写成触发条件，实际它在该场景只有诊断价值，不解决数据缺失。
> 2. **被动捕获的覆盖面窄于沙盒**：挂点全在自有代码里，mod 直接调 MC 原版方法的调用看不到（要 P3 插桩）。因此合并策略是**取并集**而非覆盖，且文档不宣称两者等价。
> 3. **版本伪装只解决"报什么版本"**，不新增"版本不匹配就拒绝加载"的判定；`FMLLoader` stub 只有 `versionInfo()`（其余方法缺失会以 `NoSuchMethodError` 暴露，不造假值）。
> 4. **"崩溃模拟（kill -9）后缓存无半成品"**用 `.tmp` 残留 + `ATOMIC_MOVE` 实现并做了用例（只剩 `.tmp` 时 `lookup` 必须为 null），未真做 kill -9 进程实验——纯 JVM 用例已覆盖等价语义。
> 5. 原文第 3 条"运行时按 MID 向 mod 报录制版本"中"录制版本"实际取的是 **toml 声明的区间端点**（MID 里那份字段本就抄自 toml），伪装算法见 `LoaderVersionMasquerade.pick`。

## P3 · 注入器深录（目标形态，AG/MDK 工具链）

**目标**：行为级 MID 的批量生产工具，一次把整库 mod 录成注册/事件/配置级别的 MID（MID-B 数据来源）。

**范式**：从"mod 侧 API 代理（fake）"转为"游戏侧状态观测 + diff 驱动"——插桩点收敛在 MC/NeoForge 侧有限几个类，比枚举整个 mod API 面容易几个数量级。

| 组件 | 设计 |
|---|---|
| 实例形态 | headless dedicated server（MC 26.2 无混淆，NeoForge server jar 可下载）；待测 mod 放入实例 `mods/`；`ServerAboutToStartEvent` 后 `System.exit(0)`，不进世界 tick |
| Agent | `mirage-recorder-agent.jar`（javaagent，premain → `Instrumentation.addTransformer`），**不进 MG 运行时/fat jar** |
| 插桩点 | `Registry`（退出前 dump 全量 id 与基线求差，免插桩）；`EventBus.addListener` / `@SubscribeEvent` 扫描入口；`ModConfig` 加载；`SimpleChannel` 注册 |
| 归属 | `DeferredRegister` 自带 modId 为主，栈回溯找 mod jar 类兜底；分不清的标"归属未知"，不猜 |
| 基线 | 空 mods 实例跑一次存注册表快照 + 事件列表 |
| 隔离 | 一次性 `userdir`（世界/配置/日志全在内，跑完即弃）；断网运行 |
| 产物 | 观测日志 → 组装 IR（`mod.mirage.json` 风格，带实例 NeoForge/MC 版本标签） |

**成本与边界（如实）**：一次性冷启动 10–30 秒（上限固定，比 fake pass 最坏 N×2s 更省），但是**构建期/开发期工具，不进用户日常启动路径**；客户端 mod（渲染/GUI）录不了——总纲已判死，MID 客户端部分继续靠静态分析；NeoForge 发行包从 Maven 拉入沙盒目录（运行外部 NeoForge 不违反 clean-room 红线，红线禁的是抄代码进 MG）；mod 真实副作用（写文件/连网）由"实例即耗材"消化。

**触发条件**（别默认开）：整库录制、AG 编译期转译需要行为级 MID、或 P2 信号持续（failed 率高 / touched_apis 大面积空）。

**验收标准**：基线 + 录制两实例各跑一次，diff 产出的注册/事件清单与人工核对一致；观测日志可回放组装为合法 IR；坏 mod 只死实例不影响录制器。

## P4 · fake 自动化（按需，可跳过）

**目标**：仅当 fake pass 仍是常驻支柱时才投入。P3 落地后 fake pass 退化为纯预检，本阶段价值大减，**可能永远不做**。

- 构建期从真实 jar 生成缺口 fake：常量池预检（扫 mod jar 引用的 `net.*` 类 ∩ fake 覆盖）算缺口 → **只生成缺口类**；
- 暗坑处理：enum 的 `<clinit>` 不能剥（常量是 static final 字段，剥了全 null）；签名闭包会拖传递依赖，所以只做缺口不做全量；enum/注解/接口进白名单保留原样或保留 clinit。

## 升级触发条件

| 信号 | 升级到 |
|---|---|
| 测试 mod 的 `System.exit` 杀过进程 / 超时数据污染缓存 | P1（立即） |
| failed=true 率高、touched_apis 大面积空 | P2 被动捕获补数据；持续则 P3 |
| 需要行为级 MID / AG 转译要完整注册语义 | P3（整库重录一次） |
| fake pass 长期不退场且缺口持续扩大 | P4 |

## 修正记录（相对早期设想的修正）

1. ASM 改写挂点是 `findClass` 不是 `defineClass`（URLClassLoader 默认路径不过我们的 defineClass）。
2. 超时 interrupt 是 best-effort，不是硬保证；硬杀只有子进程（P3）能做。
3. "全量剥体生成 fake"不现实：enum clinit 不可剥、签名闭包拖依赖 → 缩窄为缺口类按需生成。
4. 被动捕获（P2）不能替代首启伪装——数据来晚了；首启伪装继续由静态 toml 解析 + fake pass 按需补。
5. fake pass 从"每个未缓存 mod 必跑"降为按需触发，这是消灭双跑的关键一刀。
6. MSJ（每 mod 一 JVM）维持否决：冷启动线性成本；P3 是批量实例，不是每 mod 一 JVM。
