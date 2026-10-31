# MGScript：类 Python 的 Mod 脚本语言（MSE 落地）
> 关联：`MG-设计脉络与命名体系.md` §九（MSE = Mirage Script Engine）、`docs/plans/2026-10-31_AG-总计划重梳理.md` G25 / T7.1（KubeJS 降级方案）、`docs/architecture.md`。无上游 commit 依赖。

## 目标

让 Mod 开发可以在**不写 Java、不装 JDK、不跑 Gradle**的前提下完成：在 `mods/<mymod>/` 放一个 `mod.mirage.json` + 若干 `.mgs` 脚本，启动游戏即生效；语言以 Python 的阅读感为基线（缩进定块、动态类型、无分号），但把 Mod 开发的高频意图提升为**语法级声明**（`item` / `block` / `on <event>`），使拼写错误在解析期就报错，而不是运行期 NPE。

**可验证标准（P0 完成判据）**：

1. `mods/hello-mgs/` 目录内含 `mod.mirage.json` + `scripts/main.mgs`，启动后 `logs/latest.log` 出现 `[MGS] hello-mgs loaded`；
2. 脚本里一句 `item "ruby"` 声明，进入游戏后创造模式搜索栏能搜到 `Ruby` 并取出（复用原版贴图即可）；
3. 脚本里写错事件名（如 `on player_jon`）时，**启动日志给出带文件名/行号/列号 + 候选名列表的诊断**，该 mod 被跳过、游戏继续启动；
4. `/mgs reload` 修改脚本后无需重启，日志出现 `[MGS] reload ok (n files, m ms)`；
5. 纯 JVM 回归脚本 `scripts/verify_mgscript.ps1` 覆盖词法/语法/求值/超时熔断四类断言，全绿且做过一次变异检验。

## 前置条件与阻塞项

- **无代码阻塞**：脚本层是纯新增，不改现有 jar / Mod 加载语义。
- **工作区有既有 WIP**（20+ 文件未提交，含 MSR P2、Fabric F1、早期窗口三批）。本计划 P0 落地时必须遵守「只 add 本轮自己的文件」，不得 `git add -A`。
- **需要用户裁决 5 个决策点**（见文末 §10）。其中 Q4（体积预算）会决定 mgscript 是打进 fat jar 还是做可选模块——**在裁决前只能写方案，不能落代码**。

## 现状分析

### 加载链路 as-is（2026-10-31 代码）

`Launcher.main`（[`launcher/mirage-launch/.../Launcher.java`](../../launcher/mirage-launch/src/main/java/io/mirage/launch/Launcher.java)）只做进程级准备（日志 tee / banner / 早期窗口），随后单向委托 `io.mirage.ls.LifecycleStarter.main`。真正的加载在 `lifecycle-starter` 的六个阶段里，由 `LifecycleScheduler` 严格串行执行：

| 阶段 | 类 | 关键行为 | 与脚本的关系 |
|---|---|---|---|
| S1 Discovery | `DiscoveryStage` → `Loader.discoverMods` → `ModDiscoverer` | `Files.list(modsDir)` **只挑 `.jar`**；按 `mod.mirage.json`(IR) → `META-INF/mirage.mod.json`(legacy) → `fabric.mod.json` → `neoforge.mods.toml` 顺序识别，产出 `ModContainer` | **接入点 1**：目录形态的脚本 mod 需要新分支 |
| S2 Parse | `ParseStage` | 依赖拓扑排序、`EventBus` 实例化、按 `ParseFailPolicy` 判定跳过集合 | 无需改 |
| S3 Transform | `TransformStage` | `new MirageClassLoader(urls, platformLoader)`，逐个 `mcl.addJar(mod.jarFile)`，Mixin 配置扫描 | 目录 mod 的 `addJar(目录)` 天然可用（file: URL） |
| S4 RegistryWindow | `RegistryWindow` | 反射解冻所有 `MappedRegistry`，`DeferredRegister.setHookListener/setNativeBackend(window::nativeRegister)` 装后端 | **接入点 2**：脚本静态声明落进 `DeferredRegister` |
| S6 ModInit | `ModInitStage` | 逐 mod `Class.forName(clsName, true, mcl)` → `constructModInstance` → `eventBus.register` → `ModConstruct/PreInit/Init` 事件 → 全部结束后 `flush` 所有 `DeferredRegister` → 关窗 | **接入点 3**：脚本 `on init` 与事件订阅 |
| S7 | — | 移交 MC | 无需改 |

