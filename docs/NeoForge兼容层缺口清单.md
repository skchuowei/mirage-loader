# NeoForge 兼容层缺口清单

> 评估日期：2026-10-24（2026-10-24 按代码实况复核修订；2026-10-25 P0 四条全部落地；2026-10-25 P1.7 依赖拓扑排序落地；2026-10-25 P1.5 生命周期事件+ModConfig 落地；2026-10-25 P1.6 网络本地通路代码落地）
> 结论：**P0 + P1 全部落地（P1.6 部署待并行 Fabric WIP 收敛后随 fat jar 统一出包）**，可开始小规模试跑（事件监听类 mod、多 mod 依赖包、配置型 mod、简单网络 mod）。
> 本文列出大规模测试前必须补齐的能力缺口，按优先级排序。文中"已具备"均以代码为准，不采信旧文档。

---

## 已具备的能力（不是空壳）

| 能力 | 状态 |
|---|---|
| neoforge.mods.toml 解析 | ✅ 含 modId/version/displayName/版本区间（`NeoForgeModDetector` + `ModsTomlParser`） |
| @Mod 注解扫描 | ✅ ASM 找主类（`NeoForgeModDetector.findModMainClass` / `NeoModScanner`） |
| MID 磁盘缓存 | ✅ 落盘 + 二次启动命中即跳过 ASM（`MidCache`，`ModDiscoverer` 命中分支） |
| SandboxRecorder 沙盒录制 | ✅ 构造函数触达 API/事件录制（`SandboxRecorder` + `Sink`/`RecordingSink`） |
| NeoForge stub API 表面 | ✅ @Mod / IEventBus / SubscribeEvent / Event / FMLJavaModLoadingContext |
| DeferredRegister 桥接 | ✅ **52 种**注册表路径（item/block/creative_mode_tab/enchantment/biome/mob_effect/sound_event/block_entity_type/fluid/potion/particle_type/attribute/menu/各种世界生成器等）；纯 codec 注册表按设计不映射并告警跳过 |
| RegistryObject / DeferredHolder / DeferredBlock / DeferredItem | ✅ |
| mod 主类实例化 | ✅ 无参构造 + IEventBus 注入式构造 |
| 生命周期事件 post | ✅ Construct / CommonSetup / ClientSetup / **InterModEnqueue / InterModProcess** 均已 post（`Launcher.postNeoLifecycle`）；**ModConfigEvent**(Loading/Reloading) 随 `ModConfig` 注册加载后 post。`FMLDedicatedServerSetupEvent` 仍只有类定义不 post（无服务端启动链路，属预期） |
| ModConfig 配置系统 | ✅ `net.neoforged.fml.config`：`ModConfig`（COMMON/CLIENT/SERVER 三类型，启动时自动从 `config/<modid>-<type>.toml` 加载，缺失则生成默认）+ `TomlConfigIO`（扁平键 + 单层 `[table]` 段 + int/long/double/bool/string/list 读写）+ `ConfigData`/`TomlConfigData` 取值 API + `IConfigSpec` stub + `ModLoadingContext.registerConfig()` 入口 |
| 资源包（assets） | ✅ 已接入：织入 `VanillaPackResourcesBuilder.build()` → `MirageDebug.exposeModNamespaces` 把 mod jar 的 `assets/` 解压并以 CLIENT_RESOURCES 注册 |
| 依赖拓扑排序 | ✅ `DependencySorter`（Kahn + 环检测，`Loader` 已调用）现已吃到 NeoForge mod 的 `[[dependencies.X]]`：`NeoForgeModDetector.parseDependencies()` 解析全部依赖段，mandatory 的 mod 间依赖写入 `Detected.depends` → `ModDiscoverer` 落到 `manifest.depends`（平台依赖 neoforge/minecraft 只记版本区间，可选依赖不入约束避免误 FATAL）。AG_VERSION 已 bump 到 0.1.1 使 MID 缓存重生。Fabric 的 depends 转换时仍置 null（见 P2 遗留）。 |

---

## 缺口清单

### P0：阻塞绝大多数 mod 的硬伤

#### 1. 游戏事件总线完全缺失

**现状**：只有 mod bus（构造期的 3 个生命周期事件），没有游戏运行时事件总线；全仓库没有任何 `net.neoforged.neoforge.event.*` 类。

**问题**：NeoForge mod 90% 的逻辑是监听游戏运行时事件，现在这些事件类根本不存在，post 也从来不会触发。

