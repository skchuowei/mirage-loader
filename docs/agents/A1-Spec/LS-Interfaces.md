# LS-Interfaces.md — Lifecycle Starter (LS) 接口冻结

> 版本：**v0.2**（2026-10-25；phase 2 迁移完成修订。v0.1 于 2026-10-24 冻结）
> 维护：A1（MG-Spec）
> 配套：`MG-Lifecycle.md`（S1–S7 阶段语义）、`docs/plans/2026-10-24_lifecycle-starter-LS.md`（拆分计划）
> 实现位置：`lifecycle-starter/` 模块，包 `io.mirage.ls`（核心类型）+ `io.mirage.ls.stage`（S1–S7 阶段类）。

---

## 0. 设计原则（继承自 LS 计划，接口层再强调）

1. **MG 是唯一运行时**：LS 属于 MG 侧；LS 只做"决定当前该干什么"的调度，不实现 mod 兼容逻辑。
2. **阶段顺序冻结**：S1→S7 顺序由 `MG-Lifecycle.md` 冻结，LS 调度器只按注册顺序串行执行，禁止运行期重排、禁止按 mod 试错调整顺序。
3. **禁止静默失败**：FATAL 立即终止进程（exit 1），不允许跳过继续、不允许兜底重试。
4. **AG 只产出 MID，MG 只消费 MID**（phase 3 起）：LS 通过 `ModuleHandle` 调度 AG 模块，接口边界在本文件冻结。
5. **保序迁移**（phase 2 新增）：从 Launcher 单体迁入阶段类时，逐阶段保持日志关键字与 mod 可观察时序不变——冒烟等价优先于规范表语义纯度；与规范表的偏差记入附录 B，不静默修正。

---

## 1. 启动周期与周期参数

### 1.1 LifecycleMode（启动周期）

```java
public enum LifecycleMode {
    C1,      // 直接启动（MD 直启）：不走 AG、不走 MRS，只消费 mirage 原生描述符
    C2,      // 游戏 mod 转译（硬解码 + MRS 含录制）——缺省周期，兼容现有行为
    C3,      // 游戏 mod 转译（硬解码 + MRS 不含录制）；解析失败按 ParseFailPolicy 处理
    CUSTOM   // 自定义：按 mirage.ls.json（LsConfig）配置
}
```

- 解析规则：命令行 `--lifecycle-mode=C1|C2|C3|custom`；大小写不敏感；**缺省 `C2`**。
- 兼容别名（v0.1 三模式迁移用）：`native→C1`、`transpile→C2`、`record→C2`。
- **非法值 = FATAL**（打印合法取值后 exit 1），不静默归默认——周期决定整条启动路径，错拼必须响亮失败（设计原则 3）。
- 四周期的**阶段序列相同**（S1–S7 全集；C4 可按配置禁用 S2–S6，见 1.3），差异在录制开关 / 失败策略 / 原生过滤，由各阶段读 ctx 决策：

| 周期 | MRS 录制缺省 | ParseFailPolicy 缺省 | 原生过滤 |
|---|---|---|---|
| C1 | off | skip | **是**（非 mirage 原生 mod WARN 滤除） |
| C2 | auto | skip | 否 |
| C3 | off | **confirm** | 否 |
| CUSTOM | auto | skip | 否 |

### 1.2 ParseFailPolicy（解析失败策略）

```java
public enum ParseFailPolicy { SKIP, CRASH, CONFIRM }
```

适用场景：C3（及 C4 显式声明）下 LS 判定 mod「无法完全解析」时的行为。
当前已接线的判定信号：**主类定位失败**（`manifest.mainClass` 与 fabric entrypoints 均为空，S2 检测）。
其余判定标准（MBD 硬解码覆盖率、MID 必填校验、依赖满足）随 AG 解耦（phase 3）接入，阈值 `minCoverage` 已可由 C4 配置预置。

| 策略 | 行为 | 备注 |
|---|---|---|
| `SKIP` | 跳过该 mod，继续启动，记 WARN | C1/C2 缺省；`skip` 显式开启才用于 C3——违反原则 3，故必须带 WARN |
| `CRASH` | 立即崩溃（FATAL exit 1），输出缺失项清单 | 无人值守（`System.console()==null`）时 confirm 自动降级为 crash |
| `CONFIRM` | 控制台列出无法解析的 mod + 原因，等待确认（Y 继续 / S 跳过这些 mod / N 中止） | C3 缺省 |

- 命令行覆盖（调试用）：`--lifecycle-parse-fail=skip|crash|confirm`；**非法值 = FATAL**。
- 优先级：CLI 覆盖 > C4 配置 > 周期模板缺省。
- 被跳过集合由 S2 写入 `ctx.skippedMods()`，S6 初始化循环据此跳过（不重复告警）。
- 本枚举由 A1/MIS 冻结，禁止各模块自定义。