三条硬事实，决定了脚本层必须怎么长：

1. **`ModDiscoverer` 只认 `.jar`**（`stream.filter(p -> p.getFileName().toString().endsWith(".jar"))`）。目录 mod 不进来就什么都不用谈，这是接入点 1。
2. **注册表窗口跨 S4→S6**：`DeferredRegister.flush()` 统一在 S6 里跑，所以脚本的 `item`/`block` 声明只要在 S6 之前登记进 `DeferredRegister`，就能复用现有注册后端（含 `BlockItem` 绑定、`CreativeModeTab` intrusive holder、bug#1 精准 remove 三处修补），**不需要另写一套注册管线**。
3. **`EventBus` 只认反射注解**（`EventBus.register` 扫 `@SubscribeEvent` + 恰好 1 参）。脚本函数不是 Java 方法，需要给总线加一个函数式注册入口；而 NeoForge 侧 `NeoEventBusImpl.addListener(priority, receiveCancelled, Class, Consumer)` 已经存在，是现成通道。

### 类加载：最大的技术风险，且已有两次前车之鉴

`MirageClassLoader` 的 parent 是 **platform loader**，不是 app；`io.mirage.*` 被硬编码强制走 `SystemClassLoader`（app），只有 `io.mirage.neoforge.` / `io.mirage.fabric.` 两个例外由 MCL 自己加载。

历史教训（`docs/更新日志.md` 两条独立事故）：兼容层类一旦被 app 加载，它引用的 `net.minecraft.*` 也由 app 从启动 classpath **重新 define 一份**，与 MCL 版本形成两份 `Class` → `LinkageError: loader constraint violation`。

因此脚本引擎（要直接访问 `net.minecraft.*` 与 `net.neoforged.*`）**必须**：

- 包名 `io.mirage.mgscript.`，并加入 `MirageClassLoader.loadClass` 的例外白名单（与 neoforge/fabric 同因）；
- LS 侧（app）**不得 import 引擎类**，只能 `Class.forName("io.mirage.mgscript.ScriptHost", true, mcl)` 反射进入（照抄 `NeoLifecycle` 的做法）。

### 事件面现状（制约脚本能力的真实上限）

`io.mirage.api.event` 只有生命周期事件（Construct/PreInit/Init/PostInit/Client\*/Server\*），**没有 tick、玩家进出、方块破坏这类游戏内事件**。游戏内事件目前只存在于 NeoForge 兼容层（`net.neoforged.neoforge.event.tick.{ServerTickEvent,LevelTickEvent,PlayerTickEvent,EntityTickEvent}`、`event.player.*`、`event.level.block.*` 等 20+ 个）。

结论：**脚本能写什么，取决于事件层覆盖度，不取决于语言本身**。P0 只承诺 `on init` + `on tick`；事件面扩展是独立任务，不能算在语言头上。

### 与已规划的 KubeJS 路线的关系

命名体系里 MSE 早就存在（A13），但从未落地（G25 = 0%）。本计划**不引入 Rhino / KubeJS**：自研解释器零外部依赖，体积可控，且错误诊断能用中文精确到列。与 `<3MB` 目标的冲突在 §10 Q4 处理。

## 改动文件清单