**缺失的游戏事件类**：
- `TickEvent`（服务端 tick / 客户端 tick / 等级 tick / 实体 tick / 玩家 tick）
- `PlayerEvent`（玩家登录 / 退出 / 交互）
- `BlockEvent`（方块放置 / 破坏 / harvest）
- `EntityEvent`（实体生成 / 死亡 / 加入世界）
- `PlayerInteractEvent`（右键方块 / 右键空气 / 左键方块）
- `LivingEvent`（生物受伤 / 死亡 / 治疗）
- `EntityJoinLevelEvent`
- 掉落物相关事件（ItemTossEvent 等）
- `LootTableLoadEvent`
- `RegisterCommandsEvent`
- 等等 20+ 类

**额外要求**：需要在 MC 对应位置注入 post 调用点（如玩家交互处、tick 处）。注入统一走 Mixin 路径；**不要**对已被 `MirageTransformer` 改写的类（如 `Minecraft`）再加 Mixin（见 AGENTS 技术注意事项第 9 节）。

#### 2. 事件总线不做继承层次分发

**现状**：`NeoEventBusImpl.post()` 只按 `event.getClass()` 精确匹配监听器。

**问题**：NeoForge mod 经常监听父类事件（如监听 `PlayerEvent` 而不是具体子类 `PlayerInteractEvent`），post 子类事件时不会触发父类监听器。

**修复**：post 时遍历 event.getClass() 及其所有 superclass / 接口，收集所有匹配监听器。

#### 3. `IEventBus.addListener` 方法不存在（复核新增）

**现状**：`net.neoforged.bus.api.IEventBus` 只有 `register` / `post`。

**问题**：NeoForge 1.21+ 主流写法是 `modBus.addListener(this::commonSetup)`，方法不存在会直接 `NoSuchMethodError`。优先级等同 P0，必须先于游戏事件类补齐。

#### 4. Event 无取消语义 + `@EventBusSubscriber` 缺失（复核新增）

- `net.neoforged.bus.api.Event` 只有 `isCancelable()` 返回 false，没有 `setCanceled` / `isCanceled`，可取消事件一用就 `NoSuchMethodError`。
- 没有 `@EventBusSubscriber`，静态 `@SubscribeEvent` 方法所在类不会被扫描；`NeoEventBusImpl.register` 也只扫 `getDeclaredMethods()`，不含父类。

---

### P1：常见 mod 会用到的

#### 5. ~~生命周期事件缺一半~~ ✅ 已完成（2026-10-25）

**修复（已落地）**：
- `InterModEnqueueEvent` / `InterModProcessEvent` 已建类并在 `Launcher.postNeoLifecycle` 按 NeoForge 时序 post（Construct → CommonSetup → InterModEnqueue → InterModProcess → ClientSetup）。
- `ModConfigEvent`（含 `Loading` / `Reloading` 子类）+ `IMC`（`InterModComms.sendTo` / `get`）已建。
- 配置系统：`ModConfig`（启动加载 `config/<modid>-<type>.toml`，缺失自动生成）+ `TomlConfigIO`（扁平键+单层 table+基础类型）+ `ModLoadingContext.registerConfig()` 注册入口；`Launcher` 在 mod 实例化前设置活跃 mod 供 `ModLoadingContext` 定位。
- 事件类均无参构造，`postNeoLifecycle` 反射实例化后 post 全局 mod bus，`ModConfigEvent` 走 `Launcher.fireModConfigEvent` 携带 ModConfig 实例。

#### 6. ~~网络包缺失~~ ✅ 代码已落地（2026-10-25，P1.6）

