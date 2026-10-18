# NeoForge mod 提前沙盒录制（MSR-neo）

> 日期：2026-10-17
> 范围：`core/mirage-loader` 运行时发现管线，MID 磁盘缓存第一步（`2026-10-17-mid-cache.md`）的下游扩展
> 前置：本计划只做设计文档，不写 Java 代码；实施要等 MID 缓存第一步落地 + 工作区另一会话 WIP 提交后再开。
>
> **注意（2026-10-18补充）**：MC 26.2原版CreativeModeInventoryScreen本身已经内置了完整的多标签分页/翻页按钮逻辑，不需要我们自己实现分页。沙盒阶段要自然观察原版分页按钮的触发条件、分页流程，不要自己重复造分页逻辑。

## 目标

在真正进入 MC 主循环之前，让每个 NeoForge mod 的主类**在隔离沙盒里跑一遍构造函数（含静态初始化器）**，把 mod 自己声明期望的版本区间、以及它实际触达的 API 表面录进 MID 缓存：

- `recorded_loader_version`：mod 在 `neoforge.mods.toml` 的 `[[dependencies.neoforge]]` 里声明期望的 NeoForge 版本区间；
- `recorded_mc_version`：mod 在 `[[dependencies.minecraft]]` 里声明期望的 MC 版本；
- `touched_apis: [...]`：构造函数里实际调用了哪些 NeoForge / Mirage API（类名 + 方法签名）；
- `registered_events: [...]`：构造函数里通过 event bus `addListener` / `register` 挂上去的事件类名；
- `failed: boolean` + `error: string`：沙盒构造是否崩、崩在哪。

这些字段写进 `.mid-cache/<mod-id>+<jar-sha256>.json` 的新 `recorded` 子对象，跟第一步的 `Detected` 字段共存。第二次启动命中缓存就直接用，不再跑沙盒。

**可验证标准**：ferritecore 第一次启动后，`.mid-cache/...json` 里能看到 `recorded.recorded_loader_version` / `recorded.touched_apis` 字段；第二次启动日志打印"沙盒录制命中缓存"，没有重新跑构造函数的耗时。

## 现状分析

读 `NeoForgeModDetector.java`、`ModDiscoverer.java`、`api/mirage-api/src/main/java/io/mirage/api/registry/DeferredRegister.java`、`MG-设计脉络与命名体系.md`（第三节"沙盒给 mod 报什么版本"、第七节"AG/MG 分离"、第九节 MSR/MSJ 命名）后确认：

1. **当前 NeoForge mod 启动路径**：`ModDiscoverer` 扫到 `neoforge.mods.toml` → `NeoForgeModDetector.detect(jf)` 解析 toml + ASM 扫 class 找 `@Lnet/neoforged/fml/common/Mod;` 主类 → 返回 `Detected(id, version, displayName, mainClass)` → Launcher 后续 `Class.forName(mainClass)` 反射构造。
2. **当前的"录制"为零**：构造函数跑完就跑了，它注册了什么、要什么版本、碰了哪些 API，全都没记。Launcher 里现在是靠 `DeferredRegister.setHookListener(pendingRegisters::add)` 这种 hook 在**真实启动流程里**被动收集注册项——这等于把"录制"和"运行"混在一起，违反 AG/MG 分离（第七节：AG 编译期转译，MG 运行期加载，两者通过 MID 解耦）。
3. **`api/mirage-api` 里已有 `io.mirage.api.registry.DeferredRegister`**，但那是给 MG 原生 mod 用的；NeoForge mod jar 字节码里绑死的是 `net.neoforged.neoforge.registries.DeferredRegister`，类名不同，沙盒里 mod 不可能碰到 Mirage 自己的 DeferredRegister。
4. **MG 设计文档第三节明确要求**：沙盒录制时给 mod 报它声明期望的版本，让 mod 走最完整分支；MID 记录 `recorded_loader_version` / `recorded_mc_version`；运行时重放按 mod 上下文伪装；冲突无法隔离就报错，不静默。
5. **MID 缓存第一步**（`2026-10-17-mid-cache.md`）已经规划了 `MidCache.Entry` 结构（`specVersion / agVersion / mcVersion / jarSha256 / cachedAt / detected`），本计划只是在它基础上加 `recorded` 可选子对象，不改老字段。
6. **工作区当前阻塞**：另一会话正在改 `ModDiscoverer.java` / `NeoForgeModDetector.java` / `Launcher.java` / `EventBus.java` / `IrDescriptorParser.java` / `MirageClassLoader.java` / `MirageDebug.java` / `MirageTransformer.java` 等未提交。本计划实施前必须等这批改动落地。