| 文件 | 改什么 |
|---|---|
| `modules/mirage-mgscript/build.gradle.kts`（新） | 新模块；`compileOnly` 26.2 jar；零第三方依赖 |
| `modules/mirage-mgscript/src/main/java/io/mirage/mgscript/lex/{Lexer,Token,TokenType}.java`（新） | 词法：缩进栈（INDENT/DEDENT）、插值字符串、注释、行列号 |
| `.../parse/{Parser,Ast,ParseError}.java`（新） | 递归下降；语法错误带「文件:行:列 + 期望/实际 + 候选建议」 |
| `.../runtime/{Interp,Value,Env,ScriptError,ControlFlow}.java`（新） | 树遍历求值；指令计数熔断；调用栈保留脚本行号 |
| `.../builtin/{Builtins,LogLib,MathLib,JsonLib}.java`（新） | 标准库：str/list/dict/math/json/log |
| `.../game/{GameApi,ItemDecl,BlockDecl,EventBinder}.java`（新） | 游戏 API 与声明式编译器（`item`/`block` → `DeferredRegister`；`on` → 事件订阅） |
| `.../ScriptHost.java`（新） | 唯一对 LS 暴露的入口：`load(ModScriptUnit)` / `onGameEvent` / `reload` |
| `core/mirage-loader/.../ModDiscoverer.java` | 接入点 1：`Files.list(modsDir)` 增加「目录 + `mod.mirage.json`」分支 |
| `core/mirage-loader/.../ModContainer.java` | 新增 `Path scriptRoot`（脚本 mod 非 null） |
| `core/mirage-loader/.../MirageClassLoader.java` | 例外白名单加 `io.mirage.mgscript.`（一行，与既有两个例外同因） |
| `core/mirage-eventbus/.../EventBus.java` | 新增 `register(Class<?> type, Consumer<Object> handler)`；不动既有注解扫描路径 |
| `lifecycle-starter/.../stage/ModInitStage.java` | 接入点 3：`mainClasses.isEmpty() && scriptRoot != null` 时走脚本分支（在 flush 之前挂声明，在 Construct/Init 处触发 `on init`） |
| `lifecycle-starter/.../stage/TransformStage.java` | 目录 mod 打一行 debug（`addJar` 本身不必改） |
| `launcher/mirage-launch/build.gradle.kts` | fat jar 纳入新模块（视 Q4 裁决） |
| `scripts/verify_mgscript.ps1`（新） | 纯 JVM 回归：五类断言 + 变异检验 |
| `mdk/mdk-script-example/`（新） | 三文件起步模板：`mod.mirage.json` + `scripts/main.mgs` + `README.md` |
| `docs/日志规范.md` / `docs/更新日志.md` / `docs/改动登记表.md` / `docs/经验与踩坑.md` | 台账 |

> **明确不做**：不改 `LifecycleScheduler` / `LifecycleContext` 的阶段契约；不改 `RegistryWindow` 的注册后端；不改 `MirageTransformer` 与任何 Mixin；不引入 Rhino/GraalJS/Jython；不做 Forge 兼容；不动 `mc-shili/`；不做脚本 → 字节码编译（P0–P2 一律树遍历解释）。

## 每处改动要点

### 1. 接入点 1 · 目录 mod 发现（`ModDiscoverer`）

现状只 `Files.list().filter(endsWith(".jar"))`。新增一条分支：目录下存在 `mod.mirage.json` → 与 jar 走**同一个** descriptor 解析路径，只是把 `jarFile` 换成目录 `Path`。

- `ModContainer.jarFile` 语义从「jar 路径」放宽为「mod 根路径」，字段改名成本高，**保留字段名 + 加注释**；新增 `scriptRoot`（= 目录本身，或 jar 内的 `scripts/` 虚拟路径）。
- 目录 mod 不参与 MID 缓存与沙盒录制（它不是 NeoForge mod），直接跳过 `NeoForgeModDetector` 分支。
- `DependencySorter` 不变：脚本 mod 也走 `manifest.depends` 拓扑。

### 2. 接入点 2 · 静态声明 → DeferredRegister

脚本顶层声明块在**解析后被静态提取**，不执行任何用户代码就登记注册项：

```
item "ruby"
    name "Ruby"
    stack 64
```

→ `DeferredRegister<Item>.register("ruby", () -> new Item(new Item.Properties()))`（反射构造，参数如 `stack` 落到 `Properties.stacksTo(64)`）。

**先声明、后 flush**：因为 `ModInitStage` 在所有 mod 构造完之后才统一 `flush`，脚本在 S6 任意时刻登记都来得及；且 flush 仍在 S4 打开的 unfreeze 窗口内，不破坏现有注册语义。

同时把静态声明写进 `IrDescriptorParser.IrResult.registries`（该字段早已接住但从未消费）——这一步让「脚本可被 AG/工具链静态分析」成立，是后续 MID-S 的基础。

### 3. 接入点 3 · 事件订阅（`EventBus` 扩展 + `EventBinder`）

给 `EventBus` 加：

```java
public void register(Class<?> eventType, Consumer<Object> handler)
```

内部与注解扫描共用同一个 `listeners` 桶（key 为事件类型，值为 handler 列表），`post` 时先走既有 `Method` 列表再走 `Consumer` 列表。**既有行为零变化**：`register(Object)` 路径一行不改。

