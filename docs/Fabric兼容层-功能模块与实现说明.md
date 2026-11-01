# Fabric 兼容层 · 功能模块与实现说明

> 创建：2026-11-01（fabireAG 梳理任务产出）
> 定位：`docs/Fabric兼容层缺口清单.md` 管「还缺什么」；本文管「已有什么、怎么实现的、接口约定、要验证什么」。
> 现状以代码为准（2026-11-01 盘点，fabric-compat 16 个 java + mirage-mixin 9 条 Fabric 注册 + lifecycle-starter 接线）。

---

## 1. 在 AG 体系中的位置

AG = MG 核心 + 兼容层。Fabric 兼容层（`modules/mirage-fabric-compat`）的目标：**纯 Fabric mod 不改代码跑在 Mirage 上**（高频子集，不做全量）。

```
mods/*.jar (fabric.mod.json)
   │  ModDiscoverer（core/mirage-loader）解析 → FabricModManifest → ModManifest/ModContainer
   ▼
lifecycle-starter（LS）S6 ModInitStage：entrypoints.main/client/server 反射实例化 + onInitialize 调用
   │  mod 类链接 net.fabricmc.fabric.api.* 符号
   ▼
mirage-fabric-compat（clean-room stub）：Event/EventFactory + lifecycle/tick/networking API 表面
   │  API 类只是「壳」，真正的派发在
   ▼
io.mirage.fabric.runtime.*Bridge（3 个桥）← core/mirage-mixin 的 9 条 Fabric*Mixin 注入调用
```

**类加载约定（同族坑第 4 次的教训）**：`io.mirage.fabric.*` 走 app loader（`MirageClassLoader.loadClass` 例外白名单，与 `io.mirage.neoforge.*`/`io.mirage.mgscript.*`/`org.lwjgl.*` 同一批）；`net.fabricmc.*` stub 与 bridge 由 MirageClassLoader 一起加载（随 `lifecycle-starter`/`launcher` 的 `implementation(project(":modules:mirage-fabric-compat"))` 进启动 classpath → fat jar）。这样 mod、stub、bridge 三方看到**同一份类**；白名单改错任一侧都会 `LinkageError: loader constraint violation`。

---

## 2. 功能模块清单（以代码为准）

| # | 模块 | 代码位置 | 状态 |
|---|---|---|---|
| M1 | fabric.mod.json 解析 | `core/mirage-loader/ModDiscoverer`（发现优先级：legacy IR → mirage.mod.json → fabric.mod.json）+ `FabricModManifest`（POJO：id/version/name/description/authors/depends/environment/entrypoints） | ✅ |
| M2 | 入口接口 | `api/mirage-api/net/fabricmc/api/{ModInitializer,ClientModInitializer}` | ✅ |
| M3 | entrypoints 调用 | `lifecycle-starter/.../ModEntrypoints`（main/side 入口列表）+ `ModInitStage`（construct→preInit→onInitialize→init；instanceof 分派 + 反射回退）+ `LifecycleContext.detectDist`（`-Dmirage.dist` / `MIRAGE_DIST`） | ✅ P1-7 |
| M4 | 事件抽象 | `net/fabricmc/fabric/api/event/{Event,EventFactory}`（clean-room） | ✅ |
| M5 | lifecycle-v1 | `ServerLifecycleEvents`（STARTING/STARTED/STOPPING/STOPPED）+ `ClientLifecycleEvents`（STARTING/STARTED/STOPPING） | ✅ |
| M6 | client tick | `ClientTickEvents`（START/END_CLIENT_TICK + START/END_LEVEL_TICK） | ✅ P1-6 |
| M7 | play 阶段网络 | `ServerPlayConnectionEvents`/`ClientPlayConnectionEvents`（INIT/JOIN/DISCONNECT）+ `ServerPlayNetworking`/`ClientPlayNetworking` + `PacketSender` + `PayloadTypeRegistry` | ✅ P1-4（含 stub 限制） |
| M8 | 资源 data/ | `MirageDebug.exposeModNamespaces`：mod jar `data/` 按 SERVER_DATA push 进数据包管线 | ✅ P1-5 |
| M9 | Mixin 接线 | `core/mirage-mixin` 9 条注册进 `mirage.mixins.json`（server 3：Lifecycle/Payload/PlayConnection；client 6：Lifecycle/Payload/PlayConnection/Tick/LevelTick/Disconnect） | ✅ F1（2026-10-31 实机验证织入） |
| M10 | 静态分析遗留 | `io/mirage/fabric/{ModsJsonParser,OutputModel}` | ⚠️ **死代码，无调用方**（见 §6） |