**实现（本地通路）**：`net.neoforged.neoforge.network` 包——
- **新 API**：`event.RegisterPayloadHandlersEvent`（`registrar("1")` → `registration.PayloadRegistrar`，`playToServer/playToClient/playBidirectional` 登记到 `ChannelRegistry`）+ `handling.IPayloadHandler` + `PacketDistributor.sendToServer/sendToPlayer/sendToAllPlayers`。
- **旧 API**：`NetworkRegistry.newSimpleChannel(...)` + `SimpleChannel.messageBuilder(Class).encoder().decoder().consumerMainThread().add()` + `NetworkEvent.Context`（方向/sender/enqueueWork/setPacketHandled）+ `NetworkDirection`。
- **派发语义**：单机单 JVM，消息对象直接传引用（StreamCodec/encoder/decoder 登记但不执行），`sendToServer` 直达服务端 handler、`sendToPlayer` 直达客户端 handler，方向不符时告警不崩。`enqueueWork` 当前线程立即执行。
- **post 站点**：`Launcher` 在 InterModProcessEvent 后 post `RegisterPayloadHandlersEvent`。
- **构建注意**：26.2 类文件签名带 jspecify 注解 + netty ByteBuf，模块 compileOnly 需补 `jspecify-1.0.0.jar` 与 netty-buffer/common；`compile-stub` 的 `Identifier` 镜像已补齐与 26.2 一致的静态工厂（parse/fromNamespaceAndPath/withDefaultNamespace/tryParse）。
- **部署状态**：✅ 已部署（2026-10-25 18:07，备份 bak-20261025-p16）——fat jar 含 P1.6 网络类 17 个 + Launcher 的 RegisterPayloadHandlersEvent post + P1.5 配置类 + 并行会话 Fabric 网络类（共存无冲突）。

#### 7. ~~NeoForge mod 依赖没有进入拓扑排序~~ ✅ 已完成（2026-10-25）

**现状（修复前）**：`[[dependencies.<modid>]]` 只解析了 neoforge/minecraft 的 versionRange，没有把其它 mod 依赖写进 `ModManifest.depends`。

**修复（已落地）**：`NeoForgeModDetector.parseDependencies()` 解析全部 `[[dependencies.*]]` 段，mandatory 的 mod 间依赖（依赖目标 modId）写入 `Detected.depends` → `ModDiscoverer` 落到 `manifest.depends` → 复用现有 `DependencySorter` 拓扑排序 + 缺失依赖 FATAL 校验。平台依赖(neoforge/minecraft/forge/fml/java/javafml)只记录版本区间，可选依赖不入约束避免误 FATAL。AG_VERSION bump 0.1.0→0.1.1 强制 MID 缓存重生。

---

### P2：中等复杂度 mod 需要的

#### 8. 数据包（data/）未接入

**现状**：`assets/` 已通过 `exposeModNamespaces` 注册（CLIENT_RESOURCES）。**`data/`（配方 / 战利品 / 标签）没有注册**——`extractAssets` 只过滤 `assets/`，也没有 push SERVER_DATA 类型路径。

**问题**：mod 注册了 item 但没配方、没标签、没战利品表。

#### 9. SandboxRecorder 录了但没消费

**现状**：`touched_apis` / `registered_events` 录了但只用于诊断日志和缓存，没有用于"自动桥接"。

**问题**：录了哪些 API 就该自动生成对应的 stub 注册，现在只是记了一笔。

#### 10. Dist / Executor 类型缺失

**现状**：没有 `net.neoforged.api.distmarker.Dist` stub（Mirage 自己的 `io.mirage.api.env.Dist` 不算）。NeoForge mod 用 `@Mod(dist = Dist.CLIENT)` 会在类加载期就挂。

---

### P3：远期

#### 11. Capabilities 能力系统

#### 12. 数据序列化（Hooks / IExtensionPoint）

#### 13. 方块实体 / 屏幕菜单的完整注册

DeferredRegister 映射了 `block_entity_type` / `menu`，但 `MenuScreens` 注册没做。

---

## 建议的推进顺序

| 步 | 补什么 | 影响面 |
|---|---|---|
| 1 | `addListener` + Event 取消语义 + 事件总线继承分发 | 改 NeoEventBusImpl / IEventBus / Event |
| 2 | 游戏事件类（20+）+ Mixin 注入 post 调用点 | 最大块：新增 event 包 + 一批 Mixin 类 |
| 3 | NeoForge 依赖写进 `manifest.depends`（复用现有 DependencySorter） | 改 ModDiscoverer + NeoForgeModDetector |
| 4 | 数据包 `data/` 注册（复用现有资源包管线） | 改 MirageDebug.extractAssets |
| 5 | ~~网络包 + 配置系统~~ 配置系统已落地（P1.5）；网络包（P1.6 SimpleChannel 本地通路）待补 | SimpleChannel + ModConfig |

---

## 验证标准

- **小规模测试通过**：3–5 个极简 NeoForge mod（只注册 item/block + 监听一个 setup 事件）从识别到注册到进游戏全流程无报错。
- **大规模测试通过**：20+ 个真实 NeoForge mod（含游戏事件监听、资源文件、多 mod 依赖）全部正常加载。