## 改动文件清单

| 文件 | 改什么 |
|---|---|
| `core/mirage-loader/src/main/java/io/mirage/loader/SandboxRecorder.java` | **新建（MSR 主控）**。在 launcher 进程内起一个隔离的 `MirageClassLoader` 快照加载 mod jar，反射调 `@Mod` 主类构造函数，拦截 NeoForge API 调用，收集 `RecordingResult`，2 秒超时熔断。 |
| `core/mirage-loader/src/main/java/io/mirage/loader/fake/` 包 | **新建目录**。fake NeoForge API 表面：`net.neoforged.fml.common.Mod`（注解占位）、`net.neoforged.fml.ModContainer`、`net.neoforged.fml.loading.FMLJavaModLoadingContext`、`net.neoforged.bus.api.IEventBus`、`net.neoforged.neoforge.registries.DeferredRegister`、`net.neoforged.neoforge.registries.RegistryObject`。每个方法都是 recording proxy：不真做事，但把"被调了谁"记进 `touched_apis`。 |
| `core/mirage-loader/src/main/java/io/mirage/loader/MidCache.java` | 扩展 `Entry`，加可选 `recorded` 子对象（Gson 可空，缺字段视为未录制）。specVersion 从 `"0.1"` 升到 `"0.2"`，但**老字段一个不动**。 |
| `core/mirage-loader/src/main/java/io/mirage/loader/NeoForgeModDetector.java` | 解析 `neoforge.mods.toml` 里 `[[dependencies.neoforge]]` / `[[dependencies.minecraft]]` 的 `versionRange` 字段，填到 `Detected` 或 `RecordingResult`。不改 `detect()` 主流程。 |
| `core/mirage-loader/src/main/java/io/mirage/loader/ModDiscoverer.java` | 在缓存 miss、`detect()` 写完 `Detected` 之后，调 `SandboxRecorder.record(jar, detected)`，把结果塞进 `Entry.recorded`；缓存命中且 `recorded != null` 时跳过沙盒。 |
| `launcher/mirage-launch/src/main/java/io/mirage/launch/Launcher.java` | 加 `--sandbox-record=off\|auto\|force` flag（默认 auto：缺 recorded 才录；off：完全不录；force：无视缓存强制重录）。 |
| `scripts/verify_sandbox_neo.ps1` | **新建**。冒烟：删 `.mid-cache/` → 首启 dry-run 5 秒 kill → 检查 ferritecore 的 MID json 里 `recorded.recorded_loader_version` 非空、`recorded.touched_apis` 是数组（哪怕为空）、`recorded.failed == false` → 二启 → 日志打"沙盒录制命中缓存"。 |
| `docs/更新日志.md` | 实施完在顶部追加一条。 |
| `docs/改动登记表.md` | 把本行从"进行中"移到"已完成"，补 commit 哈希。 |

**不改动**：`api/mirage-api/` 下 Mirage 自己的 DeferredRegister（那是给 MG 原生 mod 用的，NeoForge mod 字节码绑的不是它）；`modules/mirage-neoforge-compat/`（静态 TOML 分析模块跟运行时沙盒是两回事）；`mc-shili/`、`mods-library/`（规则 9）。

## 每处改动要点

### 1. 沙盒隔离方案：launcher 进程内子 ClassLoader，**不 fork 子 JVM**

**结论**：在 launcher 进程里起一个专用的隔离 ClassLoader（`MirageClassLoader` 的一个一次性实例，parent = app classloader，但 parent-first 模式反过来——mod 的类自己加载，只有 fake NeoForge API 委托 parent）加载 mod jar，构造主类，跑完记录就 close 丢引用。**不 fork 子 JVM**。

