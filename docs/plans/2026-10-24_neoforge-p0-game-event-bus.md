# NeoForge P0：游戏事件总线 + 事件继承分发

> 关联：`docs/NeoForge兼容层缺口清单.md`（2026-10-24 复核版）P0 四条；refs/neoforge 仅作 API 签名对照

## 目标

让 NeoForge mod 的运行时事件监听真正可用：

1. `NeoEventBusImpl.post()` 按事件类型的 superclass 链 + 接口分发监听器；
2. `IEventBus` 补 `addListener`（含优先级/收取消事件的重载），`Event` 补取消语义；
3. 新增 `net.neoforged.neoforge.event.*` 游戏事件类（tick / 玩家 / 交互 / 方块 / 实体 / 生物 / 指令 / 战利品等 20+）；
4. 用 Mixin 在 MC 26.2 的对应方法注入 post 调用点。

**可验证标准**：`gradle build` 全绿；fat jar 内含全部新事件类与 Mixin 类；极简 NeoForge 测试 mod 注册 `@SubscribeEvent` 监听 `LevelTickEvent.Post` / `PlayerEvent.PlayerLoggedInEvent` 时，`latest.log` 出现 Mirage 侧的派发标记与 mod 自己的打印。

## 前置条件与阻塞项

- 工作区存在其它轮次未提交 WIP：`Launcher.java`、`MirageDebug.java`、`MirageTransformer.java`、`SandboxRecorder.java`、`LoadingScreen.java`（删除）等。**本轮不修改这些文件**，避免混入他人改动。
- 由于不碰 `MirageTransformer`，本轮**不给 `Minecraft` 类加 Mixin**（该类已被 `MirageTransformer` 改写，双路径叠加会冲突，见 AGENTS 技术注意事项第 9 节）；客户端 tick 改从 `ClientLevel.tick` 派发。
- 开工前先跑一次 `gradle :core:mirage-loader:build :modules:mirage-neoforge-compat:build :core:mirage-mixin:build` 确认基线。

## 现状分析

- `NeoEventBusImpl`（modules/mirage-neoforge-compat）：`post()` 只做 `listeners.get(event.getClass())` 精确匹配；`register()` 只扫目标对象 `getDeclaredMethods()`。
- `IEventBus` 只有 `register` / `post`，无 `addListener`；`Event` 只有 `isCancelable()` 恒 false。
- 无任何 `net.neoforged.neoforge.event.*` 类。
- Mixin 管线实际生效（`mirage.mixins.json`，defaultRequire=1），mirage-mixin 模块已有 MC 26.2 `compileOnly`。
- `modules/mirage-neoforge-compat` 目前只带 three 个 MC compile-stub（Identifier/ResourceKey/Registry），要引用 `Level`/`Player`/`LivingEntity` 等真类需补 compileOnly MC jar，并把 toolchain 提到 25（与 mirage-mixin 同款处理）。

## 改动文件清单

| 文件 | 改什么 |
|---|---|
| `modules/mirage-neoforge-compat/build.gradle.kts` | 加 `compileOnly` 26.2.jar + brigadier；toolchain 25 / release 21（读 v69 class） |
| `modules/mirage-neoforge-compat/.../net/neoforged/bus/api/IEventBus.java` | 补 `addListener` 重载 |
| `.../net/neoforged/bus/api/Event.java` | 补 `setCanceled` / `isCanceled` / `hasResult` / `getResult` / `setResult` |
| `.../net/neoforged/bus/api/EventPriority.java`（新） | 枚举 HIGHEST/HIGH/NORMAL/LOW/LOWEST |
| `.../net/neoforged/bus/api/EventBusSubscriber.java`（新） | 注解 stub（本轮只做注解存在，扫描在后续） |
| `.../io/mirage/neoforge/runtime/NeoEventBusImpl.java` | 继承层次分发、监听器按优先级排序、addListener 支持、父类方法扫描 |
| `.../io/mirage/neoforge/runtime/NeoForgeEventBridge.java`（新） | 静态派发入口：`post(Object)`，Mixin 只调这一个类，避免跨模块直接依赖事件构造细节 |
| `.../net/neoforged/neoforge/event/**`（新，约 40 类） | 事件类：tick / entity / player / living / level / 指令 / 战利品 |
| `core/mirage-mixin/src/main/java/io/mirage/mixin/NeoGameEventMixin*.java`（新，约 8 个） | Mixin 注入点 |
| `core/mirage-mixin/src/main/resources/mirage.mixins.json` | 登记新 Mixin（`mixins` / `client` 分开） |
| `core/mirage-mixin/build.gradle.kts` | 加 `compileOnly(project(":modules:mirage-neoforge-compat"))` |
| `docs/改动登记表.md`、`docs/更新日志.md`、`docs/已知bug.md`、`docs/NeoForge兼容层缺口清单.md` | 台账同步 |

