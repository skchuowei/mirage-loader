# mirage-neoforge-compat

NeoForge 兼容层静态分析模块（独立模块，类似 `mirage-harddecode`）。
**Clean-room** 实现 `META-INF/neoforge.mods.toml` 解析 + NeoForge 注解 / `DeferredRegister` 字节码扫描，
把一个 NeoForge mod jar 翻译成符合 **MG IR v0.1** 契约的 `mod.mirage.json`。

> 本轮只做静态分析；**不**改 `core/ModDiscoverer`、**不**改 `Launcher`，**不**做运行时桥接。

## CLI 用法

与 `mirage-harddecode` 同构：

```powershell
# 构建（JDK 25 + Gradle 9.1）
$env:JAVA_HOME="C:\Program Files\Java\jdk-25.0.4"
$env:GRADLE_OPTS="-Dfile.encoding=UTF-8"
& "D:\bing\MC\mcmoderr-my\.gradle-dist\gradle-9.1.0\bin\gradle.bat" `
  :modules:mirage-neoforge-compat:build --console=plain --no-daemon

# 跑分析
java -cp "<module.jar>;<asm-9.9.jar>;<asm-commons-9.9.jar>;<gson-2.11.0.jar>" `
  io.mirage.neoforge.Main <mod.jar> [-o output.json]
```

- 缺省 `-o` 时 JSON 打到 stdout；指定 `-o` 时写到文件（UTF-8 无 BOM）。
- 退出码：`0` 成功，`1` 分析/IO 失败，`2` 参数错误。

### 自带 fixture

```powershell
java -cp "<...>" io.mirage.neoforge.Main sample\exampleneomod-1.0.0.jar -o sample\output.json
```

- `sample/exampleneomod-1.0.0.jar`：用 JDK 25 的 `javac` 编译 `sample/src/` 下的 stub 注解 + `com.example.neomod.NeoModMain` 后 `jar` 打包，并内置 `META-INF/neoforge.mods.toml`。
- stub 只在 `sample/src/` 下，**不**进 `src/main`。

## IR 字段映射表（NeoForge 描述符 → MG IR v0.1）

| NeoForge 侧来源 | IR 字段 | 说明 |
|---|---|---|
| `META-INF/neoforge.mods.toml` 顶层 `modLoader`/`loaderVersion`/`license` | `_diagnostics.unresolved`（旁注） | 暂不桥接运行时 |
| `[[mods]]` → `modId` | `mod.id` | `@Mod("...")` 注解值会覆盖 toml |
| `[[mods]]` → `version` | `mod.version` | `${version}` / `${file.jarVersion}` 占位符未解析时默认 `1.0.0` 并在 `_diagnostics` 记一笔 |
| `[[mods]]` → `displayName`/`authors`/`description`/... | （暂存，未进 IR 核心） | A4 不消费 |
| `@Mod("...")` 标注的类 | `mod.entrypoints.main[]`（二进制名） | NeoForge 入口类 |
| `[[dependencies.<ourModId>]]` → `modId`/`mandatory`/`versionRange`/`side` | `dependencies[]` | `mandatory=true` → `type=REQUIRED`，否则 `OPTIONAL`；`side` 大写 |
| `@SubscribeEvent` 标注的方法 | `events[]` | `owner`=类内部名，`method`/`desc`=方法名+描述符，`eventType`=第一个参数内部名；`_src={STATIC,MEDIUM}` |
| `public static` 字段类型为 `Lnet/neoforged/neoforge/registries/DeferredRegister;` | `registries[]` | `owner`/`field`/`declaredType`/`modIdHint`；`_src={STATIC,LOW}` |
| `[[mixins]]` → `config` | `_diagnostics.mixinTargets[]` | 仅登记文件名 |
| `[[accessTransformers]]` → `file` | `mod.accessWideners[]` | 直通 |
| mod id | `resources.namespaces[0]` | 兜底 |

注解/字段描述符按字符串匹配（ASM），**不要求** classpath 上真有 NeoForge 类。

## 当前局限 / TODO

- [x] **运行时桥接已落地（2026-10-17）**：`net.neoforged.*` stub 随 mirage-launch fat jar 进运行时 classpath，NeoForge mod 类链接不再 `NoClassDefFoundError`。已接通：
  - `net.neoforged.fml.common.Mod`（RUNTIME 注解）
  - `net.neoforged.bus.api.SubscribeEvent` / `IEventBus` / `Event`（`io.mirage.neoforge.runtime.NeoEventBusImpl` 实现，按事件类型分桶分发）
  - `net.neoforged.fml.javafmlmod.FMLJavaModLoadingContext`（全局单例 mod bus）
  - `net.neoforged.neoforge.registries.DeferredRegister` / `RegistryObject`：`register(bus)` 时按注册表 key location（`minecraft:item`/`block`）反射出 MC 元素类，桥接到 MG `io.mirage.api.registry.DeferredRegister`，**零 Launcher 改动**复用现有 hook→flush→nativeBackend 闭环。
  - `@Mod` 主类构造：无参构造失败时回退 `MyMod(IEventBus modBus)` 注入式构造（Launcher 反射实现）。
  - 生命周期事件 stub：`FMLConstructModEvent` / `FMLCommonSetupEvent` / `FMLClientSetupEvent` / `FMLDedicatedServerSetupEvent`；Launcher 在对应节点 post 到 mod bus，NeoForge mod 的 `@SubscribeEvent` 方法真正触发。
- [ ] `@SubscribeEvent` 的 `priority` / `receiveCancelled` 注解元素**未解析**，IR 里暂写默认值（1000 / false）。
- [ ] NeoForge 业务事件（方块放置、生物死亡等 gameplay 事件）未提供 stub 也未 post；mod 若监听这类事件仍会 `NoClassDefFoundError`。
- [ ] `DeferredRegister` 的 `RegistryKey` / `Registry` 静态初始化**未做**深度解析；`registries[]` 目前只是"这里有个 DeferredRegister 字段"的 LOW 置信度提示，拿不到具体注册的方块/物品。
- [ ] 非 item/block 注册表（entity_type / biome / enchantment …）运行时跳过并打印，不桥接。
- [ ] `RegistryObject` 字段未单独登记。
- [ ] TOML 解析是手写子集解析器：不支持多行字符串、数组内联表、日期等；对真实大型 mod 的健壮性待验证。
- [ ] 未猜 MC 26.2 内部类名；不确定的能力一律在此列 TODO，不编造。

## 运行时 stub 与编译期镜像

- 运行时 stub 源码在 `src/main/java/net/neoforged/**`，**会**打包进 jar。
- `src/compile-stub/java/net/minecraft/**` 是编译期镜像（Registry/ResourceKey/Identifier 的空签名），只为让 NeoForge 风格方法签名通过编译；`tasks.jar { exclude("net/minecraft/**") }` 保证它们**不**进产出 jar，运行时由 mcl 从真 MC 26.2 jar 加载，不会遮蔽真类。
- 部署：stub 随 mirage-launch fat jar 合并进 `mc-shili/libraries/io/mirage/mirage-launch/0.1.0/`（合并各模块 `build/classes/java/main` 后重新打包）。