`EventBinder` 负责把脚本的 `on tick(server)` 映射到具体事件类：用 `Class.forName("net.neoforged.neoforge.event.tick.ServerTickEvent$Post", true, mcl)` 拿类型，再 `NeoEventBusImpl.addListener(...)` —— 走的是**已有**的函数式入口，不需要碰事件总线实现。

### 4. 语言：Python 的骨架，去掉 Python 的糟粕

**保留**（这是「像 py」的来源）：缩进定块、无分号无花括号、动态类型、列表/字典字面量、`if/elif/else`、`for … in`、`while`、`fn`、闭包、字符串插值。

**明确改掉的 11 处**（用户要的「语法优化」）：

| # | Python 的写法 | MGScript | 为什么改 |
|---|---|---|---|
| 1 | `def f(self, x)` | `fn f(x)`，方法体内 `this` 隐式可用 | `self` 是纯噪声，90% 的 mod 逻辑用不到显式接收者 |
| 2 | `__init__` / `__str__` | `new` / `text` | dunder 是历史包袱，不是设计 |
| 3 | `for i in range(10)` | `for i in 0..10`（左闭右开，`0..=10` 闭区间） | `range` 是函数，区间是语法 |
| 4 | `if x:` （冒号必写） | `if x` 后直接缩进，冒号可选 | 缩进已经表达了块，冒号是重复信号 |
| 5 | `f"HP {p.hp}"` | `"HP {p.hp}"` | 插值默认开启，不用记前缀 |
| 6 | `try/except E as e` | `try/catch e` | 脚本层只有一种错误类型，不需要异常分类树 |
| 7 | `global x` / `nonlocal` | 无；模块级变量用 `shared x` 显式声明 | `global` 是最容易被误用的关键字 |
| 8 | `threading` / `asyncio` | 无并发，全部主线程；`every 20` 语法糖做周期 | MC 的线程模型不容许玩家脚本乱开线程 |
| 9 | 装饰器 `@app.route` | 语法关键字 `on <event>` / `command "x"` | 装饰器是「库伪装成语法」，直接把意图做成语法 |
| 10 | 隐式 `str + int` 报错信息晦涩 | 一律显式转换，报错指出两侧类型与位置 | 新手 90% 的运行时错误在这一类 |
| 11 | 无超时保护 | 每个回调默认 100k 指令预算，超了抛 `ScriptTimeout` 并**熔断该处理函数**（WARN 一次，不刷屏） | 脚本生态必备，否则一个 `while true` 卡死客户端 |

一段完整示例（P0 目标形态）：

```
mod "rubycraft" version "1.0.0"

item "ruby"
    name "Ruby"
    stack 64

on init
    log "rubycraft ready"

on tick(server) every 20
    for p in server.players
        if p.health < 6
            p.message("你伤得很重")
```

**不做**（刻意的减法）：类继承与多态（用 `struct` + 组合）、生成器、元类、`with`、运算符重载、多重赋值拆包、`*args/**kwargs`。

### 5. 错误诊断（决定「门槛」高低的真正因素）

每类错误都要给三件事：**位置**（file:line:col）、**期望 vs 实际**、**修法**。目标形态：

```
[MGS] mods/hello-mgs/scripts/main.mgs:12:5  未知事件 "player_jon"
      12 | on player_jon(p)
         |    ^^^^^^^^^^
      候选: player_join, player_leave, player_tick
```

这是自研解释器相对「嵌入现成引擎」的核心优势：Rhino/GraalJS 只会把 JS 的堆栈翻译成 Java 堆栈，做不到这个。

### 6. 类加载接线（一行，但必须做）

`MirageClassLoader.loadClass` 的例外名单加 `io.mirage.mgscript.`。漏了这一行，症状是启动时 `LinkageError: loader constraint violation`，且栈帧指向 `net.minecraft.*`，非常难倒推 —— 直接写进类注释。

## 回归测试影响面