理由：
- **fork 子 JVM 的代价不可接受**：JVM 冷启动 1–2 秒 / mod，整合包 200 个 NeoForge mod 就是 5–7 分钟额外首启时间；还要管子进程通信、stderr 重定向、崩溃回收、退出码传递。MID 缓存虽然二启为 0，但**首启必须全录**，不是按需录。
- **子 ClassLoader 的好处**：毫秒级，内存里起，mod 构造函数崩了直接 `try/catch (Throwable)`，主 launcher 不受影响；ClassLoader 丢了它加载的类就一起 GC；跟 `MidCache` 同进程，结果直接在内存里，不用 IPC。
- **静态污染对策**：mod 构造函数如果起了非 daemon 线程、注册了 shutdown hook、或改了自己类的 static 字段，这些污染留在主进程。对策：① 沙盒里跑完立刻 `close()` ClassLoader 并把引用全丢；② 对 mod 构造函数加 2 秒超时（丢到一个独立线程跑，主线程 `join(2000)`，超时就认为这个 mod "构造函数有副作用"，降级到 `failed=true, error="timeout"`）；③ mod 的 static 字段污染在 ClassLoader close 后随类一起卸载，不会传到 app classloader。

### 2. NeoForge API fake：必须塞一层 fake 表面

mod jar 里 `@Mod` 构造器典型长这样：
```java
@Mod("ferritecore")
public class FerriteCore {
    public static final DeferredRegister<Item> ITEMS =
        DeferredRegister.create(Items.ITEM, "ferritecore");   // ← 绑 net.neoforged.neoforge.registries.DeferredRegister
    public FerriteCore(FMLJavaModLoadingContext ctx) {       // ← 绑 net.neoforged.fml.loading.FMLJavaModLoadingContext
        ctx.getModEventBus().addListener(this::commonSetup); // ← 绑 net.neoforged.bus.api.IEventBus
    }
}
```

- **问题 2 回答**：mod 构造器里 `DeferredRegister.create(...)` 触达的是 `net.neoforged.neoforge.registries.DeferredRegister`，**不是** `io.mirage.api.registry.DeferredRegister`。两者包名不同，字节码里写死了 NeoForge 的类名。
- **结论**：沙盒里必须塞一个 **fake NeoForge API 表面**（`core/mirage-loader/.../fake/` 包），提供上述类的空壳实现。每个方法都是 recording proxy：
  - `DeferredRegister.create(...)` → 记一条 `touched_apis.add("net.neoforged.neoforge.registries.DeferredRegister.create")`，返回一个 fake `RegistryObject`；
  - `IEventBus.addListener(method)` → 反射拿 method 的参数类型，记一条 `registered_events.add(paramType.getName())`；
  - `FMLJavaModLoadingContext.get().getModEventBus()` → 返回 fake event bus；
  - `@Mod` 注解本身是 RUNTIME 保留，class 文件必须能被 ClassLoader 看到（fake 包里放一个同名注解类，RetentionPolicy=RUNTIME）。
- fake 表面**不真注册到 MC 的 MappedRegistry**，不真 post 事件——只是让 mod 构造函数不抛 `NoClassDefFoundError`，同时把它"调过谁、挂了哪些事件"录下来。这是 AG 编译期转译的雏形。

### 3. 录制深度：只跑构造函数 + 静态初始化器，post 最小事件集合

**最小集合 = 无额外事件 post**。只做两件事：
1. 触发 mod 主类的**静态初始化器**（`Class.forName(mainClass, false, sandboxLoader)` → 不 initialize；再 `Class.forName(mainClass, true, sandboxLoader)` initialize，这一步会跑所有 `static {}` 块和 static final 字段初始化，包括 `DeferredRegister.create(...)` 的静态调用）；
2. 反射调 `@Mod` 主类的**构造函数**（NeoForge 现代版构造器签名是 `FMLJavaModLoadingContext ctx`，老版是无参，两种都试）。

**不 post** `FMLCommonSetupEvent` / `FMLClientSetupEvent` / `InterModEnqueueEvent` 等任何后续生命周期事件。

理由：
- 构造函数 + 静态初始化器是 mod 唯一的"必跑"代码，且 90% 的 `DeferredRegister.create` / `addListener` 调用都在这里；
- setup 阶段会触达 MC 注册表、网络、资源加载——这些在沙盒里没有 MC 主类，跑了也是 `NoClassDefFoundError`，反而会误标 `failed=true`；
- `DeferredRegister.register("id", supplier)` 传的是 lambda（`Supplier<Item>`），构造函数里只调了 `register(id, supplierRef)` 这个方法本身，lambda 体**不执行**（直到 MC 真正 freeze registry 才跑）。所以本步只录"注册了哪些 id"，不录 lambda 体里 `new Item()` 的具体参数——这是已知限制，后续 MSR 第二步再扩。

