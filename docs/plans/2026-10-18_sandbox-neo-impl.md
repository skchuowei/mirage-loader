# NeoForge mod 提前沙盒录制实施（MSR-neo 落地）

> 关联：`docs/plans/2026-10-17_sandbox-neo.md`（设计文档，本计划是其代码落地）
> 日期：2026-10-18

## 目标

把设计文档 `2026-10-17_sandbox-neo.md` 里的 MSR-neo 从"只做设计"落地为可运行的代码：在进入 MC 主循环前，让每个 NeoForge mod 主类在隔离沙盒里跑一遍 `<clinit>` + 构造函数，把 `recorded_loader_version` / `recorded_mc_version` / `touched_apis` / `registered_events` / `failed` 写进 `.mid-cache/<modId>+<jarHash16>.json` 的 `recorded` 子对象，二次启动命中缓存跳过沙盒。

**可验证标准**：ExpOre / ferritecore 首次启动后 `.mid-cache/26.2/*.json` 出现 `recorded` 子对象且 `recorded_loader_version` 非空、`touched_apis` 是数组；二次启动日志出现"沙盒录制命中缓存"；`gradle build` BUILD SUCCESSFUL。

## 前置条件与阻塞项

无，工作区干净（`git status` clean），可立即开干。前置依赖：MID 缓存第一步（`MidCache.java`）已落地；`modules/mirage-neoforge-compat` 运行时 stub 已随 fat jar 进运行时 classpath，但**沙盒 fake 表面独立**，不复用它们。

## 现状分析

1. **NeoForge mod 启动路径**：`ModDiscoverer` 扫到 `neoforge.mods.toml` → `NeoForgeModDetector.detect(jf)`（ASM 扫 `@Mod` 主类）→ `MidCache.lookup/store` → `ModContainer.fromNeoForge=true` → Launcher 反射构造 + post 生命周期。
2. **录制为零**：构造/`<clinit>` 跑完即弃，无任何记录。
3. **实测触达面**（`javap -p -c` 反编译 ExpOre / ferritecore）：
   - ferritecore 主类 `malte0811.ferritecore.ModMainForge`：`@Mod("ferritecore")`，**无参构造**，构造体只有 `super()`，几乎零触达（好测）。
   - ExpOre 主类 `com.blocklegend001.expore.ExpOre`：`ExpOre(IEventBus)` 注入式构造；`<clinit>` 触达 `Registries.ITEM`、`DeferredRegister.create(ResourceKey,String)`、`DeferredRegister.createBlocks(String)`；构造体调 `ExpOreConfig.loadConfig()`（触达 `java.io.File`）+ `ModItems/ModBlocks/ModCreativeModTabs.register(IEventBus)`。
   - 触达面确认：`IEventBus.register(Object)`、`DeferredRegister.create/createBlocks/createItems`、`DeferredRegister.register(String,Supplier)`、`DeferredRegister.register(IEventBus)`、`DeferredBlock/DeferredItem/DeferredHolder`、`ResourceKey`、`Identifier`、`Registries.ITEM/BLOCK/CREATIVE_MODE_TAB`。
4. **fake 表面不能进 fat jar 标准类路径**：`net.minecraft.*` fake 一旦打进 `net/...` 路径，运行时 mcl 加载真 MC 类会被遮蔽。结论：fake 类字节码以**资源**形式打包在 `mirage/sandbox-fake/` 前缀下，由 `SandboxClassLoader` 读资源后 `defineClass`，不影响 mcl / app classloader。
5. **关键约束**：fake 方法签名（擦除后描述符）必须与 mod 字节码**严格一致**，否则 `NoSuchMethodError`。

## 改动文件清单

**要改：**

| 文件 | 改什么 |
|---|---|
| `core/mirage-loader/src/main/java/io/mirage/loader/MidCache.java` | 加 `Recorded` 静态类 + `Entry.recorded` 可选字段；`specVersion` "0.1"→"0.2"；`store` 签名加 `Recorded` 参数 |
| `core/mirage-loader/src/main/java/io/mirage/loader/NeoForgeModDetector.java` | `Detected` 加 `neoForgeVersionRange` / `mcVersionRange`；解析 `[[dependencies.*]]` 段的 `versionRange` |
| `core/mirage-loader/src/main/java/io/mirage/loader/ModDiscoverer.java` | 缓存 miss 后调 `SandboxRecorder.record`；缓存命中且 `recorded==null` 补录；加 `sandboxMode` 参数（off/auto/force） |
| `core/mirage-loader/src/main/java/io/mirage/loader/Loader.java` | 透传 `sandboxMode` |
| `launcher/mirage-launch/src/main/java/io/mirage/launch/Launcher.java` | 加 `--sandbox-record=off\|auto\|force` flag 解析并传参 |
| `core/mirage-loader/build.gradle.kts` | 新增 `sandbox` sourceSet；jar 任务把 sandbox 编译产物打进 `mirage/sandbox-fake/` |
| `core/mirage-loader/src/main/java/io/mirage/loader/sandbox/*.java` | 新增（见下"新增文件"） |
| `core/mirage-loader/src/sandbox/java/**` | 新增 fake 表面（见下） |
| `scripts/verify_sandbox_neo.ps1` | 新增冒烟脚本 |
| `docs/更新日志.md` / `docs/改动登记表.md` | 收尾追加 |