### 1.3 LsConfig（C4 自定义配置，`mirage.ls.json`）

结构（计划文档冻结）：

```json
{
  "cycle": "C2",
  "ag": ["harddecode", "neoforge-compat"],
  "record": { "mode": "auto" },
  "parse": { "failPolicy": "confirm", "minCoverage": 0.8 },
  "stages": { "transform": { "enabled": false } }
}
```

| 字段 | 必填 | 合法值 | 说明 |
|---|---|---|---|
| `cycle` | 是 | `C1`/`C2`/`C3` | **不允许 `custom`**——禁止配置递归 |
| `ag` | 否 | 字符串数组 | AG 启用集；phase 3 `ModuleHandle` 消费，本期仅登记 |
| `record.mode` | 否 | `off`/`auto`/`force` | 缺省继承周期模板 |
| `parse.failPolicy` | 否 | `skip`/`crash`/`confirm` | 缺省继承周期模板 |
| `parse.minCoverage` | 否 | `0~1` 数字 | MBD 覆盖率阈值；未设置 = -1 |
| `stages.<id>.enabled` | 否 | 布尔 | 阶段 id 用 `s1..s7` 或 `discovery..loaded` 别名（大小写不敏感）；**S1/S7 禁用于校验期即拒**（发现/移交是启动必经阶段，禁用=静默改变启动语义，违反原则 3） |

- 配置文件路径：`--lifecycle-config=<file>` 优先，否则 `<gameDir>/mirage.ls.json`；**缺失 = FATAL**。
- 校验失败一律 FATAL（exit 1）并给出可操作信息；未知顶层 key 仅 WARN（前向兼容）。
- **零依赖**：内置最小 JSON 解析器（对象/数组/字符串/数字/字面量 + `\uXXXX`），不引 Gson——LS 运行时 classpath 上没有任何 JSON 库，且本结构足够扁平。

## 2. Stage（阶段接口）

```java
public interface Stage {
    String id();      // "S1".."S7"；内建非标准阶段用描述性 id（如 "legacy"）
    String name();    // 人类可读名（日志用）
    StageOutcome execute(LifecycleContext ctx);
}
```

契约：

- `execute` **同步**执行；S7/移交类阶段允许**永不返回**（MC 主循环阻塞在内），调度器不得因此视为异常。
- 阶段内可恢复错误按 `MG-Lifecycle.md` 各阶段的失败回退表处理（WARN-LOW 跳过 / WARN 继续 / FATAL）。
- 未预期的异常：阶段**不要自己吞**，直接抛出；调度器统一转 FATAL（含堆栈）。
- 阶段之间只通过 `LifecycleContext` 传递状态，**禁止静态变量传状态**（便于后续多轮/测试隔离）。

## 3. StageOutcome（阶段结果）

```java
public enum StageResult { SUCCESS, SKIPPED, FATAL }
public record StageOutcome(StageResult result, String detail) {
    public static StageOutcome success();
    public static StageOutcome skipped(String reason);
    public static StageOutcome fatal(String message);
}
```

| 结果 | 调度器行为 |
|---|---|
| SUCCESS | 记录耗时，进入下一阶段 |
| SKIPPED | 打印 reason，继续（例：C4 配置禁用某阶段，或 phase 1 未迁移占位） |
| FATAL | 打印 `[LS] FATAL`，**立即 exit 1**，不执行后续阶段 |

## 4. LifecycleContext（阶段间共享上下文）

```java
public final class LifecycleContext {
    String[] args();                 // 原始命令行
    LifecycleMode mode();
    LsConfig config();               // C4 配置；非 CUSTOM 为 null
    java.nio.file.Path gameDir();
    io.mirage.api.env.Dist dist();   // -Dmirage.dist / MIRAGE_DIST，缺省 CLIENT
    String sandboxMode();            // off/auto/force（flag > C4 > 周期缺省）
    boolean midCacheForceRegen();    // --mid-cache-force-regen
    boolean midCacheVerbose();       // --mid-cache-verbose
    ParseFailPolicy failPolicy();    // CLI > C4 > 周期缺省；无人值守 confirm→crash
    Set<String> skippedMods();       // S2 按策略判定的跳过集合，S6 消费
    Map<String, Object> properties();// 阶段间共享状态
    Map<String, String> summary();   // 调试快照
    public static Path resolveGameDir(String[] args);
}
```

- v0.2 起：启动装配期解析的全部环境量（物理端 / 沙盒录制开关 / MID 缓存开关 / 失败策略）统一由本类持有——「LS 决定当前该干什么」的决策集中在这一层，阶段类不再各自解析参数。
- properties key 约定用阶段 id 前缀，正式清单见**附录 A**；未登记的 key 视为阶段私有，禁止跨阶段引用。
- `gameDir` 解析规则与现有 Launcher `parseGameDir` 一致（`--gameDir <path>`，缺省 `.`）。

