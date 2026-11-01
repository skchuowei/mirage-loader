# Fabric 兼容层缺口清单

> 评估日期：2026-10-25（P1 落地后复核）
> 结论：**P0 核心/关闭路径 + P1 全部四项均已落地**（lifecycle-v1 六事件全 firing；mod `data/` 进数据包管线；
> `ClientTickEvents` 四事件 firing；fabric-api-network play 阶段 API 表面 + 连接事件 firing + 真实收发；
> Launcher 专用服务端 entrypoints 路径）。可让"注册 lifecycle/tick 回调、收发包、带 data/ 资源"的
> Fabric mod 跑通；**fabric-api-events 其余 20+ 回调类（P0-2）、fabric-api-registry（P0-3）仍缺失**。
> 网络/tick 桥接的已知限制见文末"桥接 stub 限制汇总"。文中"已具备"均以代码为准，不采信旧文档。
> **2026-11-01**：已实现部分的功能模块/实现细节/边界验证清单另见 `docs/Fabric兼容层-功能模块与实现说明.md`；
> 本轮盘点新增缺口两条（mod 自带 mixin config、depends 依赖检查，见下文 P1-13/P1-14）。

---

## 已具备的能力（不是空壳）

| 能力 | 状态 |
|---|---|
| ~~fabric.mod.json 解析 | ✅ `core/mirage-loader/FabricModManifest` + `modules/mirage-fabric-compat/ModsJsonParser`~~ **【已过时·2026-11-01】** 运行时解析只走 `core/mirage-loader/FabricModManifest`（`ModDiscoverer` 唯一调用点）；`modules/mirage-fabric-compat/io/mirage/fabric/{ModsJsonParser,OutputModel}` 是硬解码器早期遗留死代码，全仓无调用方。修正后：fabric.mod.json 解析 = ✅ `core/mirage-loader/FabricModManifest`（不含 mixins/accessWideners 字段） |
| `ModInitializer` / `ClientModInitializer` 接口 | ✅ `api/mirage-api/net/fabricmc/api/` |
| ~~entrypoints 实际反射调用 | ✅ `launcher/.../Launcher.callOnInitialize` + client 侧 `instanceof ClientModInitializer` 分派（`runtime-wiring-changelog` 已接通）~~ **【已过时·2026-11-01】** LS 拆分后逻辑迁至 lifecycle-starter。修正后：entrypoints 实际反射调用 = ✅ `lifecycle-starter/.../stage/ModInitStage`（`ModEntrypoints` 出入口列表 + `instanceof ModInitializer/ClientModInitializer` 分派 + 反射回退） |
| MG 生命周期事件 post | ✅ `ModConstruct/PreInit/Init/PostInit` 经 `io.mirage.eventbus` 派发 |
| Fabric `Event` / `EventFactory` | ✅ 本次落地：`modules/mirage-fabric-compat/.../net/fabricmc/fabric/api/event/` |
| `lifecycle-v1` 事件类 | ✅ 本次落地：`ServerLifecycleEvents`（Starting/Started/Stopping/Stopped）+ `ClientLifecycleEvents`（Starting/Started/Stopping） |
| lifecycle 派发桥 + Mixin 接线 | ✅ 本次落地：`io.mirage.fabric.runtime.FabricLifecycleBridge` + `FabricClientLifecycleMixin` / `FabricServerLifecycleMixin`（已注册进 `mirage.mixins.json`）；六个事件全部 firing：CLIENT_STARTING/STARTED/STOPPING、SERVER_STARTING/STARTED/STOPPING/STOPPED |
| `net.fabricmc.*` 进启动 classpath | ✅ 本次落地：`launcher/mirage-launch` 已 `implementation(:modules:mirage-fabric-compat)`，随启动 jar 进入 `mcl` |
| mod `data/` 注册进数据包管线 | ✅ P1-5：`MirageDebug.exposeModNamespaces` 在 `assets/`(CLIENT_RESOURCES) 之外同时 push `data/`(SERVER_DATA)，`extractAssets` 提取范围扩到 `data/` |
| `ClientTickEvents`（client tick 回调） | ✅ P1-6：START/END_CLIENT_TICK + START/END_LEVEL_TICK；`FabricClientTickBridge` + `FabricClientTickMixin` / `FabricClientLevelTickMixin` |
| play 阶段网络 API 表面 | ✅ P1-4：`ServerPlayConnectionEvents` / `ClientPlayConnectionEvents`（INIT/JOIN/DISCONNECT）、`ServerPlayNetworking` / `ClientPlayNetworking`（register/unregister/send/receive/canSend）、`PacketSender`、`PayloadTypeRegistry` |
| 连接事件 firing + 自定义 payload 收发 | ✅ P1-4：`FabricNetworkingBridge` + 4 个 Mixin；send 走真实 `Connection`；receive 在 `handleCustomPayload` HEAD 派发并 cancel 原版处理 |
| server entrypoints（专用服务端路径） | ✅ P1-7：`Launcher.DIST` 判定（`-Dmirage.dist` / `MIRAGE_DIST`），服务端 dist 加载 server-only mod 并实例化 `entrypoints.server`（`ModInitializer.onInitialize`） |