> 明确不做：不改 `Launcher.java`、`MirageDebug.java`、`MirageTransformer.java`、`SandboxRecorder.java`；不做网络包/配置系统/数据包 data/ 注册/Capabilities；不做 `@EventBusSubscriber` 的运行时扫描（只留注解类）；不做服务端 DedicatedServer 链路；不给 `Minecraft` 加 Mixin。

## 每处改动要点

1. **继承分发**：`post()` 先 `event.getClass()`，再逐级 `getSuperclass()`，并遍历每级 `getInterfaces()`（递归父接口），按第一次命中的顺序合并监听器；用 `LinkedHashSet<Method>` 去重，避免子类与父类监听器重复触发。
2. **优先级**：监听器条目记录 `EventPriority`（`@SubscribeEvent(priority=...)` + `addListener(priority, ...)`），post 时按 HIGHEST→LOWEST 稳定排序。
3. **取消语义**：`Event` 增加 `cancelable` 判定（默认 `isCancelable()` 仍返回 false，子类事件按需 override）、`setCanceled` / `isCanceled`、`hasResult` / `getResult` / `setResult`。可取消事件被取消后，默认不再派发给未声明 `receiveCanceled=true` 的监听器。
4. **addListener**：实现 `addListener(Consumer<T>)`、`addListener(EventPriority, Consumer<T>)`、`addListener(EventPriority, boolean, Consumer<T>)`、`addListener(EventPriority, boolean, Class<T>, Consumer<T>)`；lambda 事件类型从泛型签名解析，解析不到时打印明确报错（不静默）。
5. **事件类**：包名/类名/构造签名与 NeoForge 26.x 一致（refs/neoforge 对照），字段只保留 mod 常用 getter：
   - `event.tick`：`LevelTickEvent.Pre/Post`、`ServerTickEvent.Pre/Post`、`PlayerTickEvent.Pre/Post`、`EntityTickEvent.Pre/Post`
   - `event.entity`：`EntityEvent`、`EntityJoinLevelEvent`、`EntityLeaveLevelEvent`、`EntityTravelToDimensionEvent`
   - `event.entity.player`：`PlayerEvent`(+`PlayerLoggedInEvent`/`PlayerLoggedOutEvent`/`PlayerRespawnEvent`/`PlayerCloneEvent`/`PlayerChangedDimensionEvent`)、`PlayerInteractEvent`(+`RightClickBlock`/`RightClickItem`/`RightClickEmpty`/`LeftClickBlock`/`LeftClickEmpty`/`EntityInteract`/`EntityInteractSpecific`)、`AttackEntityEvent`、`UseItemOnBlockEvent`
   - `event.entity.living`：`LivingEvent`(+`LivingTickEvent`)、`LivingHurtEvent`、`LivingDamageEvent`、`LivingDeathEvent`、`LivingHealEvent`
   - `event.level`：`LevelEvent`、`BlockEvent`(+`BreakEvent`/`EntityPlaceEvent`)
   - 根包：`RegisterCommandsEvent`、`LootTableLoadEvent`
