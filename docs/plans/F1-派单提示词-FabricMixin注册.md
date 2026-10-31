# F1 派单提示词 · 让 Fabric 桥接 Mixin 真正生效

> 用途：整段复制给执行 AI。任务边界已锁定，未经追加授权不得扩大范围。
> 关联：`docs/plans/2026-10-31_AG-总计划重梳理.md` 的 F1 条目。

---

## 执行结果：已完成并通过实机验证（2026-10-31 晚）

派单后交付质量达标，判定依据如下（取自 `mc-shili/versions/26.2-mirage/logs/mirage-launch.log`，累计 6 次启动）：

- **7 个目标类全部 `[MODIFIED]`**，计数各 5–6 次（每次启动各 1 次），含 `ClientCommonPacketListenerImpl`（证明必改项的父类拆分确实织入）。
- **16 类 Fabric 回调全部 fired**：`CLIENT_STARTING/STARTED/STOPPING`、`START_/END_CLIENT_TICK`、`START_/END_LEVEL_TICK`、`CLIENT_PLAY_INIT/JOIN`、`SERVER_STARTING/STARTED/STOPPING/STOPPED`、`SERVER_PLAY_INIT/JOIN/DISCONNECT`。
- **零注入失败**：无 `转换 … 失败`、无 `InjectionError`、无 `Critical injection failure`。
- **没有为了不报错而降 `require`**：新增的 `FabricClientDisconnectMixin` 与保留的两个注入点均为 `require = 1`。
- 必改项按方案 A 落地：`FabricClientDisconnectMixin` 打父类 + `instanceof ClientPacketListener` 守卫，`FabricClientPlayConnectionMixin` 里的 DISCONNECT 注入已移除并在注释里写明原因。

**唯一缺口**：`CLIENT_PLAY_DISCONNECT` 一次都没触发。已静态定性为**非缺陷**——`ClientPacketListener` 自覆写 `close()` 且不调用 `Connection.disconnect()`，单人退出路径不走 `handleDisconnection()`。完整分析与「不要额外注入 `close()`」的理由见 `docs/已知bug.md` 已知限制末条。

---

你是 Mirage Loader 的字节码织入工程师。这次只做一件事：把 8 个已经写好、但从未被加载的 Fabric 桥接 Mixin 接进 Mixin 配置，并验证它们在运行时真的织入了。不要顺手重构，不要扩展功能。

## 一、环境（已核实，直接采信，不要重复调研）

| 项 | 值 |
|---|---|
| 仓库根 | `D:\bing\MC\mcmoderr-my\mirage-loader` |
| 技术栈 | Java（`--release 21`，toolchain 25）、MC 26.2、SpongePowered Mixin 0.8.7、自研 `MirageClassLoader` |
| Gradle | `/c/Users/chuowei/gradle-dist/gradle-8.10.2/bin/gradle`（无 wrapper） |
| 构建 | `gradle :core:mirage-mixin:build :launcher:mirage-launch:build` |
| 部署 | 把 `launcher/mirage-launch/build/libs/mirage-launch-0.1.0.jar` 覆盖到 `..\mc-shili\libraries\io\mirage\mirage-launch\0.1.0\mirage-launch-0.1.0.jar`；覆盖前先按既有惯例备份为 `mirage-launch-0.1.0.jar.bak-YYYYMMDD-f1fabric` |
| 启动 | 仓库上一级 `启动 26.2-mirage.bat`，或 `.tmp_verify/run_game.sh`（参数与 bat 完全一致） |
| 关键日志前缀 | `[Mirage-Mixin]`、`[Mirage]` |

## 二、问题定义

`core/mirage-mixin/src/main/resources/mirage.mixins.json` 当前只注册了 14 条（`mixins` 11 条 Neo 系 + `client` 3 条），而 `core/mirage-mixin/src/main/java/io/mirage/mixin/` 下有 **8 个 `Fabric*Mixin` 类一条都没注册**。

这 8 个类是 `FabricLifecycleBridge` / `FabricClientTickBridge` / `FabricNetworkingBridge` 的**唯一调用点**，所以现在 Fabric 的 lifecycle、tick、networking 三层桥接在运行时完全不可达 —— 不是"部分可用"，是零。

配置唯一入口是 `lifecycle-starter/src/main/java/io/mirage/ls/stage/TransformStage.java:81-82`：扫 mod jar 的 `*.mixin.json`，再 `addConfiguration("mirage.mixins.json")`。**改 json 即可，不要动这段传参格式。**

## 三、逐类核实结果（我已用 javap 对 26.2.jar 逐个核对，照此执行）