---

## 3. 核心逻辑与实现细节

### 3.1 事件抽象（M4）—— 回调式 `Event<T>`

- `Event<T>`：`protected volatile T invoker` + `final T invoker()` + `abstract register(T)`；`register(String phase, T)` 降级为默认注册；`addPhaseOrdering` no-op（极简实现不做阶段排序）。
- `EventFactory.createArrayBacked`：`CopyOnWriteArrayList` 存监听者，每次 register 后 `rebuild()`——用反射 `Array.newInstance(type, n)` 造数组、调 `invokerFactory` 合并成新 invoker 原子替换 volatile 字段。
- **三参重载**（`type, emptyInvoker, invokerFactory`）：0 个监听者→emptyInvoker，1 个→直接用该监听者（零开销直调），≥2 个→invokerFactory 合并。
- **两参重载空数组语义**：仍调 invokerFactory（合并 lambda 循环 0 次）→ 无监听者时 `invoker().onXxx()` 是安全 no-op，不 NPE。所有 Events 类都用两参版，因此 bridge 里的 `invoker().xxx()` 永远安全。
- 线程安全：COW 列表 + volatile 读，注册可发生在任何线程、任何时刻（含首次派发后）。

### 3.2 桥接模式（M5/M6/M7 共用骨架）

三个桥（`FabricLifecycleBridge` / `FabricClientTickBridge` / `FabricNetworkingBridge`）同一套约定：

1. **Mixin 只调桥的 static 方法**，事件构造与查表细节留在桥内（与 NeoForge 侧 `NeoForgeEventBridge` 同构）。
2. **一次性打点**：每个事件首次派发打一行 `[Fabric] XXX fired`（`ConcurrentHashMap.newKeySet` 去重）。这是「回调真被调到」的第二重证据（第一重是目标类 `[MODIFIED]`）——排查"注入成功但没反应"先看这几行。
3. **payload handler 异常隔离**：`invokeServerHandler/invokeClientHandler` catch Throwable → `MirageErrors.warn`（MEL WARN 档），单个 handler 炸不影响其他 payload/mod。

### 3.3 生命周期事件注入点（M5，对齐 fabric-lifecycle-events-v1）

| 事件 | 注入点 | 位置 |
|---|---|---|
| SERVER_STARTING | `MinecraftServer.runServer` 中 `initServer()` 前 | HEAD |
| SERVER_STARTED | `runServer` 中 `buildServerStatus()` 首次调用处 | — |
| SERVER_STOPPING | `MinecraftServer.stopServer()` | HEAD |
| SERVER_STOPPED | `MinecraftServer.stopServer()` | TAIL |
| CLIENT_STARTING / STOPPING | MC 启动早期 / `Minecraft.exitWorldAndClose()` HEAD | — |
| CLIENT_STARTED | `run()` 中 `gameThread` 字段赋值之后 | — |

### 3.4 网络桥（M7）—— Mirage 侧类型表 + 真实原版收发

- **PayloadTypeRegistry**：`register` 只登记 Mirage 侧 `REGISTERED_PAYLOAD_TYPES`（四个 channel 各一个 `PayloadTypeRegistryImpl`），**不接原版协议 codec**（深层集成，缺口 P2）；`registerLarge` 两个重载按普通类型登记 + INFO 日志（分包未实现）。未登记类型注册 receiver 会打 WARN（`warnIfTypeUnregistered`）。
- **receiver 表**：global（`ConcurrentHashMap<Identifier, handler>`）+ per-listener（`Collections.synchronizedMap(WeakHashMap<listener, Map>)`）双层；查询顺序 per-listener → global。listener 断开由弱键自然回收。
- **收包派发**：`onServerCustomPayload` / `onClientCustomPayload` 返回 boolean，Mixin 据此 cancel 原版 `handleCustomPayload`；查不到 receiver 返回 false 走原版路径。
- **发包**：`send` 直接构造原版 `ClientboundCustomPayloadPacket`/`ServerboundCustomPayloadPacket` 走真实 `Connection`。
- **PacketSender**：服务端走 `listener.send(...)`；客户端带 `ChannelFutureListener` 的发送需反射读 `ClientPacketListener` 继承链的 `connection` 字段（按类缓存），拿不到则降级单参 send + 日志。
- **canSend/getSendable 近似**：以本端已登记 receiver 的 payload id 冒充"对端声明可收"（无握手信息）。
- **Context**：`ServerContext`（server/player/responseSender）、`ClientContext`（client/player/responseSender）；**无 `packetContext()`**（依赖 Fabric 官方 mixin 注入的方法，桥不提供）。

