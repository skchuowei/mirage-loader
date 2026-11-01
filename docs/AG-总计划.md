# AG 项目总计划

> AG = 完整项目结构：MG 加载器核心 + Fabric/NeoForge/Forge 兼容层
> MG = 加载器本身（核心运行时）
> 创建：2026-10-11

---

## AG 组成

```
AG
├── MG 核心（已有，io.mirage）
│   ├── launcher/mirage-launch       启动入口
│   ├── core/mirage-loader           类加载、mod 发现、生命周期
│   ├── core/mirage-mixin            Mixin 集成（当前空壳）
│   ├── core/mirage-eventbus        事件总线
│   └── api/mirage-api               Mod 开发者 API
│
├── 兼容层（modules/）
│   ├── mirage-fabric-compat        Fabric mod 兼容（空壳，已有 FabricModManifest）
│   ├── mirage-neoforge-compat      NeoForge mod 兼容（新建，clean-room）
│   └── mirage-forge-compat         Forge mod 兼容（远期，LGPL 只做行为观察）
│
├── 工具层（modules/）
│   ├── mirage-harddecode           ASM 静态分析（开发中）
│   ├── mirage-config               配置文件（空壳）
│   ├── mirage-network              网络包（空壳）
│   └── mirage-kotlin               Kotlin DSL（空壳）
│
├── gradle-plugin/mirage-gradle-plugin  构建工具链
└── mdk/mdk-example                 Mod 开发模板
```

---

## 参考材料（已在 refs/ 下）

| 参考 | 路径 | 许可证 | 用法 |
|---|---|---|---|
| Fabric Loader | `refs/fabricmc/fabric-loader/` | Apache 2.0 | 可参考架构，注意 NOTICE |
| Fabric API | `refs/fabricmc/fabric-api/` | Apache 2.0 | 可参考事件/注册表 API |
| NeoForge | `refs/neoforge/` | LGPL 2.1 | **只做行为观察/接口枚举，禁止抄代码** |

---

## 分组件计划

### 1. MG 核心完善（当前进行中）

状态：运行时 agent 在做事件触发、Fabric 入口调用、注册表接通。

**下一步：**
- [ ] 接入真正的 SpongePowered Mixin（mirage-mixin 模块当前空壳）
- [ ] 验证 DeferredRegister 挂到 MC 26.2 Registries
- [ ] 跑通一个真实 mod

### 2. Fabric 兼容层（mirage-fabric-compat）

**目标**：让纯 Fabric mod 不改代码就能在 AG 上跑。

**范围（高频子集，不做全量）：**
- [x] fabric.mod.json 解析（已有 FabricModManifest）
- [x] ModInitializer / ClientModInitializer 接口（已有）
- [x] ~~entrypoints 实际反射调用（`Launcher.callOnInitialize` + client 侧分派，runtime-wiring 已接通）~~ **【已过时·2026-11-01】** 见下方修正条目（迁至 lifecycle-starter）
- [x] entrypoints 实际反射调用（2026-11-01 修正：LS 拆分后在 `lifecycle-starter/ModInitStage` + `ModEntrypoints` + `LifecycleContext.detectDist`，原 `Launcher.callOnInitialize` 引用已过时）
- [x] fabric-api-lifecycle-v1 核心：`ServerLifecycleEvents` + `ClientLifecycleEvents`（Event/EventFactory 抽象类 + 6 个 lifecycle 事件 + `FabricLifecycleBridge` + 2 个 Mixin 接线；2026-10-25 落地，详见 `docs/Fabric兼容层缺口清单.md`）
- [~] fabric-api-events：基础回调事件（20+ 类）——**P1-6 首切片已落地**：`ClientTickEvents` 四事件（START/END_CLIENT_TICK + START/END_LEVEL_TICK）+ 2 个 Mixin；其余回调类（AttackEntityCallback / UseBlockCallback / ServerTickEvents 等）仍缺，归 P0-2
- [~] **mod 自带 mixin config 加载（2026-11-01 新列，缺口清单 P1-13）**：`FabricModManifest` 不解析 `mixins` 字段、mixin 引擎只加载自家配置——真实 Fabric mod 链接期炸的主因之一
- [~] **depends 依赖检查（2026-11-01 新列，缺口清单 P1-14）**：`toMirageManifest()` 丢弃 depends，依赖缺失的 mod 照样加载
- [ ] fabric-api-registry：注册事件（RegistryEntryAddedCallback / FabricRegistryBuilder 桥接）
- [x] fabric-api-network：play 阶段网络包（ServerPlayConnectionEvents / ClientPlayConnectionEvents / ServerPlayNetworking / ClientPlayNetworking / PacketSender / PayloadTypeRegistry + `FabricNetworkingBridge` + 4 个 Mixin；send 走真实 Connection；2026-10-25 P1-4 落地，已知限制见缺口清单文末汇总）
- [x] fabric-api-resource：资源包/数据包（assets 已接；data/ 经 SERVER_DATA push 进数据包管线，2026-10-25 P1-5 落地）
- [x] entrypoints.server 侧：`Launcher.DIST` 判定 + 专用服务端路径实例化 `entrypoints.server`（P1-7；PCL 场景恒 CLIENT，服务端为前瞻接线）

**验收**：10 个小型 Fabric mod 中至少 5 个能跑。

### 3. NeoForge 兼容层（mirage-neoforge-compat，新建）

**目标**：让 NeoForge mod 通过行为观察 + clean-room 实现跑在 AG 上。

**原则**：LGPL 2.1 禁止抄代码。只看 `refs/neoforge/` 的 API 签名和行为，自己写实现。

**范围（按优先级）：**
- [ ] neoforge.mods.toml 解析
- [ ] @Mod 注解处理（MG API 已有 @Mod，对接 NeoForge 的 mod 元数据）
- [ ] NeoForge 事件总线桥接（EventBus 事件名映射到 MG EventBus）
- [ ] DeferredRegister / RegistryObject 的 NeoForge 等价物
- [ ] @SubscribeEvent 的 NeoForge 注解兼容
- [ ] 网络通道（SimpleChannel）后置

**验收**：1 个简单 NeoForge mod（只有 @Mod + 一个事件）能加载。

### 4. Forge 兼容层（远期）

LGPL 2.1，工作量大。等 NeoForge 兼容层跑通后再评估。

### 5. 硬解码器（mirage-harddecode，开发中）

Layer 1：ASM 静态分析 mod jar，输出 MG 静态描述 JSON。

### 6. 沙箱记录器（Layer 2，等 Layer 1 出来后启动）

多 JVM 沙箱，Java Agent 拦截类加载，记录 mod 运行时行为。

---

## 并行策略

| 阶段 | 并行 agent | 说明 |
|---|---|---|
| 当前 | 运行时完善 + 硬解码器 | 两个 agent 在跑 |
| 下一步 | Fabric 兼容 + NeoForge 兼容骨架 | 等运行时完善回来 |
| Layer 2 | 沙箱记录器 | 等硬解码器输出格式稳定 |

---

## 许可证红线

- Fabric/Mixin 系：Apache 2.0 / MIT，可参考
- NeoForge/Forge：LGPL 2.1，**只看 API 签名和行为，不抄实现**
- AG 自身：MIT（见 PLAN.md D0 决策）
