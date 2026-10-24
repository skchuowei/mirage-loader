# LS-Interfaces.md — Lifecycle Starter (LS) 接口冻结

> 版本：**v0.1**（2026-10-24 冻结；改动需走 A1 修订流程并在 DECISION-LOG 登记）
> 维护：A1（MG-Spec）
> 配套：`MG-Lifecycle.md`（S1–S7 阶段语义）、`docs/plans/2026-10-24_lifecycle-starter-LS.md`（拆分计划）
> 实现位置：`lifecycle-starter/` 模块，包 `io.mirage.ls`（核心类型）+ `io.mirage.ls.stage`（S1–S7 阶段类）。

---

## 0. 设计原则（继承自 LS 计划，接口层再强调）

1. **MG 是唯一运行时**：LS 属于 MG 侧；LS 只做"决定当前该干什么"的调度，不实现 mod 兼容逻辑。
2. **阶段顺序冻结**：S1→S7 顺序由 `MG-Lifecycle.md` 冻结，LS 调度器只按注册顺序串行执行，禁止运行期重排、禁止按 mod 试错调整顺序。
3. **禁止静默失败**：FATAL 立即终止进程（exit 1），不允许跳过继续、不允许兜底重试。
4. **AG 只产出 MID，MG 只消费 MID**（phase 3 起）：LS 通过 `ModuleHandle` 调度 AG 模块，接口边界在本文件冻结。

---

## 1. LifecycleMode（启动模式）

```java
public enum LifecycleMode {
    NATIVE,     // 只加载 mirage 原生 mod（mod.mirage.json），跳过 AG 转译
    TRANSPILE,  // 非 mirage 原生 mod 先经 AG 转译为 MID 再加载（默认，兼容现有行为）
    RECORD      // 启用 MSR 沙盒录制路径（对齐 --sandbox-record，phase 1 仅透传）
}
```

- 解析规则：命令行 `--lifecycle-mode=native|transpile|record`；缺省 `TRANSPILE`。
- **非法值 = FATAL**（打印合法取值后 exit 1），不静默归默认——与 `--sandbox-record` 的"非法归 auto"策略不同：mode 决定整条启动路径，错拼必须响亮失败（设计原则 3）。
- 大小写不敏感。

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
| SKIPPED | 打印 reason，继续（例：NATIVE 模式下 AG 转译阶段跳过） |
| FATAL | 打印 `[LS] FATAL`，**立即 exit 1**，不执行后续阶段 |

## 4. LifecycleContext（阶段间共享上下文）

```java
public final class LifecycleContext {
    String[] args();                 // 原始命令行
    LifecycleMode mode();
    java.nio.file.Path gameDir();
    Map<String, Object> properties();// 阶段间共享状态（phase 2 起存 mods/classloader/eventbus 等）
}
```

- key 约定用阶段 id 前缀（如 `"S1.mods"`、`"S3.classLoader"`），具体 key 清单在 phase 2 迁移时补入本文件附录 A。
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
2. 每阶段前后打统一日志：`[LS] stage <id> <name> start` / `done (<ms> ms, <result>)`。
3. FATAL（含阶段抛出的未预期异常）→ 打印 `[LS] FATAL <id>: <detail>` + 堆栈 + **exit 1**。不存在"跳过这个阶段再试"。
4. 阶段永不返回（S7 移交 MC 主循环）视为正常完成路径。
5. 调度器自身不实现任何 mod 逻辑。

## 6. LegacyBootStage（phase 1 专用过渡阶段）

- `id="legacy"`，`execute` 整体委托 `io.mirage.launch.Launcher.main(args)`——phase 1 中 S1–S7 全部逻辑仍在 Launcher 单体里，LS 通过它保持行为 100% 不变。
- 正常路径它不返回（Launcher 内部移交 MC main 或 fatal 时 System.exit）；**若返回 = 异常路径**，产出 FATAL。
- **phase 2 迁移完成后删除本类**，并在本文件登记删除 commit。

## 7. S1–S7 阶段类（phase 2 迁移目标）

| 类 | id | 迁入的现有 Launcher 逻辑（phase 2 剪切目标） |
|---|---|---|
| `stage.DiscoveryStage` | S1 | `new Loader().discoverMods(...)` 及 mods 计数日志 |
| `stage.ParseStage` | S2 | manifest/entrypoint 解析归位（现散在 Loader/ModDiscoverer，迁移时只搬 Launcher 侧胶水） |
| `stage.TransformStage` | S3 | MirageClassLoader 创建、mod jar 挂载、mixin config 扫描、`MirageMixinBootstrap.init`、AppClassLoader addURL |
| `stage.RegistryMergeStage` | S4 | SharedConstants/Bootstrap、解冻全量 MappedRegistry、DeferredRegister nativeBackend、flush、BlockState 修补、重新冻结 |
| `stage.EventBusStage` | S5 | EventBus 创建、`Mirage.bootstrap` 门面、mod 实例 register |
| `stage.ModInitStage` | S6 | entrypoint 实例化循环、生命周期事件、NeoForge 生命周期桥接、ModPostInit |
| `stage.LoadedStage` | S7 | ClientStartingEvent、FMLClientSetupEvent、移交 `net.minecraft.client.main.Main`、setLoaded |

- phase 1 这些类**已建骨架但不在活动 pipeline 里**：`execute` 返回 `skipped("phase-2 迁移未完成")`，防止误接线改变行为。
- 迁移约束：搬代码时**逐阶段保持日志关键字不变**（冒烟对比 `latest.log` 关键字集合）；每阶段一个 commit，可独立 revert。

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

- 启动脚本仍进 `io.mirage.launch.Launcher`；phase 2 把 Launcher 降级为 thin wrapper（`Launcher.main` → `LifecycleStarter.main` 单向委托）后脚本不变。
- **反递归**：`Launcher.main` 与 `LifecycleStarter.main` 任意时刻只允许一个方向委托。phase 1 是 `LS → Launcher`；phase 2 后是 `Launcher → LS`。两者同时存在 = 死循环，接线时必须切换。
- `[LS] mode=<mode>` 是计划文档冻结的冒烟关键字，格式不得变。
- LS 不读 `mirage.mixins.json`、不碰 Mixin 内部；mixin 仍由 `MirageMixinBootstrap`（S3 内）管理。

---

## 附录 A · properties key 清单（phase 2 补填）

> 迁移 S1–S7 时逐个登记，未登记的 key 视为私有实现细节，禁止跨阶段引用。

（暂空）