**新增文件（main，`io.mirage.loader.sandbox`）：**

| 文件 | 作用 |
|---|---|
| `RecordingSink.java` | 录制接口：`touch(api)` / `registerEvent(eventClass)` / `deferredRegisterCreated()` |
| `Sink.java` | 静态 holder：`static volatile RecordingSink current`，fake 类通过它访问录制器 |
| `RecordingSinkImpl.java` | `RecordingSink` 实现（线程安全集合） |
| `SandboxClassLoader.java` | 隔离 ClassLoader：`net.minecraft.*`/`net.neoforged.*` 走 fake 字节码；其余委托 parent |
| `SandboxRecorder.java` | 主控：加载 fake → 建 loader → 2 秒超时跑 `<clinit>`+构造 → 收集 `Recorded` |

**新增文件（sandbox sourceSet，fake 表面）：**

| 文件 | 作用 |
|---|---|
| `net/neoforged/fml/common/Mod.java` | RUNTIME 注解占位 |
| `net/neoforged/bus/api/IEventBus.java` | 接口：`register` / `post` / `addListener` |
| `net/neoforged/bus/api/SubscribeEvent.java` | RUNTIME 注解占位 |
| `net/neoforged/bus/api/Event.java` | 空基类 |
| `net/neoforged/bus/api/FakeEventBus.java` | `IEventBus` 实现：`register` 扫描 `@SubscribeEvent` 记 `registered_events` |
| `net/neoforged/fml/javafmlmod/FMLJavaModLoadingContext.java` | 单例，`getModEventBus()` 返回 FakeEventBus |
| `net/neoforged/neoforge/registries/DeferredRegister.java` | `create/createBlocks/createItems/register(String,Supplier)/register(IEventBus)` + 内部类 `Blocks`/`Items`，全部 recording proxy |
| `net/neoforged/neoforge/registries/DeferredHolder.java` / `DeferredBlock.java` / `DeferredItem.java` / `RegistryObject.java` | 句柄占位 |
| `net/minecraft/core/Registry.java` / `resources/ResourceKey.java` / `resources/Identifier.java` / `core/registries/Registries.java` / `world/item/Item.java` / `world/level/block/Block.java` / `world/item/CreativeModeTab.java` | minimal `net.minecraft.*` 占位，自洽不引用真 MC |

**明确不做：** 不改 `api/mirage-api/`（MG 原生 DeferredRegister）；不改 `modules/mirage-neoforge-compat/` 运行时 stub（沙盒 fake 独立）；不改 `MirageClassLoader` / `MirageTransformer` / mixin 管线；不碰 `mc-shili/` 除 fat jar 部署外的文件；不 fork 子 JVM。

## 每处改动要点

