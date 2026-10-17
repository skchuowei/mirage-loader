# MG-Lifecycle.md — MG 加载生命周期阶段

> 版本：**v0.1**（初版，2026-10-11 起 3 天内冻结）
> 维护：A1（MG-Spec）
> 配套：《MG-Spec.md》《MG-Interfaces.md》《MG-Errors.md》
> 原则：生命周期严格按本文档执行，**不靠试错调顺序**；每个阶段失败必须知道回退到哪。

---

## 0. 总览

```
S1 发现 Discovery
   → S2 解析 Parse（含依赖图构建与拓扑排序）
   → S3 字节码转换 BytecodeTransform
   → S4 注册表合并 RegistryMerge（冻结）
   → S5 事件总线初始化 EventBusInit
   → S6 模组初始化 ModInit（入口点）
   → S7 加载完成 Loaded（触发首个游戏 tick / 主菜单）
```

阶段与接口对照（详见 MG-Interfaces.md）：

| 阶段 | 对应接口 | 责任方 |
|---|---|---|
| S1 发现 | `ModDiscoveryService` | A4 |
| S2 解析 | `ModDescriptorParser` / `FabricCompatBridge` / `HardDecodeService` / `SandboxRecordService` / `DescriptionMerger` | A4/A6/A2/A3 |
| S3 字节码转换 | `TransformerPipeline` / `MixinService` | A4/A5 |
| S4 注册表合并 | `RegistryMergeService.freeze()` | A4 |
| S5 事件总线初始化 | `EventBusFactory.create()` + 批量 subscribe | A4/A6 |
| S6 模组初始化 | `ModInitializer.invokeEntrypoints()` | A4/A6 |
| S7 加载完成 | —（运行时就绪，进入 vanilla 主流程） | A4/A7 |

---

## S1 发现（Discovery）

- **输入**：mods 目录路径列表（用户配置 + 默认目录）。
- **做什么**：枚举 jar/目录，算 sha1，去重，产出 `RawModHandle` 列表。
- **输出**：未排序的 `List<RawModHandle>`。
- **失败回退**：
  - 单个文件不可读 / 不是合法 zip → WARN-LOW，跳过该文件，继续。
  - mods 目录本身不存在 → WARN，按"无模组"继续（vanilla 裸启动合法）。
- **并行性**：✅ 可完全并行（IO 密集），并行结果按 modRoots 顺序 + 文件名字典序合并，保证确定性。

---

## S2 解析（Parse）

- **输入**：S1 的 `RawModHandle` 列表。
- **做什么**：
  1. 逐模组判定走 A6（Fabric）还是 A4 原生解析，得到 `ModDescriptor`；
  2. 调 A2 `HardDecodeService.decode` 得静态报告（26.x 无混淆，可与解析并行）；
  3. A3 沙箱会话在此阶段**异步启动**，不阻塞主流程——报告在 S6 之前回收即可（超时按 MG-Errors.md §5 降级）；
  4. `DescriptionMerger` 合并描述；
  5. 构建依赖图（REQUIRED/OPTIONAL/RECOMMENDED/INCOMPATIBLE + loadOrder），拓扑排序，产出**加载图**。
- **输出**：`List<MergedModDescription>`（已按加载图排序）。
- **失败回退**：
  - `mod.mirage.json` 缺失且非 Fabric 模组 → WARN-LOW？**不**：无法识别的 jar 直接 WARN（在周报提示）并跳过；只有 schema 合法但字段缺失才是 FATAL。
  - schema 非法 / specVersion 过新 / REQUIRED 缺失 / 版本范围不满足 / 依赖环 / INCOMPATIBLE 命中 → **FATAL**：中止本次启动，错误信息给出完整环路径或缺失依赖清单（MG-Errors.md §6）。
  - OPTIONAL 缺失 → WARN-LOW，模组继续。
- **并行性**：✅ 逐模组解析/A2 静态分析并行；依赖图拓扑排序本身单线程，但仅 O(n)，不构成瓶颈。**A3 沙箱全程异步并行**，其报告在 S5/S6 边界 join。

---

## S3 字节码转换（BytecodeTransform）

- **输入**：加载图 + 合并后描述（accessWideners、transforms、mixin 配置）。
- **做什么**：这是**懒执行**阶段——本阶段只完成配置注册与预热：
  1. A5 `registerMixinConfig` 登记全部 mixin 配置；
  2. A4 登记 accessWideners 与 MG 自定义 transforms；
  3. 真正的 `transform(className, bytes)` 发生在 **ClassLoader 加载每个类时**（首次 defineClass 前），顺序固定：accessWideners → MG transforms → Mixin 注入。
- **输出**：就绪的 `TransformerPipeline`（类加载钩子已挂接）。
- **失败回退**：
  - 配置阶段（register）找不到 mixin 配置文件 → FATAL。
  - 类加载时单个 transform 失败：
    - 目标类/方法不存在，且声明为硬要求 → FATAL；
    - `locals=CAPTURE_FAIL_SOFT` 或可选降级点 → WARN-LOW，该 transform 跳过，类按原字节码定义；
    - Mixin 注入失败的分级判定表 → MG-Errors.md §5。