### 4. 存储格式：扩展 MidCache.Entry，加 recorded 可选子对象

```java
public static class Entry {
    // ---- 第一步已有，一字不动 ----
    public String specVersion;     // 本步从 "0.1" 升到 "0.2"
    public String agVersion;
    public String mcVersion;
    public String jarSha256;
    public long   cachedAt;
    public NeoForgeModDetector.Detected detected;

    // ---- 本步新增，可空 ----
    public Recorded recorded;       // null = 老缓存未录制过
}

public static class Recorded {
    public String recorded_loader_version;   // mod 声明期望的 NeoForge 版本区间，如 "[21.4,)"
    public String recorded_mc_version;      // mod 声明期望的 MC 版本区间，如 "[26.2,)"
    public List<String> touched_apis;         // 构造函数里触达的 API 全限定名（类名+方法名）
    public List<String> registered_events;    // addListener 挂上去的事件类全限定名
    public int deferred_register_count;       // 构造里 create 了几个 DeferredRegister（从 touched_apis 聚合）
    public long record_duration_ms;           // 沙盒耗时
    public boolean failed;                    // 构造是否崩 / 超时
    public String error;                      // failed=true 时填异常类名 + message；否则 null
}
```

**老缓存兼容**：老的 `0.1` 缓存 json 没有 `recorded` 字段，Gson 反序列化时 `recorded = null`。`ModDiscoverer` 看到 `recorded == null` 就**当未录制，重跑沙盒补录**，跑完把 recorded 写回 json（specVersion 顺手升到 "0.2"）。不报错、不崩。

### 5. 失败降级：不静默失败，标 `failed=true` 但不拒绝加载

按"不静默失败"原则：
- mod 构造函数在沙盒里抛任何 `Throwable`（包括 `NoClassDefFoundError` / `ExceptionInInitializerError` / 2 秒超时）→ catch 住，填 `recorded.failed = true`、`recorded.error = 异常类名 + message 截断 500 字符`，**继续走正常启动流程**（让 mod 在真实 MC 环境里再试一次，崩了那是运行时 bug，不在这里 fail-fast）。
- 沙盒 ClassLoader close 失败 / fake API 未覆盖某个 mod 碰的类 → 同上降级，error 写明缺哪个类。
- 版本区间 toml 解析失败 → `recorded_loader_version` / `recorded_mc_version` 留空字符串，`failed=false`（这不是 mod 的错）。
- **不静默**：所有 `failed=true` 的 mod 在 `latest.log` 里打 `[Mirage][Sandbox] WARN mod=<id> 录制失败: <error>，降级为只存元数据`，且 json 里 `failed=true` 持久化，下次启动不再重录（避免每次启动都崩一次）。
- **反例（不选）**：整个 mod 拒绝加载 → 一个 mod 沙盒崩了，所有 mod 都不能用，太脆，违反"录制是探测、不是硬前提"。

### 6. 跟 MID 缓存第一步的关系

- **不改第一步文件格式**：`Entry` 的 `specVersion / agVersion / mcVersion / jarSha256 / cachedAt / detected` 六个字段一字不动；
- **只追加** `recorded` 可选字段；
- 第一步实施完后，老缓存自然缺 `recorded`，第二次启动自动补录（重跑一次沙盒，把字段填回去，specVersion 从 "0.1" 刷到 "0.2"）；
- 校对规则不变：还是 `agVersion == AG_VERSION && jarSha256 == 当前哈希` 两件套；`recorded` 缺失不触发重 `detect()`，只触发"补录"。

## 回归测试影响面

- **现有行为不变**：
  - IR v0.1 / mirage.mod.json / fabric.mod.json 三条路径完全不动；
  - NeoForge mod 识别结果跟原来一字不差（沙盒只是追加录制，不改 `Detected` 字段）；
  - 沙盒抛任何异常都降级，不影响 mod 列表构造；
  - `--sandbox-record=off` 可以完全关掉沙盒，行为跟本计划落地前一模一样。