1. **MidCache.Recorded**：字段 `recorded_loader_version` / `recorded_mc_version`（String）、`touched_apis` / `registered_events`（`List<String>`）、`deferred_register_count`（int）、`record_duration_ms`（long）、`failed`（boolean）、`error`（String）。`Entry` 加 `public Recorded recorded;`（null=未录制）。`lookup` 三件套校对不变；`store` 写 `recorded`。老缓存（无 `recorded` 字段）反序列化自然为 null，不崩。
2. **NeoForgeModDetector**：新增 `tomlDependencyVersions(toml)` 解析 `[[dependencies.*]]` 段，`modId=neoforge` 的 `versionRange` → `neoForgeVersionRange`，`modId=minecraft` → `mcVersionRange`；`detect()` 填进 `Detected`。
3. **SandboxClassLoader**：继承 `URLClassLoader`（URLs=mod jar，parent=app classloader）。`loadClass`：先 `findLoadedClass` → 命中 fake 名（`net.minecraft.`/`net.neoforged.` 前缀且在白名单）→ 从资源 `mirage/sandbox-fake/<name>.class` 读字节码 `defineClass` → 其余 `super.loadClass`（parent-first，第三方库可用）。fake 未覆盖的 `net.minecraft.*`/`net.neoforged.*` 抛 `ClassNotFoundException`（不委托 parent 避免污染）。
4. **SandboxRecorder.record**：① 从 classpath 收集 fake 类名集合；② `SandboxClassLoader`；③ `Sink.current = new RecordingSinkImpl()`；④ 独立线程里 `Class.forName(mainClass, true, loader)` 触发 `<clinit>`，再试无参构造 → 失败回退 `(IEventBus)` 构造；⑤ 主线程 `join(2000)`，超时 `failed=true, error="timeout"`；⑥ 任何 `Throwable` → `failed=true, error=类名+msg(截断500)`；⑦ 填充 `recorded_loader_version/mc_version`（来自 `Detected`）；⑧ finally 清 `Sink.current`、关 loader。全程 try/catch 不抛。
5. **fake DeferredRegister**：`create/createBlocks/createItems` 各 `Sink.touch(...)` + `deferredRegisterCreated()`；`register(String,Supplier)` 记 `touch` 返回 `DeferredHolder`；`register(IEventBus)` 记 `touch`。`FMLJavaModLoadingContext.get()` 记 touch，`getModEventBus()` 返回 FakeEventBus 单例。
6. **ModDiscoverer**：`discover(...)` 加 `String sandboxMode`；对每个 NeoForge mod：`recorded = (cached!=null)?cached.recorded:null`；`neo==null` 时先 `detect`；随后 `(sandboxMode!=off && (recorded==null || sandboxMode==force))` 时 `recorded = SandboxRecorder.record(jar, neo)`；`cache.store(jar, neo, recorded)` 仅在需要时写。verbose 打命中/录制日志。
7. **Launcher**：`parseSandboxMode(args)`（`--sandbox-record=` 前缀，缺省 "auto"）；传给 `Loader.discoverMods`。
8. **build.gradle.kts（core/mirage-loader）**：`sourceSets.create("sandbox")`，`compileClasspath += main.output`；`tasks.jar { dependsOn("sandboxClasses"); from(sandboxClasses.destinationDirectory){ into("mirage/sandbox-fake") } }`。

## 回归测试影响面

- **现有行为不变**：IR / mirage.mod.json / fabric.mod.json 三条路径零改动；`Detected` 老字段一字不动；沙盒任何异常都降级为 `failed=true` 不阻断；`--sandbox-record=off` 完全关沙盒，行为与落地前一模一样。
- **可能打破**：mod `<clinit>` 若依赖真实 MC（如静态初始化就 `new Block(...)`），沙盒里 `NoClassDefFoundError` → catch 降级，真实启动不受影响；这是预期行为。
- **零侵入模块**：`api/`、`core/mirage-mixin`、`core/mirage-eventbus`、`modules/*`（除构建脚本外）。
- **mdk-example 冒烟步骤不变**。

## 验证步骤

1. `gradle build` BUILD SUCCESSFUL。
2. 部署：把新/改的 class + `mirage/sandbox-fake/` 资源增量 `jar uf` 进 `mc-shili/libraries/io/mirage/mirage-launch/0.1.0/mirage-launch-0.1.0.jar`（不重打整 jar，避免漏 services/asm-util）。
3. 删除旧 `.mid-cache/26.2/*.json`。
4. **遵守虚拟桌面规则**：用户双击 `..\启动 26.2-mirage.bat`，AI 不直接拉 java。
5. 读 `..\logs\latest.log`：期望出现 `[Mirage][Sandbox] 录制 <mod> 完成 (xx ms, N apis, M events)`；读 `.mid-cache/26.2/*.json`：`recorded.recorded_loader_version` 非空、`touched_apis` 是数组。
6. 二次启动：日志出现 `[Mirage][Sandbox] 命中缓存: <mod>`，无重新录制耗时。
7. `scripts/verify_sandbox_neo.ps1` 退出码 0。

## 合规边界

涉及 `refs/neoforge/` 只读参考与 `net.neoforged.*` 表面。NeoForge 是 LGPL 2.1：本计划只做**接口签名枚举 + clean-room 行为观察**（fake 表面方法签名来自 `javap` 反编译 mod 字节码与既有 stub，实现为纯自研 recording proxy），不抄 NeoForge 实现代码。

## 回滚方案

- 单 commit 落地：`feat: sandbox recorder for NeoForge mod (MSR-neo)`。
- 坏则 `git revert <hash>`；改动严格限制在新增 `sandbox/` 包 + fake 资源 + `MidCache` 可选字段 + `ModDiscoverer` 一处调用 + `Launcher` 一个 flag + 构建脚本，回滚干净。
- fat jar 部署前备份 `mirage-launch-0.1.0.jar.bak-20261018-sandbox`，坏则覆盖回滚。
- 老 `.mid-cache/` 里多出的 `recorded` 字段被 Gson 忽略，不崩。
- 按规则 6 写回退报告。