| Mixin 类 | target | 注入点 | 应入分组 | javap 结论 |
|---|---|---|---|---|
| `FabricClientLevelTickMixin` | `ClientLevel` | `tick(BooleanSupplier)` HEAD/TAIL | client | 存在 |
| `FabricClientLifecycleMixin` | `Minecraft` | `<init>` RETURN / `run()` 中 `gameThread` PUTFIELD / `exitWorldAndClose` HEAD | client | 存在，`gameThread` 全方法仅 1 次 PUTFIELD，`ordinal=0` 成立 |
| `FabricClientPayloadMixin` | `ClientPacketListener` | `handleCustomPayload(CustomPacketPayload)` | client | 存在 |
| `FabricClientPlayConnectionMixin` | `ClientPacketListener` | `<init>` RETURN / `handleLogin` RETURN / **`onDisconnect`** | client | **`onDisconnect` 不在本类**，见下 |
| `FabricClientTickMixin` | `Minecraft` | `tick()` HEAD/TAIL | client | 存在 |
| `FabricServerLifecycleMixin` | `MinecraftServer` | `runServer` 内 INVOKE `initServer()` / `buildServerStatus()`、`stopServer` HEAD/TAIL | mixins | 存在，两个 INVOKE 各仅 1 次，`ordinal=0` 成立 |
| `FabricServerPayloadMixin` | `ServerGamePacketListenerImpl` | `onDisconnect` HEAD / `handleCustomPayload` | mixins | 存在 |
| `FabricServerPlayConnectionMixin` | `PlayerList` | `placeNewPlayer` HEAD/TAIL | mixins | 存在 |

### 必改项（不修这个，注册后启动即崩）

`FabricClientPlayConnectionMixin` 里 `@Inject(method = "onDisconnect", ...)` 配的是 `@Mixin(targets = "net.minecraft.client.multiplayer.ClientPacketListener")`。但 javap 显示 `onDisconnect(DisconnectionDetails)` 定义在父类 `net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl`，`ClientPacketListener` 里没有。

该类 3 个注入点全带 `require = 1`，配置里 `defaultRequire = 1`。一旦注册，这一个方法匹配失败就会抛 InjectionError → Mixin 应用失败。

处理方式（二选一，优先 A）：

- **A（推荐）**：新增 `FabricClientDisconnectMixin`，`@Mixin(targets = "net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl", remap = false)`，在 `onDisconnect` HEAD 里先判 `((Object) this) instanceof ClientPacketListener` 再调 `FabricNetworkingBridge.fireClientDisconnect(...)`，然后把原 `FabricClientPlayConnectionMixin` 里的 DISCONNECT 注入删掉。这与 fabric-networking-api-v1 的官方 mixin 做法一致。
- B：从 `FabricClientPlayConnectionMixin` 删除 DISCONNECT 注入，接受该事件暂缺 —— 需在交付说明里明确标注为遗留，不能静默略过。

## 四、注册方案

`mirage.mixins.json` 的 `mixins` 数组追加（服务端 / 通用）：

```
"FabricServerLifecycleMixin",
"FabricServerPayloadMixin",
"FabricServerPlayConnectionMixin"
```

`client` 数组追加：

```
"FabricClientLevelTickMixin",
"FabricClientLifecycleMixin",
"FabricClientPayloadMixin",
"FabricClientPlayConnectionMixin",
"FabricClientTickMixin",
"FabricClientDisconnectMixin"
```

注意三点：

1. 这 8 个类全部是 `remap = false` + `targets` 字符串，**不需要 refmap**，也不要给 json 加 `refmap` 字段。
2. `mirage.mixins.json` 本身不经过 `MirageMixinService.getResourceAsStream` 的后处理（该逻辑显式排除它），所以**不要**往这个文件里加 `compatibilityLevel` 或 `mixinextras`，会直接解析失败。
3. `MirageMixinBootstrap` 第 31 行硬编码 `env.setSide(MixinEnvironment.Side.CLIENT)`，因此 `client` 列表会加载。**这行属于范围外，不要改**（改成自动判 side 是另一个任务）。

## 五、执行顺序（禁止跳步、禁止一次性全加）

### S0 · 基线（只读，不改任何文件）

先构建并跑一次现有状态，抓 `[Mirage-Mixin]` 日志，回答一个问题：**现在注册的 14 条到底生效了没有？**

判据：日志里应出现针对 `net/minecraft/server/MinecraftServer`、`ServerLevel` 等类的 `transform #N: ... [MODIFIED]`。

- 若 14 条已 `[MODIFIED]` → 配置链路正常，进 S1。
- 若 14 条全都没有 `[MODIFIED]` → 问题不在"漏注册"，而在配置加载链路（`addConfiguration` 的后缀处理 / processor select 反射）。**立即停止，把日志和你的判断报回来**，不要继续加 8 条，加了也不会生效。

### S1 · 补服务端 3 条

只加 `mixins` 数组的 3 条 → 构建 → 备份 → 部署 → 启动 → 抓日志。