---

## 缺口清单

### P0：阻塞绝大多数 mod 的硬伤

#### 1. lifecycle 关闭路径缺失 ✅ 已修复（2026-10-25）

**现状**：`ClientLifecycleEvents.CLIENT_STOPPING`、`ServerLifecycleEvents.SERVER_STOPPING` / `SERVER_STOPPED`
事件类已定义、桥已就绪，现**已 firing**。注入点对齐 fabric-lifecycle-events-v1：
- `SERVER_STOPPING` → `MinecraftServer.stopServer()` HEAD
- `SERVER_STOPPED`  → `MinecraftServer.stopServer()` TAIL
- `CLIENT_STOPPING` → `Minecraft.exitWorldAndClose()` HEAD

同时把启动时机也修正为 Fabric 官方位置：`SERVER_STARTING`(`runServer` 中 `initServer()` 前)、
`SERVER_STARTED`(`runServer` 中 `buildServerStatus()` 首次调用处)、
`CLIENT_STARTED`(`run()` 中 `gameThread` 字段赋值之后)。

**验证**：MC 26.2 jar 用 JDK25 `javap` 确认上述方法/字段均存在；`gradle build` 通过（Mixin `require=1` 不会落空）。

#### 2. Fabric 游戏回调事件体系（fabric-api-events）几乎空白

**现状**：仓库里没有任何 `net.fabricmc.fabric.api.event.*` 的"游戏回调事件"实现
（除本次的 lifecycle-v1）。Fabric mod 绝大多数是注册这类回调：
- `AttackEntityCallback` / `UseEntityCallback` / `InteractionEntityCallback`
- `UseBlockCallback` / `UseItemCallback` / `ItemUsageEvent`
- `ServerTickEvents` / `ServerPlayerEvents` / `PlayerTickEvents`（`ClientTickEvents` 已于 P1-6 落地，其余仍缺）
- `EntitySpawnEvent` / `LivingEntityEvents` 等 20+ 类

**问题**：这些事件类都不存在，mod 链接期就 `NoClassDefFoundError`；即使类存在也无 Mixin 注入 post 点。

**修复**：新增 `fabric-api-events` 包（回调式 `Event<T>` 接口）+ 一批 Mixin 在 MC 对应位置注入 post。
注入统一走 Mixin 路径；**不要**对已由 `MirageTransformer` 改写的类再加 Mixin（见 AGENTS 技术注意事项第 9 节）。

#### 3. fabric-api-registry 桥接缺失

**现状**：MG 走自家 `io.mirage.api.registry.DeferredRegister`；Fabric mod 常用的
`RegistryEntryAddedCallback`、`FabricRegistryBuilder`、`Registry.register` 静态注册没有桥接。

**问题**：Fabric mod 用 `Registry.register(...)` 或注册回调时，要么 NoSuchMethod 要么不进 MG Registry。

**修复**：把 `Registry.register` / 注册回调转接到 MG `Registry` / `DeferredRegister`。

---

### P1：常见 mod 会用到的

#### 4. 网络包（fabric-api-network）✅ 已落地（2026-10-25，P1-4）

**现状**：play 阶段 API 表面已齐——`net.fabricmc.fabric.api.networking.v1` 下
`ServerPlayConnectionEvents` / `ServerPlayNetworking` / `PacketSender` / `PayloadTypeRegistry`，
client 侧 `net.fabricmc.fabric.api.client.networking.v1` 下 `ClientPlayNetworking` /
`ClientPlayConnectionEvents`；实现落在 `io.mirage.fabric.runtime.FabricNetworkingBridge`：

- 连接事件 firing（注入点对齐 fabric-networking-api-v1，均经 javap 在 26.2.jar 核实）：
  `PlayerList.placeNewPlayer` HEAD → INIT、TAIL → JOIN；`ServerGamePacketListenerImpl.onDisconnect` HEAD → DISCONNECT；
  客户端 `ClientPacketListener.<init>` RETURN → INIT、`handleLogin` RETURN → JOIN、`onDisconnect` HEAD → DISCONNECT。
- 发包：`ServerPlayNetworking.send(player, payload)` / `ClientPlayNetworking.send(payload)` 走真实原版
  `Connection.send`（`ClientboundCustomPayloadPacket` / `ServerboundCustomPayloadPacket`）。