## 5. LifecycleScheduler（调度器）

```java
public final class LifecycleScheduler {
    void add(Stage stage);           // 仅允许启动装配期调用；执行期禁止增删
    void run(LifecycleContext ctx);  // 按注册顺序串行执行
}
```

调度契约：

1. **严格串行、按注册顺序**；一次 `run` 内 pipeline 不可变。
2. 每阶段前后打统一日志：`[LS] stage <id> <name> start` / `done (<ms> ms)`。
3. FATAL（含阶段抛出的未预期异常）→ 打印 `[LS] FATAL <id>: <detail>` + 堆栈 + **exit 1**。不存在"跳过这个阶段再试"。
4. 阶段永不返回（S7 移交 MC 主循环）视为正常完成路径。
5. 调度器自身不实现任何 mod 逻辑。

## 6. LegacyBootStage（phase 1 专用过渡阶段）——**已删除**

- v0.1 形态：`id="legacy"`，`execute` 整体委托 `io.mirage.launch.Launcher.main(args)`——phase 1 中 S1–S7 全部逻辑仍在 Launcher 单体里，LS 通过它保持行为 100% 不变。
- **v0.2（phase 2 迁移完成）：本类已删除**（`lifecycle-starter/src/main/java/io/mirage/ls/LegacyBootStage.java`），删除动作随 phase 2 迁移提交登记；此后 LS pipeline 只含 S1–S7 真实阶段类，活动路径不再经过任何委托跳转。

## 7. S1–S7 阶段类（phase 2 已迁移完成）

| 类 | id | 迁入的 Launcher 逻辑（保序，逐行同日志） |
|---|---|---|
| `stage.DiscoveryStage` | S1 | `Loader.discoverMods` + mods 计数/命名空间日志；**C1 原生过滤**（`ir != null \|\| (fabricEntrypoints == null && !fromNeoForge)`，非原生 WARN 滤除） |
| `stage.ParseStage` | S2 | **公共 API 门面装配**（EventBus / `Mirage.bootstrap` / `ModLoadingContext.setConfigDirectory`）+ **ParseFailPolicy 应用**（主类定位失败检测：SKIP→WARN+skippedMods；CRASH→FATAL；CONFIRM→控制台 Y/S/N，无人值守降级 FATAL） |
| `stage.TransformStage` | S3 | MirageClassLoader 创建、mod jar addJar、`.mixin.json` 扫描、`MirageMixinBootstrap.init`、AppClassLoader ucp addURL（失败仅 err 打印不 FATAL） |
| `stage.RegistryMergeStage` | S4 | `RegistryWindow.open`：Bootstrap.bootStrap → 遍历 `BuiltInRegistries.REGISTRY` 解冻全部 MappedRegistry → `DeferredRegister.setHookListener` + `setNativeBackend`（creativemodetab intrusive holder / item BlockItem 绑定 / bug#1 精准 remove） |
| `stage.EventBusStage` | S5 | **不变量检查点**：校验 `S2.eventBus`/`S1.mods`/`S3.classLoader` 在 ctx 中，缺一 FATAL（见附录 B 偏差 1/2） |
| `stage.ModInitStage` | S6 | entrypoint 实例化循环（端侧过滤 → main/side 入口 → 构造 → register → Construct/PreInit/Init 事件）、DeferredRegister flush、6× NeoForge 生命周期桥接、ModPostInit 广播、**注册表关窗**（BlockState 修补 + bug#1 兜底 + 重新冻结） |
| `stage.LoadedStage` | S7 | getResources 调试探测、`setStage(启动 Minecraft 客户端)/95`/mod 列表、ClientStartingEvent、FMLClientSetupEvent 桥接、TCCL 切换 + 反射调 `net.minecraft.client.main.Main.main`、setLoaded |

- 共享支撑（同包）：`StageSupport`（guard 致命错误外壳 + 跨阶段状态读取）、`ModEntrypoints`（main/side 入口列表，S2/S6 共用防漂移）、`NeoLifecycle`（postNeoLifecycle / constructModInstance / callOnInitialize / findMethod，自 Launcher 私有方法保序移植）、`RegistryWindow`（S4 注册表窗口 + S6 尾关窗）。
- 迁移约束（已执行）：搬代码时**逐阶段保持日志关键字不变**（冒烟对比 `latest.log` 关键字集合）；与规范表的时序偏差见附录 B。
- 异常外壳：S1/S3–S7 用 `StageSupport.guard`（打印 `[Mirage] 启动 Minecraft 时发生致命错误:` + 堆栈 → FATAL，与拆分前 Launcher 单体 try/catch 一致）；**S2 例外**——用显式 try/catch，避免 guard 恒返回 success 吞掉 ParseFailPolicy 的 FATAL 结论。