| 风险 | 影响 | 缓解 |
|---|---|---|
| `ModDiscoverer` 加目录分支 | 可能把用户 mods 里无关目录当 mod → 报错 | 只在目录**同时**含 `mod.mirage.json` 时接管；否则 debug 一行并忽略 |
| `EventBus` 新增重载 | 既有注解路径若共用桶，顺序可能变 | `Consumer` 列表独立存于同一 listener 结构里，`post` 先 Method 后 Consumer；既有断言不动 |
| `MirageClassLoader` 白名单 | 若把 `io.mirage.mgscript.` 写成 `io.mirage.` 前缀会全盘改道 | 精确前缀匹配，附单元断言 |
| `ModInitStage` 脚本分支 | 无入口 mod 的 WARN 逻辑会被误吞 | 先判 `scriptRoot != null` 再判空；无脚本 mod 行为逐字不变 |
| fat jar 体积 | 可能顶破 3MB | Q4 裁决；P0 先测实际增量再定 |
| 纯 Java mod | 零影响 | 脚本模块不被引用时不加载任何类 |

`mdk-example` 冒烟步骤不变；新增 `mdk-script-example` 作为独立冒烟样本。账号级回归靠 `scripts/verify_mgscript.ps1`（不启动游戏）。

## 验证步骤

1. `gradle :modules:mirage-mgscript:build` + `:launcher:mirage-launch:fatJar`（**必须用 fatJar，`build` 会产出同名 67KB 瘦 jar**）。
2. `powershell -ExecutionPolicy Bypass -File .\scripts\verify_mgscript.ps1` → 断言全绿，退出码 0；随后做变异检验（故意改坏 `if` 求值，确认对应断言变红）。
3. **实机冒烟由用户双击 `..\启动 26.2-mirage.bat`**（虚拟桌面规则：AI 不直接拉 java）。启动后检查 `..\logs\latest.log`：
   - `[MGS] 发现脚本 mod: hello-mgs (n files)`
   - `[MGS] hello-mgs loaded`
   - 无 `LinkageError` / 无 `loader constraint violation`
4. 进游戏 → 创造模式搜索 `Ruby` → 取出 → 放置（若 P1 已做 block）。
5. 交互式验收：改脚本 → `/mgs reload` → 看日志 `[MGS] reload ok`。

## 合规边界

不涉及 LGPL 代码。仅以类名字符串引用 NeoForge 事件类型（`net.neoforged.neoforge.event.*`），这些 stub 是项目已有的 clean-room 实现；不复制 NeoForge/Forge 任何实现代码，不读 `refs/`。

## 回滚方案

P0 全部为新增文件 + 4 处小改动（`ModDiscoverer` / `ModContainer` / `MirageClassLoader` / `EventBus` / `ModInitStage`）。回滚 = `git revert <P0 commit>`；四个改动点皆为「加分支/加重载/加字段」，revert 后既有 jar 行为逐字恢复，无需数据迁移（不写任何缓存文件）。fat jar 回退用部署目录里的 `bak-*` 备份。

---

## 10. 待裁决（拍板后才动代码）

| # | 问题 | 建议 | 影响 |
|---|---|---|---|
| **Q1** | 语言名与后缀 | **MGScript / `.mgs`**（对齐命名体系 MSE） | 一旦发布改名代价极高 |
| **Q2** | 自研解释器 vs 嵌入现成引擎 | **自研**：零依赖、体积可控、错误诊断能做到中文行列号 + 候选建议；代价是 P0 约 1.5k 行 | 决定 P0 工期与体积 |
| **Q3** | 脚本 mod 形态 | **目录为主**（`mods/xxx/mod.mirage.json` + `scripts/`），jar 形态同时支持 | 目录形态是「零工具链」的关键，但需要改 `ModDiscoverer`（核心敏感区） |
| **Q4** | 体积预算 | 现 2.68MB；脚本模块预计 +250~400KB → 约 2.95MB，**贴着 3MB 上限**。建议 `<3MB` 放宽为「核心 jar <3MB，mgscript 独立可选模块」 | 与 G25/F4 同一条矛盾线，必须二选一 |
| **Q5** | 是否把声明式块绑定到 IR | **绑定**：`item/block` 解析期就写进 `IrResult.registries`，为后续 MID-S 铺路 | 决定这套语言是「另一个 KubeJS」还是「MG 自己的原生格式」 |
| **Q6** | P0 验收场景 | 物品 + `on init` + `on tick` + reload（不做贴图，复用原版素材） | 决定第一版能不能给出可玩的东西 |

> 我的倾向：Q1–Q3、Q5、Q6 按建议执行；Q4 需要你明确——是接受「核心 3MB + 脚本独立模块」，还是把核心上限抬到 3.5MB。