验收：`MinecraftServer`、`PlayerList`、`ServerGamePacketListenerImpl` 三个类出现 `[MODIFIED]`，且无 `转换 ... 失败`。

### S2 · 修 DISCONNECT + 补客户端 5 条

按第三节必改项处理 `onDisconnect`，再补 `client` 数组 5 条 → 构建 → 部署 → 启动 → **进一次单人世界** → 抓日志。

验收：`Minecraft`、`ClientLevel`、`ClientPacketListener`、`ClientCommonPacketListenerImpl` 均 `[MODIFIED]`。

### S3 · 回归

进世界、走动、退到主菜单、退出游戏。确认没有新增崩溃，尤其关注"退出游戏"路径（此前刚修过 `glfwDestroyWindow` 崩溃，别回归）。

## 六、验收判据（全部满足才算完成）

1. `gradle :core:mirage-mixin:build :launcher:mirage-launch:build` 零 error。
2. 日志中 6 个目标类均出现 `[Mirage-Mixin] transform #N: <类名> ... [MODIFIED]`。
3. 日志中**没有** `[Mirage-Mixin] 转换 ... 失败`，没有 `InjectionError` / `Critical injection failure` / `Mixin apply failed`。
4. 客户端能正常进入单人世界，`logs/latest.log` 无新增 ERROR。
5. **回调确实被调用**：在 bridge 的 `fire*` 方法里加一行临时打点（`MirageLog` 或 `System.out`），确认启动时 `CLIENT_STARTING`/`CLIENT_STARTED`、进世界时 `SERVER_STARTING`/`SERVER_STARTED`、每 tick 的 `START_CLIENT_TICK` 至少各触发一次，并附日志摘录。验证完把打点降为 debug 级或保留一行摘要，不要留高频 INFO 噪音。

> 反例警告：`MirageMixinBootstrap.transform()` 的 catch 会吞掉异常并返回原字节码。所以「游戏没崩」**不等于**「注入成功」。判据 2 和 5 是硬性要求，不可用"看起来正常"替代。

## 七、失败分支

| 现象 | 处理 |
|---|---|
| 某条注入 `require=1` 匹配失败 | 先用 `javap -p -c` 核对真实签名与调用次数，改 `@At` 或方法名。**不要直接把 `require` 降到 0 蒙混过关**；确需降级，必须在交付说明里写明原因和影响 |
| `NoClassDefFoundError: io/mirage/fabric/runtime/*` | bridge 不在 fat jar 或类加载顺序不对，查 `mirage-fabric-compat` 是否被打进 `mirage-launch` |
| `NoClassDefFoundError: net/fabricmc/...` | stub 类（`modules/mirage-fabric-compat/src/main/java/net/fabricmc/`，共 11 个）未进 jar |
| `Minecraft.<init>` 阶段抛 NPE / 静态初始化异常 | bridge 在客户端极早期被调，Fabric 事件对象可能尚未就绪。在 bridge 入口加空判 + try/catch，记为该事件降级，不阻断启动 |
| 加了注册但日志无 `[MODIFIED]` | 回到 S0 分支判断，属于配置链路问题，停止并报告 |

## 八、禁止事项

- 不改 `MirageMixinBootstrap` 的 `Side.CLIENT`、`addConfiguration` 传参、`processor.select` 反射逻辑。
- 不改 `MirageMixinService` 的 config 后处理规则。
- 不重构 3 个 bridge，不改事件语义。
- 不新增 Fabric API stub 之外的能力，不碰 KubeJS / IR / MID。
- 不 `git commit`、不 `git push`、不新建分支。
- 不动 `mc-shili/` 下除 `libraries/io/mirage/mirage-launch/0.1.0/` 之外的任何文件（该目录也只做备份+覆盖 jar）。

## 九、交付物

1. `git diff` 全文（预期只涉及 `mirage.mixins.json` + 1 个新增 mixin 类 + 1 个类的删减）。
2. 验证日志摘录：`[MODIFIED]` 行、`transform` 统计、回调打点行，各贴关键 5–10 行。
3. 在 `docs/改动登记表.md` 追加一行（日期 / F1 Fabric Mixin 注册 / 涉及文件 / 状态）。
4. 在 `docs/更新日志.md` 顶部追加条目，写明「8 个 Fabric Mixin 注册生效 + onDisconnect 目标修正」，并注明验证方式（启动 + 进世界 + 打点）。
5. 若第七节任一失败分支被触发且未彻底解决，在交付说明里单列「遗留问题」，不要藏。

## 十、完成后请明确回答

- 6 个目标类是否全部 `[MODIFIED]`？（逐类列 是/否）
- 3 条 lifecycle、2 条 tick、4 条 networking 回调分别是否被实际触发过一次？
- 有没有为了"让它不再报错"而降低 `require` 或删除注入？如有，列出位置与理由。