- 收包：`ServerGamePacketListenerImpl.handleCustomPayload(ServerboundCustomPayloadPacket)` HEAD →
  `ClientPacketListener.handleCustomPayload(CustomPacketPayload)` HEAD 派发到已登记 receiver，消费则 cancel 原版处理。

**已知限制（桥接 stub，详见文末汇总）**：原版侧 payload codec 未注册；`Context` 无 `packetContext()`；
`getSendable`/`canSend` 为近似；`reconfigure` 空操作；`registerLarge` 分包未实现；login/configuration 阶段网络未做。

#### 5. 数据包（data/）✅ 已落地（2026-10-25，P1-5）

**现状**：`MirageDebug.exposeModNamespaces` 在既有 `assets/`(CLIENT_RESOURCES) 之外，同时把 mod jar 的
`data/` 作为 SERVER_DATA push 进资源包 builder；`extractAssets` 提取范围扩到 `data/`
（整目录 `pushRootPath` + 按类型 `pushPathForType` 双管齐下）。配方/战利品表/标签/advancements
因此进入数据包管线。

**验证**：`:core:mirage-loader:compileJava` 通过；运行时需真实带 `data/` 的 Fabric mod 冒烟确认
（建议用含配方/标签的测试 mod 进世界验证）。

#### 6. fabric-api-events 的 client 子集 ✅ 已落地（2026-10-25，P1-6，仅 tick）

**现状**：`ClientTickEvents` 四事件已实现并 firing：`Minecraft.tick()` HEAD/TAIL → START/END_CLIENT_TICK；
`ClientLevel.tick(BooleanSupplier)` HEAD/TAIL → START/END_LEVEL_TICK。

**说明**：该版本 fabric-api refs 中 `ClientWorldEvents` 已不存在（更名/移除），故未实现；
client 子集其余回调（`ClientEntityEvents` 等）仍缺口，归 P0-2 一并推进。

#### 7. entrypoints.server 侧 ✅ 已落地（2026-10-25，P1-7）

**现状**：`Launcher` 新增 `DIST` 判定（`-Dmirage.dist=server` 或环境变量 `MIRAGE_DIST=server`，默认 CLIENT）：

- 客户端 dist：维持原行为——跳过 server-only mod（带日志），只跑 `entrypoints.main` + `entrypoints.client`。
- 专用服务端 dist：跳过 client-only mod，跑 `entrypoints.main` + `entrypoints.server`
  （实例化 + eventBus 注册 + `ModInitializer.onInitialize()`，与 Fabric 官方语义一致）。

**说明**：当前 PCL 场景恒为 CLIENT，服务端路径为前瞻接线；单人游戏集成服务端仍走 CLIENT（与 Fabric 一致）。

---

### P2：中等复杂度 mod 需要的

#### 8. API 查找系统（fabric-api-lookup）

`FabricApiLookup` / 自定义 API 注册（方块/实体上挂自定义能力）。

#### 9. accessWideners 桥接

~~`fabric.mod.json` 的 `accessWideners` 字段是否已被 `FabricModManifest` 解析需确认；即使解析了，运行时 AT 注入（放宽访问级别）未做。~~ **【定案·2026-11-01】** `FabricModManifest` **不解析**该字段（字段级解析只存在于死代码 `ModsJsonParser`，见顶部"已过时"注记）；运行时 AT 注入未做。声明 accessWidener 的 mod 访问放宽成员会 `IllegalAccessError`。

#### 10. 指令事件（fabric-api-command）

`CommandRegistrationCallback` 未实现。

#### 13. ~~mod 自带 mixin config 不加载~~（2026-11-01 新列；**同日修正：根因与描述均错，见下**，已修）

**~~现状~~【已过时·2026-11-01 二次修订】** ~~`FabricModManifest` 不解析 fabric.mod.json 的 `mixins` 字段；`MirageMixinBootstrap` 只加载自家
`mirage.mixins.json` 单一配置。mod jar 内的 `*.mixins.json` 完全不会被注册进 mixin 环境。~~

**修正后的真相（字节码取证）**：TransformStage 从 LS 拆分起就在扫描并注册 mod jar 根目录的 `*.mixin.json`
（`FabricModManifest` 确实不解析 `mixins` 字段，但收集走的是 jar 盲扫，不走声明字段）。真正的根因是
**注册名被去掉了 `.json` 后缀**（`name.substring(0, name.length() - ".mixin.json".length())`），而
Sponge Mixin 0.8.7 的 `Mixins.addConfiguration → Config.create` 对传入名**原样**调 `getResourceAsStream`，
查不到抛 `IllegalArgumentException` 并被静默吞掉（"Error encountered reading mixin config ..."，走
Mixin 自家 logger，不进 Mirage 日志）——**mod mixin config 从初始提交起就一个都没解析成功过**；
`MirageMixinService` 里剥 `compatibilityLevel`/`mixinextras` 的后处理分支也因此从未触发。

