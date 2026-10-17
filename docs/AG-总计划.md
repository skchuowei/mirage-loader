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
- [ ] entrypoints 实际反射调用（运行时 agent 在补）
- [ ] fabric-api-lifecycle-v1：服务器/客户端生命周期事件
- [ ] fabric-api-events：基础事件（客户端启动、服务端启动）
- [ ] fabric-api-registry：注册事件（DeferredRegister 的 Fabric 版）
- [ ] fabric-api-network：网络包（先做最简单的 S2C/C2S）
- [ ] fabric-api-resource：资源包/数据包（后置）

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