- **并行性**：✅ transform 注册并行；实际字节码变换发生在类加载路径上，天然按类并行（不同类互不依赖），**同一类的多个 transform 严格串行**（顺序即上文固定顺序）。

---

## S4 注册表合并（RegistryMerge）

- **输入**：加载图中各模组的 `RegistryEntryDecl`（含 CONSTRUCTION 阶段条目）。
- **做什么**：按加载图顺序 `accept` 条目 → 冲突校验（MG-Spec §3.3）→ `freeze()` → 派发 `mirage:registry_frozen` 事件。
- **输出**：冻结的注册表视图（`lookup` 只读）。
- **失败回退**：
  - 跨 modid 同 id 冲突 → FATAL（不静默丢弃、不允许后覆盖先）；
  - 同 modid 重复注册同一 id → FATAL。
- **并行性**：❌ **必须单线程、按加载图顺序**执行。并行会让"先注册者获胜"变成随机结果。本阶段性能开销小，禁止并行优化。

---

## S5 事件总线初始化（EventBusInit）

- **输入**：全部合并后描述里的事件订阅声明 + A3 沙箱报告（此时 join 超时兜底）。
- **做什么**：
  1. `EventBusFactory.create()` 创建全局总线（此后不允许重建）；
  2. 逐模组按加载图顺序 `subscribe` 全部声明；
  3. 派发 `mirage:mods_loaded`。
- **输出**：就绪的全局 `EventBus`。
- **失败回退**：
  - 订阅方法类不存在 / 签名不符 → 单条订阅 FATAL（说明模组描述与字节码脱节）；
  - A3 沙箱报告超时未回 → WARN，以现有描述为准继续，该模组进 A9 周报。
- **并行性**：订阅收集可并行，但 **subscribe 写入总线必须按加载图顺序串行**（保证同 priority 下的执行顺序确定性）。

---

## S6 模组初始化（ModInit）

- **输入**：加载图 + 就绪的 EventBus/注册表。
- **做什么**：按加载图顺序、同层按 modid 字典序，实例化 entrypoint 并调用。
  - `pre_launch` 类入口最先；随后派发 `mirage:pre_launch`；
  - 普通 mod 构造 → 派发 `mirage:mods_loaded` 之后依次构造；
  - 服务端/客户端生命周期入口挂到对应 vanilla 钩子（SERVER_STARTING 等），不在本阶段立刻执行。
- **输出**：全部模组入口点构造完毕，运行时进入待命。
- **失败回退**：
  - entrypoint 构造抛异常 → **FATAL**（模组初始化失败不可恢复，跳过重放会导致注册表/事件状态半残）；
  - `UnsupportedApiFeature`（A6 报的不支持 API）在本阶段被实际调用 → 按 A6 分级：核心路径 FATAL，装饰性功能 WARN。
- **并行性**：❌ **严格串行**。构造顺序即加载图顺序，不允许并行——模组构造期常触碰共享注册表/总线，并行无法保证行为一致。

---

## S7 加载完成（Loaded）

- **输入**：S6 完成。
- **做什么**：
  1. 打印启动摘要：模组数、WARN/WARN-LOW 计数、FATAL=0 才放行；
  2. 专用服务器：进入 vanilla 服务端主循环；客户端（A7）：进入窗口/渲染初始化，随后派发 `mirage:client_starting`。
- **输出**：游戏正常运行。
- **失败回退**：本阶段无回退——此前阶段任何 FATAL 都不会走到这里。启动后运行时异常走游戏内崩溃流程，不在本生命周期内。
- **并行性**：❌ 交接给 vanilla 主流程。

---

## 并行性速查表

| 阶段 | 可并行？ | 说明 |
|---|---|---|
| S1 发现 | ✅ | IO 并行，结果字典序合并 |
| S2 解析 | ✅ | 逐模组并行；A3 沙箱全程异步 |
| S3 字节码转换 | ✅ 按类 | 同类内部串行，顺序固定 |
| S4 注册表合并 | ❌ | 必须串行保确定性 |
| S5 事件总线初始化 | ⚠️ 半 | 收集并行，写入串行 |
| S6 模组初始化 | ❌ | 严格串行 |
| S7 加载完成 | ❌ | 交接 vanilla |

---

## 失败回退总则

1. **单模组故障优先隔离**：S1/S2 的识别级错误只跳过该模组，不连坐。
2. **FATAL 一旦发生，不回退、不重试、不继续**：立即刷写日志，打印可操作错误（MG-Errors.md §6），退出。不存在"跳过这个模组再试一次启动"的静默自愈——那会掩盖问题。
3. **降级路径只有一条合法通道**：`confidence: LOW` 的动态描述缺失 → 用静态描述重放 + 启动后运行时再拦截；这是 MG 设计内的唯一降级，其余降级（如"跳过冲突注册"）一律禁止。
4. 任何模块发现自己处于"不知道回退到哪步"的状态 = 设计缺陷，按 FATAL 处理并上报 A1 修订本规范。