## 8. ModuleHandle（phase 3，AG 模块接入——本节仅冻结形状，不实现）

```java
public interface ModuleHandle {
    String moduleId();                       // 如 "MTN"、"MTF"、"MBD"
    void contribute(StageRegistry registry); // AG 模块向 S2/S3 等阶段贡献 contributor
}
public interface StageRegistry {
    void registerContributor(String stageId, StageContributor c);
}
```

- 发现机制：`ServiceLoader`（`META-INF/services/io.mirage.ls.ModuleHandle`）。
- AG 模块**只能通过 contributor 挂进对应阶段**，不能自建阶段、不能改顺序。
- MG 与 AG 互相不 import 对方实现类；MID 是唯一数据交换格式。

## 9. 相容性硬约束

- 启动脚本仍进 `io.mirage.launch.Launcher`；**phase 2 已完成降级**：`Launcher.main` 只做进程级准备（installLogTee / banner / MirageEarlyWindow.start），随后单向委托 `io.mirage.ls.LifecycleStarter.main(args)`，脚本 / Main-Class 不变。
- **反递归**：`Launcher.main` 与 `LifecycleStarter.main` 任意时刻只允许一个方向委托。phase 1 是 `LS → Launcher`；**phase 2 后是 `Launcher → LS`**（已切换，LegacyBootStage 删除即标志）。两者同时存在 = 死循环。
- Gradle 依赖同向：`launcher/mirage-launch` 声明 `implementation(project(":lifecycle-starter"))`；`lifecycle-starter` **不得**依赖 launcher（fat jar 经 runtimeClasspath 自动 shade）。
- `[LS] mode=` 是计划文档冻结的冒烟关键字，格式不得变。
- LS 不读 `mirage.mixins.json`、不碰 Mixin 内部；mixin 仍由 `MirageMixinBootstrap`（S3 内）管理。

---

## 附录 A · properties key 清单（phase 2 已填）

| key | 类型 | 写入 | 读取 | 说明 |
|---|---|---|---|---|
| `S1.mods` | `List<ModContainer>` | S1 | S2/S5/S6/S7 | 发现阶段产出的 mod 列表（C1 已过滤） |
| `S2.eventBus` | `io.mirage.eventbus.EventBus` | S2 | S5/S6/S7 | 公共事件总线（门面留 S2，见附录 B 偏差 1） |
| `S2.provider` | `io.mirage.loader.runtime.MirageProviderImpl` | S2 | —（登记备用） | `Mirage.bootstrap` 的 provider 快照 |
| `S3.classLoader` | `io.mirage.loader.MirageClassLoader` | S3 | S4/S5/S6/S7 | mod 类加载器 |
| `S4.registryWindow` | `io.mirage.ls.stage.RegistryWindow` | S4 | S6 | 注册表窗口句柄（S6 尾关窗） |
| `S4.pendingRegisters` | `List<DeferredRegister<?>>` | S4 | S6 | hook 收集的待 flush 注册器 |

非 properties 的跨阶段状态：`ctx.skippedMods()`（S2 写、S6 读）。

## 附录 B · 保序迁移偏差记录（MG-Lifecycle 规范表 vs 实际时序）

phase 2 迁移遵循设计原则 5（冒烟等价优先）：以下三处与 `MG-Lifecycle.md` 规范表的语义归属存在偏差，均为**保序决定**——拆分前 Launcher 单体的真实执行顺序即现状，迁移不改变 mod 可观察行为：

1. **公共 API 门面（EventBus / `Mirage.bootstrap` / `ModLoadingContext`）落 S2**（规范表列 S5）。原因：拆分前 Launcher 在 mod 解析后、类加载器创建前建门面；且 S2 的入口缺失判定（`sideClasses`/`mainClasses`）依赖 provider 就绪。S5 因此退化为不变量检查点。
2. **`eventBus.register(instance)` 留 S6 实例化循环内**（规范表列 S5）。原因：register 与 Construct/PreInit 事件按 mod 交替发生，提前集中注册会改变跨 mod 事件次序。
3. **注册表关窗（BlockState 修补 + 重新冻结）在 S6 尾**（规范表列 S4）。原因：必须等全部 mod 内容注册完成才能冻结；S4 只负责开窗（解冻 + native backend），窗口句柄经 `S4.registryWindow` 传至 S6。

> 冒烟验收标准：迁移前后 `latest.log` 的 `[Mirage]` 关键字集合一致（阶段日志逐行同文）；`[LS] stage Sx <name> start/done` 为新增行，不计入差异。