6. **Mixin 注入点**（全部按 26.2 javap 实证签名）：
   - `ServerLevel.tick(BooleanSupplier)`：HEAD → `LevelTickEvent.Pre`，RETURN → `Post`
   - `ClientLevel.tick(BooleanSupplier)`：同款（client mixin）
   - `MinecraftServer.tickServer`：HEAD/RETURN → `ServerTickEvent.Pre/Post`
   - `Entity.tick()`：HEAD/RETURN → `EntityTickEvent.Pre/Post`
   - `Player.tick()`：HEAD/RETURN → `PlayerTickEvent.Pre/Post`
   - `LivingEntity.heal(F)`：HEAD → `LivingHealEvent`（可取消则直接 return）
   - `LivingEntity.hurtServer(ServerLevel, DamageSource, float)`：HEAD → `LivingHurtEvent`（可取消返回 false）；RETURN → `LivingDamageEvent`
   - `LivingEntity.die(DamageSource)`：HEAD → `LivingDeathEvent`
   - `ServerLevel.addFreshEntity(Entity)`：HEAD → `EntityJoinLevelEvent`
   - `Commands.<init>`：RETURN → `RegisterCommandsEvent`
   - `ServerPlayerGameMode.destroyBlock`：HEAD → `BlockEvent.BreakEvent`
   - 玩家登录：`PlayerList.placeNewPlayer` / `ServerGamePacketListenerImpl` 链路按 javap 结果择一，注入 `PlayerLoggedInEvent`
7. **派发入口**：所有 Mixin 只调 `io.mirage.neoforge.runtime.NeoForgeEventBridge.post(Object)`；bridge 内部持有全局 `NeoEventBusImpl` 单例（与 `FMLJavaModLoadingContext.getModEventBus()` 同一个实例），保证 mod 注册的监听器能被运行时事件触达。

## 回归测试影响面

- **可能被打破**：`NeoEventBusImpl` 的 post 语义变化（继承分发 + 取消），原有生命周期事件监听多派发一次父类监听器——生命周期事件都直接继承 `Event`，`Event` 若被误注册为父类监听器会被触发；实现时把 `Event` 基类本身排除在"父类分发"之外（只分发 `Event` 以下的实际事件父类），避免误伤。
- `mirage.mixins.json` 的 `defaultRequire: 1` 意味着任一新增注入点找不到方法都会导致启动失败——每条 Mixin 必须先用 javap 确认签名；对 26.2 不存在的目标一律不写。
- 本轮不接 MC 资源系统 / DeferredRegister，注册流程零侵入。
- mdk-example 冒烟步骤不变；新增验证靠极简 NeoForge 测试 mod（在 `modules/mirage-neoforge-compat/sample` 里扩一个带 tick 监听的样例，或临时放 `mods/`）。

## 验证步骤

1. `gradle :modules:mirage-neoforge-compat:build :core:mirage-mixin:build :launcher:mirage-launch:build` 全绿。
2. `jar tf` fat jar，核对 `net/neoforged/neoforge/event/**.class` 与 `io/mirage/mixin/NeoGameEventMixin*.class` 全部存在，且与 `mirage.mixins.json` 注册项一一对应。
3. 部署 fat jar（备份旧 jar 为 `.bak-20261024-p0`），**遵守虚拟桌面规则**：让用户双击 `..\启动 26.2-mirage.bat`，AI 不直接拉 java。
4. 读 `..\logs\latest.log`，grep `[Mirage:NeoBus]` 派发标记、`Mixin transformer` 无 `MixinTransformerError`、测试 mod 的监听打印。

## 合规边界（条件必填）

涉及 `modules/mirage-neoforge-compat` 且引用 `refs/neoforge/` 源码。NeoForge 为 LGPL 2.1：本计划**只做接口枚举与签名对照**，事件类实现为 clean-room 最小实现（仅 getter + 字段），不复制其方法体、不抄注释、不搬运内部工具类。

## 回滚方案

- 本轮为提交 `feat: NeoForge P0 游戏事件总线` 的独立 commit；回滚执行 `git revert <hash>`，不影响其它轮次 WIP（未提交文件不在本 commit 内）。
- 部署前的 fat jar 备份保留在 `mc-shili/libraries/io/mirage/mirage-launch/0.1.0/mirage-launch-0.1.0.jar.bak-20261024-p0`，回滚时拷回即可。
- 回退后按规则 6 写回退报告。