### 3.5 entrypoints 调用（M3，LS 侧）

- `LifecycleContext.detectDist()`：`-Dmirage.dist` → `MIRAGE_DIST` → 默认 CLIENT（PCL 场景恒 CLIENT；专用服务端为前瞻接线）。
- `ModEntrypoints.mainClasses(mod, dist)`：fabric mod 取 `entrypoints.main`；mirage 原生取 `mainClass`。`sideClasses(mod, dist)`：客户端取 `entrypoints.client`、专用服务端取 `entrypoints.server`（二者互斥，main 两端都跑——Fabric 官方语义）。
- `ModInitStage`：构造 → `EventBus` register → `onInitialize()`（优先 `instanceof ModInitializer` 强转，不实现则反射找同名方法）；client 侧同理 `ClientModInitializer.onInitializeClient()`。单个 entrypoint 失败 → `MirageErrors.error`（MEL ERROR 档：功能不可用但游戏继续）。

---

## 4. 接口一致性核查结论（2026-11-01）

| 核查项 | 结论 |
|---|---|
| `mirage.mixins.json` 注册 ↔ mixin 类文件 | ✅ 一一对应：Fabric 9 条（server 3 + client 6）全部有源文件；`defaultRequire: 1`（注入失败会炸而非静默跳过） |
| bridge static 方法 ↔ mixin 调用点 | ✅ 全部对上（lifecycle 7 / tick 4 / 连接 6 / payload 2） |
| Events 类事件常量 ↔ bridge invoker 调用 | ✅ 无签名漂移 |
| `net.fabricmc.*` 进启动 classpath | ✅ launcher 与 lifecycle-starter 都 `implementation(project(":modules:mirage-fabric-compat"))` |
| `io.mirage.fabric.*` 类加载白名单 | ✅ `MirageClassLoader.loadClass` 例外分支在位 |
| 代码注释里的 stub 限制 ↔ 缺口清单文末汇总 | ✅ 6 条一致（codec / packetContext / canSend 近似 / reconfigure / registerLarge / login-configuration 未做） |
| **文档 ↔ 代码漂移** | ⚠️ 三处（已修订，见 §6）：死代码被列为解析实现 / `Launcher.*` 引用过时 / 两个未单列缺口 |

---

## 5. 边界情况与重点验证清单（按风险排序）

### 5.1 需要实机/联机验证的

1. **`ClientPlayConnectionEvents.DISCONNECT` 单人不触发（已知限制，已定性）**：`ClientPacketListener` 自覆写 `close()` 不走 `Connection.disconnect()`。**待办：真实联机（远端 kick/断网）复验一次能触发**；验证前禁止在 `close()` 补注入（联机会重复派发）。
2. **payload 跨端收发端到端**：现在只有桥侧证据（fired 日志），缺一个真实「server→client→server」自定义 payload 往返的测试 mod。**注意**：原版 codec 未注册 ⇒ 未注册类型的包**到达不了 receiver**——测试 mod 必须同时在两端 `PayloadTypeRegistry.register*`，且要验证「注册了类型但仍走不走的通」这一未知项（类型表只在 Mirage 侧，原版协议层是否放行未证实）。
3. **handler 线程语义**：Fabric 官方承诺 payload handler 在 server/client 主线程被调；Mirage 的注入点（`handleCustomPayload`）理论上同为主线程，但未实测验证——写「可直接操作 world」的 mod 有回归风险。
4. **server dist 路径**（P1-7）：`MIRAGE_DIST=server` 下 client-only mod 跳过、`entrypoints.server` 实例化——纯前瞻接线，从未实机跑过。
5. **environment 字段过滤**：`FabricModManifest.environment`（`*`/`client`/`server`）的**完整语义未实现验证**——client-only mod 在 client dist 正常，但 server dist 下是否被跳过取决于 LS 端侧过滤是否消费 environment（当前 `ModEntrypoints` 只按 dist 选入口列表）。用带 `"environment": "client"` 的 mod 测 server dist。

### 5.2 已知的退化行为（写 mod 时要避）

6. **事件 phase 排序是 no-op**：`register(phase, listener)` 忽略 phase、`addPhaseOrdering` 空操作——依赖监听顺序的 mod 按注册序执行。
7. **`canSend`/`getSendable` 是近似**：按本端 receiver 表冒充对端能力；「向未注册 receiver 的对端发包」不会提前拦截，静默发出去。
8. **`reconfigure` 空操作**、**`registerLarge` 不分包**（大 payload 可能超协议上限被原版踢）。
9. **两参 `createArrayBacked` 的 invokerFactory 空数组语义**：自定义 Events 类若 invokerFactory 不容 null/空数组会炸——本项目全部 Events 用了安全写法，mod 作者自己 `EventFactory.createArrayBacked` 时要注意。
10. **`connectionOf` 反射按字段名 `"connection"` 找**：MC 更名该字段 → 静默拿 null → 带回调发送降级（有日志，不炸）。