- **新增用例**：`scripts/verify_sandbox_neo.ps1`——删缓存、首启 dry-run、检查 ferritecore 的 MID json 里 `recorded.recorded_loader_version` 非空、`recorded.touched_apis` 是数组（哪怕为空）、`recorded.failed == false`；二启日志打"沙盒录制命中缓存"。
- **可能打破的旧行为**：mod 构造函数如果现在**依赖**真实 MC 环境（比如静态初始化器就调 `Block.register(...)`），沙盒里会 `NoClassDefFoundError`——但 catch 住降级 `failed=true`，不影响真实启动。
- **人工冒烟**：双击 `启动 26.2-mirage.bat` 进 MC 主菜单，无新增崩溃；ferritecore 那行旁边打印"沙盒录制完成 (xx ms, N apis, M events)"。

## 验证步骤

按顺序跑（本计划是文档，不实施；以下是实施时要走的步骤）：

1. **前置基线**：MID 缓存第一步已实施并验证；工作区另一会话 WIP 已提交（`git status` 干净）。
2. **编译**：
   ```powershell
   cd D:\bing\MC\mcmoderr-my\mirage-loader
   gradle :core:mirage-loader:build :launcher:mirage-launch:build
   ```
3. **自动化冒烟**：
   ```powershell
   powershell -ExecutionPolicy Bypass -File .\scripts\verify_sandbox_neo.ps1
   ```
   期望退出码 0，输出里能看到：
   - 首启：`[Mirage][Sandbox] 录制 ferritecore ... 完成 (xx ms, N apis, M events)`
   - 首启后：`.mid-cache/26.2/ferritecore+*.json` 里 `recorded.recorded_loader_version` 非空
   - 二启：`[Mirage][Sandbox] 命中缓存: ferritecore`（没有"录制完成"行）
4. **人工冒烟**（虚拟桌面规则，用户双击 bat，不是 AI 拉 java）：
   - 双击 `D:\bing\MC\mcmoderr-my\启动 26.2-mirage.bat`；
   - 期望 MC 进主菜单，无新增崩溃；
   - 看 `D:\bing\MC\mcmoderr-my\logs\latest.log` 里 ferritecore 旁边有沙盒录制日志。
5. **开关验证**：
   - bat 命令行加 `--sandbox-record=off`，启动一次，日志里**没有**沙盒录制行；
   - 加 `--sandbox-record=force`，启动一次，无视缓存重录。

## 沙盒阶段附加观察项（2026-10-18补充）
跑通NeoForge mod沙盒之后，附加观察创造模式标签页分页机制：
1. 验证原版26.2本身是否内置多标签分页：逐行看`CreativeModeInventoryScreen.init()`里有没有翻页按钮、currentPage字段；
2. 重点观察NeoForge的分页实现：它是在init()里加翻页按钮、自己维护visibleTabs列表、用页内column算x坐标；
3. 记录Fabric的实现方式：它用`@Accessor @Mutable` Mixin直接改写CreativeModeTab的column/row final字段，让原版getTabX自然拿到页内列号，不需要运行时反射；
4. 后续我们自己实现分页优先参考Fabric路线：在init()阶段（游戏已完全启动，不会触发bootstrap）一次性用Accessor改写tab的页内列号，彻底避免`Not bootstrapped`反射异常。

## 回滚方案

- 单个 commit 落地（按规则 5，一轮一提交），commit message：`feat: sandbox recorder for NeoForge mod (MSR-neo)`。
- 万一引入启动崩溃 / 沙盒死循环 / fake API 缺类导致正常 mod 也崩，回滚就一条：
  ```powershell
  git revert <commit-hash>
  ```
  改动范围严格限制在新增 `SandboxRecorder.java` + `fake/` 包 + `MidCache` 加可选字段 + `ModDiscoverer` 一处调用 + `Launcher` 一个 flag，回滚干净。
- 回退后老 `.mid-cache/` json 里多出来的 `recorded` 字段会被第一步的 `Entry` 反序列化忽略（Gson 默认忽略未知字段），不崩。
- 按规则 6 在 `docs/` 写 `回退报告_2026-10-17_sandbox-neo.md`，说明触发现象。
- **保留的部分**：MID 缓存第一步（`MidCache` 的老六个字段）不受影响；`modules/mirage-neoforge-compat/` 静态分析模块独立，本计划不碰。