**修复（2026-11-01，计划 `docs/plans/2026-11-01_mod-mixin-config.md`）**：
- 收集逻辑抽纯逻辑类 `core/mirage-loader/.../mixin/ModMixinConfigs`（声明优先 + 根目录盲扫兜底 + 统一 `.json` 后缀 + 去重 + 缺失 WARN）；
- `TransformStage` 改走收集器，注册名一律完整资源路径；
- `FabricModManifest` 新增防御式 `mixins` 字段（`List<JsonElement>`）+ `mixinConfigPaths()`，`ModContainer.fabricMixinConfigs` 透传——声明字段从此成为权威来源，子目录 config 可用；
- 回归 `scripts/verify_mod_mixin_config.ps1`（15 断言 + 源码守卫 8 项 + 4 种变异检验全红）。
**实机验证待用户双击 bat**：`logs/mirage-launch.log` 应出现 `后处理 mixin config: <mod的config>.json`。

#### 14. depends 依赖检查未做（2026-11-01 新列）

**现状**：`FabricModManifest.toMirageManifest()` 把 `depends` 置 null 丢弃——依赖缺失/版本不满足的 mod
照样进加载流程，炸在更远的调用栈上。

**修复方向**：与 G9 模块 `requires` 的降级策略对齐（缺失/不满足 → WARN 跳过该 mod），并在日志列明缺哪项。

---

### P3：远期

#### 11. 渲染相关（fabric-api-rendering）

`BuiltinItemRenderer` / 渲染层注册。

#### 12. 菜单（fabric-api-screen handler）

`ScreenHandlerFactory` / 客户端菜单注册。

---

## 建议的推进顺序

| 步 | 补什么 | 影响面 | 状态 |
|---|---|---|---|
| 1 | lifecycle 关闭 firing（CLIENT_STOPPING / SERVER_STOPPING/STOPPED） | 改 `FabricLifecycleBridge` + 新增/扩展 Mixin 关闭点 | ✅ 已完成 |
| 2 | fabric-api-events 基础回调事件（AttackEntityCallback 等 20+ 类）+ Mixin 注入 post 点 | 最大块：新增 event 包 + 一批 Mixin 类 | ⬜ 未做（P0-2；`ClientTickEvents` 已作为首切片落地） |
| 3 | fabric-api-registry 桥接（Fabric 注册回调 → MG Registry） | 改 fabric-compat 同模块 + MG Registry 适配 | ⬜ 未做（P0-3） |
| 4 | fabric-api-network（play 阶段） | `ServerPlayConnectionEvents` + `Networking` | ✅ 已完成（P1-4，含已知限制） |
| 5 | fabric-api-resource（data/ 注册） | 改 `MirageDebug.extractAssets`，复用资源包管线 | ✅ 已完成（P1-5） |

---

## 桥接 stub 限制汇总（P1 网络/tick 相关）

1. **原版 payload codec 未注册**：`PayloadTypeRegistry.register*` 只登记 Mirage 侧类型表。26.2 原版把已知
   payload 类型表藏在协议 codec 内部（jar 内无 vanilla `PayloadTypeRegistry` 构件），接它需 P2 深层集成
   （Mixin/AT 改协议 codec）。未注册类型的自定义包到达不了 receiver——连接事件与 `send()` 链路不受影响。
2. **`Context.packetContext()` 不存在**：该方法依赖 Fabric mixin 注入原版 listener 的扩展方法，桥接侧不提供。
3. **`getSendable` / `canSend` 为近似**：桥接侧没有"对端声明可收"的握手信息，以本端已登记 receiver 的
   payload id 近似。
4. **`reconfigure` 为记录日志的空操作**；**`registerLarge` 分包未实现**（按普通类型登记）。
5. **login / configuration 阶段网络未做**（`ServerLoginNetworking` / `ClientLoginNetworking` /
   `ServerConfigurationNetworking` 等）。
6. **`ClientWorldEvents` 未实现**：该版本 fabric-api refs 中已无此类（更名/移除）。

---

## 验证标准

- **小规模测试通过**：3–5 个极简 Fabric mod（只 `ModInitializer` / 注册一个 lifecycle 回调）从识别 → 注册 → 进游戏全流程无报错，且回调确实被触发（日志可见）。
- **大规模测试通过**：20+ 个真实 Fabric mod（含游戏回调事件、资源文件、网络包）全部正常加载。