### 5.3 结构性缺口（推动 P0/P1）

11. ~~**mod 自带 mixin config 不加载（2026-11-01 新列，建议 P1）**~~ **【已过时·2026-11-01 二次修订】** 上半句结论（`FabricModManifest` 不解析 `mixins` 字段）属实，但"mixin 引擎只加载自家配置"**错误**——`TransformStage` 一直在扫描并注册 mod jar 根目录的 `*.mixin.json`，真根因是**注册名被去掉 `.json` 后缀**，Mixin 0.8.7 对传入名原样查资源、查不到静默吞（字节码取证见缺口清单 P1-13 修正记录）——mod mixin config 实际从未生效。**已修（同日）**：收集逻辑抽 `ModMixinConfigs` 纯逻辑类、注册名统一完整资源路径、声明字段消费、子目录支持；回归 `scripts/verify_mod_mixin_config.ps1`。改造时仍须注意双注入路径互斥（§5.4-17）。
12. **depends 依赖检查未做（2026-11-01 新列）**：`FabricModManifest.toMirageManifest()` 把 `depends` 置 null 丢弃——依赖缺失/版本不满足的 mod 照样加载，炸在更远的调用栈上。建议与 G9 模块 `requires` 的降级策略对齐（缺失 WARN 跳过）。
13. **fabric-api-events 其余 20+ 回调类（P0-2）**：AttackEntityCallback / UseBlockCallback / ServerTickEvents / ServerPlayerEvents / PlayerTickEvents / EntitySpawnEvent / LivingEntityEvents 等——事件类不存在 → mod 链接期 `NoClassDefFoundError`。
14. **fabric-api-registry 桥接（P0-3）**：`Registry.register` 静态注册 / `RegistryEntryAddedCallback` / `FabricRegistryBuilder` 未转接到 MG Registry。
15. **accessWideners（P2-9，2026-11-01 定案）**：`FabricModManifest` **不解析**该字段；解析逻辑只存在于死代码 `ModsJsonParser`；运行时 AT 注入完全未做。声明 accessWidener 的 mod 访问放宽成员会 `IllegalAccessError`。
16. **login / configuration 阶段网络未做**（`ServerLoginNetworking` 等整族类不存在）。

### 5.4 改造时的既有约束（别踩）

17. **双注入路径互斥**：`MirageTransformer`（自研 ASM，现基本禁用）与 SpongePowered Mixin 不许改同一个 MC 类；给 fabric-api-events 补 Mixin 前先查目标类是否已被 transformer 改写。
18. **`defaultRequire: 1` + 注入失败升级 FATAL**（2026-11-01 起 `MirageMixinBootstrap` 关键织入失败不再吞成降级）——新增 fabric Mixin 的注入点必须先用 `javap` 在 26.2.jar 核实（含父类链，参考 `onDisconnect` 定义在 `ClientCommonPacketListenerImpl` 的教训）；构建期可用 `scripts/verify_mixin_registry.ps1` 离线校验。
19. **注册表冻结窗口**：Fabric mod 若在 `onInitialize` 之外（如 SERVER_STARTED 后）调 `Registry.register` 会撞冻结——P0-3 桥接设计时要定好「注册窗口」语义。

---

## 6. 盘点修正记录（文档漂移，只标不删）

1. **【2026-11-01】** 「fabric.mod.json 解析 = FabricModManifest + ModsJsonParser」**已过时**：运行时解析只走 `core/mirage-loader/FabricModManifest`（`ModDiscoverer` 唯一调用点）；`modules/mirage-fabric-compat/io/mirage/fabric/{ModsJsonParser,OutputModel}` 是硬解码器早期遗留，**全仓无调用方**（硬解码/NeoForge 侧各有自己的 OutputModel），属死代码——后续可移除或归档，删除前先 grep 一次确认。
2. **【2026-11-01】** 「entrypoints 反射调用 = `Launcher.callOnInitialize`」「DIST 判定 = `Launcher.DIST`」**已过时**：LS 拆分后逻辑在 `lifecycle-starter`（`LifecycleContext.detectDist` / `ModEntrypoints` / `ModInitStage`）；`Launcher` 已降级 thin wrapper。
3. **【2026-11-01】** P2-9「accessWideners 是否已被解析需确认」**定案**：见 §5.3-15。
