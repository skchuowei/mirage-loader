# MG-Spec.md — MG 描述格式规范

> 版本：**v0.1**（初版，2026-10-11 起 3 天内冻结，冻结后只做增量修订）
> 维护：A1（MG-Spec）
> 消费方：A2（硬解码）、A3（沙箱记录）、A4（运行时）、A5（Mixin 集成）、A6（Fabric 兼容层）、A7（客户端）、A8（QA）
> 术语对齐：《MG-项目总纲》《MG-9agent分工方案》

---

## 0. 这份文档定义什么

MG 描述是**模组在 MG 运行时中被重放的唯一中间表示**。A2 从字节码静态分析产出**MG 静态描述**，A3 从沙箱运行时拦截产出**MG 动态描述**，A4 在加载时合并两者并重放。

- 描述文件位置：模组 jar 根目录的 **`mod.mirage.json`**（单文件；静态/动态字段合并写在同一文件中，见 §0.3 的来源标记）。
- 编码：UTF-8，无 BOM；换行 LF。
- MC 26.1+ **无混淆**：类名/方法名/字段名直接使用 JVM 内部形式（`net/minecraft/world/level/block/Block`）与 Java 源码形式（`getRenderShape`），本规范不定义任何 remap 表。
- 命名空间：`minecraft:` 为 vanilla 保留；`mirage:` 为 MG 运行时保留；模组一律使用自己的 modid。

### 0.1 顶层结构骨架

```json
{
  "mirageSpecVersion": "0.1",
  "mod": {
    "id": "examplemod",
    "version": "1.0.0",
    "group": "com.example",
    "environment": "SERVER",
    "entrypoints": { "main": ["com.example.ExampleMod"] },
    "accessWideners": ["examplemod.accesswidener"]
  },
  "dependencies": [ ... ],
  "transforms": [ ... ],
  "events": [ ... ],
  "registries": [ ... ],
  "resources": { "namespaces": ["examplemod"] }
}
```

### 0.2 来源标记约定

A2（静态）与 A3（动态）产出的字段可共存。每个数组元素允许带来源标记，便于 A4 校验与 A8 统计：

```json
"_src": { "origin": "STATIC", "confidence": "HIGH" }
```

- `origin`：`STATIC`（A2 字节码分析）/ `DYNAMIC`（A3 沙箱拦截）/ `BOTH`（两者一致合并）。
- `confidence`：`HIGH` / `MEDIUM` / `LOW`。`LOW` 条目在重放失败时按 MG-Errors.md 降级处理。

### 0.3 静态不可解析项

invokedynamic、Lambda 体、反射、MethodHandle、Unsafe 操作在 A2 侧**禁止猜测**，必须显式标注：

```json
{ "_src": { "origin": "STATIC", "confidence": "LOW", "unresolved": true },
  "note": "lambda inside PlayerAttackCallback target — deferred to A3 dynamic capture" }
```

---

## 1. 类变换（transforms）

### 1.1 两条来源通道

| 通道 | 说明 | 责任方 |
|---|---|---|
| Mixin 配置直通 | 模组自带的 `.mixins.json` 与 mixin 类不重写，由 A5（MixinService）直接消费。MG 描述里只登记引用，不复制注入细节。 | A5 |
| MG 自定义变换 | Mixin 表达不了的变换（改父类、加接口、加字段、改字段初值、访问宽化）走本节自定义结构。 | A2/A3 产出，A4 重放 |

> 原则：**能走 Mixin 就走 Mixin**；自定义变换只作为补充，避免重造注入框架。

### 1.2 类级变换（class-level）

```json
{
  "kind": "CLASS",
  "target": "com.example.ExampleBlock",
  "changes": {
    "addInterfaces": ["com.example.api.ITickableBlock"],
    "setSuperclass": null,
    "addAnnotations": ["com.example.api.RegisterBlock"]
  },
  "_src": { "origin": "STATIC", "confidence": "HIGH" }
}
```

字段说明：

- `target`：JVM 内部类名（斜杠分隔）。
- `setSuperclass`：允许 `null`（表示不修改）；修改父类是**高风险操作**，必须 `confidence: HIGH`，否则 FATAL（见 MG-Errors.md §3）。
- `addInterfaces` / `addAnnotations`：追加，不移除 vanilla 已有项。

### 1.3 方法级变换（method-level）

方法级变换直接映射 Mixin 注入器语义，A4 重放时优先转交给 A5 执行：

```json
{
  "kind": "METHOD",
  "targetClass": "net/minecraft/server/level/ServerPlayer",
  "targetMethod": {
    "name": "tick",
    "descriptor": "()V"
  },
  "injector": "INJECT",
  "at": { "type": "INVOKE", "target": "net/minecraft/world/entity/Entity.tick()V", "ordinal": 0 },
  "handler": "com.example.ExampleModHooks#onPlayerTick(Lnet/minecraft/server/level/ServerPlayer;)V",
  "priority": 1000,
  "locals": "CAPTURE_FAIL_SOFT",
  "_src": { "origin": "DYNAMIC", "confidence": "HIGH" }
}
```

- `injector` 枚举：`INJECT` / `REDIRECT` / `MODIFY_ARG` / `MODIFY_RETURN` / `OVERWRITE` / `MODIFY_VAR`。与 Mixin 注入器一一对应（对应 `@Inject`/`@Redirect`/`@ModifyArg`/`@Overwrite` 等）。
- `at.type`：`HEAD` / `RETURN` / `INVOKE` / `FIELD` / `TAIL` / `CONSTANT`。
- `targetMethod.descriptor`：JVM 描述符；26.x 无混淆，不做命名反查。
- `locals`：捕获局部变量失败策略，`CAPTURE_FAIL_SOFT`（WARN-LOW 继续）/ `CAPTURE_FAIL_HARD`（FATAL）。

### 1.4 字段级变换（field-level）

```json
{
  "kind": "FIELD",
  "targetClass": "com.example.tile.MachineTileEntity",
  "changes": {
    "addField": {
      "name": "energyBuffer",
      "descriptor": "I",
      "access": "PRIVATE",
      "initialValue": "0"
    },
    "setAccess": null
  },
  "_src": { "origin": "STATIC", "confidence": "MEDIUM" }
}
```

- `addField.initialValue`：仅支持编译期常量表达式（数字、字符串字面量、`null`）。运行时初值逻辑必须放进模组初始化阶段，不允许出现在字节码变换里。
- `setAccess`：访问宽化统一由 `mod.accessWideners` 文件描述（见 §5），字段级 `setAccess` 只用于 MG 自定义类内部。

---

## 2. 事件（events）

### 2.1 事件标识与侧别

- 事件 ID：`"examplemod:machine_tick"` 形式，`<namespace>:<path>`；vanilla/MG 运行时事件用 `minecraft:` / `mirage:`。
- 也允许用**类全限定名**引用 Fabric 风格事件接口，如 `net/fabricmc/fabric/api/event/lifecycle/v1/ServerLifecycleEvents$SERVER_STARTING`（A6 兼容层桥接，见 MG-Interfaces.md §6）。
- `side`：`CLIENT` / `SERVER` / `BOTH`。`CLIENT` 事件在专用服务器构建中注册直接降级为 WARN-LOW。

### 2.2 优先级与取消语义

```json
{
  "eventId": "mirage:server_tick",
  "listener": {
    "owner": "examplemod",
    "method": "com.example.server.MachineManager#onServerTick(Lnet/minecraft/server/MinecraftServer;)V"
  },
  "priority": 1000,
  "receiveCancelled": false,
  "_src": { "origin": "DYNAMIC", "confidence": "HIGH" }
}
```

规则（A4 事件总线必须严格遵守）：

1. **数值越大越先执行**（对齐 Fabric `EventPhase` 直觉，同时兼容 Mixin/Forge 系习惯）。默认优先级 `1000`；预置常量：`HIGHEST=2000`、`HIGH=1500`、`NORMAL=1000`、`LOW=500`、`LOWEST=0`。
2. **取消语义**：事件声明为 `cancellable: true` 时（在事件定义侧，由 `mirage:` 事件表给出，见 §2.3），监听器执行中调用 `event.setCancelled(true)` 后：
   - `receiveCancelled: false` 的后续监听器**不再被调用**；
   - `receiveCancelled: true` 的监听器**仍被调用**，且能通过 `event.isCancelled()` 读到取消状态；
   - 取消不回滚已执行监听器的副作用。
3. 非 cancellable 事件上调用取消方法 = 编程错误，按 FATAL 处理（不静默）。

### 2.3 事件定义表（MG 运行时内置）

| 事件 ID | side | cancellable | 触发时机 |
|---|---|---|---|
| `mirage:pre_launch` | BOTH | 否 | 所有模组类加载前 |
| `mirage:mods_loaded` | BOTH | 否 | 全部模组构造完成、注册表合并前 |
| `mirage:registry_frozen` | BOTH | 否 | 注册表合并完成 |
| `mirage:server_starting` | SERVER | 否 | 服务端启动 |
| `mirage:server_stopping` | SERVER | 否 | 服务端停止 |
| `mirage:client_starting` | CLIENT | 否 | 客户端启动（A7 侧） |
| `mirage:client_stopping` | CLIENT | 否 | 客户端停止 |

> 模组自定义事件同样以 `modid:event_name` 登记，运行时事件总线对模组事件与内置事件一视同仁。

---

## 3. 注册表（registries）

### 3.1 注册表条目结构

```json
{
  "registry": "minecraft:block",
  "entry": {
    "id": "examplemod:ruby_ore",
    "type": "com.example.block.RubyOreBlock",
    "data": {
      "properties": { "hardness": 3.0, "tool": "pickaxe" }
    },
    "phase": "CONSTRUCTION"
  },
  "_src": { "origin": "DYNAMIC", "confidence": "HIGH" }
}
```

- `registry`：注册表 ID。vanilla 保留：`minecraft:block`、`minecraft:item`、`minecraft:entity_type`、`minecraft:block_entity_type`、`minecraft:sound_event`、`minecraft:particle_type`。模组注册表用 `<modid>:<name>`。
- `entry.id`：命名空间 ID，必须全小写，`[a-z0-9_/-]`。
- `entry.type`：实现类全限定名（无混淆，可直接 new/反射调用构造）。
- `entry.data`：该注册表序列化需要的额外参数；`type` 无法表达的语义放这里。
- `entry.phase`：
  - `CONSTRUCTION`：注册表冻结前注册（常规方块/物品/实体）；
  - `DYNAMIC`：注册表冻结后由模组运行时注册（A3 动态捕获的运行时注册表行为）。`DYNAMIC` 条目在 A4 重放时必须在 `mirage:registry_frozen` 之后、对应阶段的事件回调里执行。

### 3.2 配置条目

模组自身配置不走 MC 注册表，单独声明：

```json
{
  "kind": "CONFIG",
  "entry": {
    "id": "examplemod:main_config",
    "class": "com.example.config.MainConfig",
    "path": "config/examplemod.json"
  }
}
```

### 3.3 ID 冲突规则

同一注册表内 `entry.id` 重复 = 冲突：

1. 先注册者获胜，后注册者按 MG-Errors.md 处理：同 modid 重复 = FATAL；跨 modid 重复 = FATAL（不静默丢弃）。
2. 不允许"后注册覆盖先注册"。模组要替换 vanilla 物品必须显式走 `transforms`（Mixin/@Overwrite 语义），不能靠注册表覆盖。

---

## 4. 资源（resources）

### 4.1 命名空间

```json
"resources": { "namespaces": ["examplemod"] }
```

- 命名空间 = modid，小写 `[a-z0-9_]`；与 jar 内实际资源目录一一对应。
- 禁止两个模组声明同一命名空间（FATAL）。

### 4.2 资源包路径（client）

根：`resources/<namespace>/`

```
resources/examplemod/
  textures/block/ruby_ore.png
  models/block/ruby_ore.json
  blockstates/ruby_ore.json
  lang/en_us.json
  lang/zh_cn.json
  sounds/examplemod/machine.ogg
```

- 路径全小写；文件名 `[a-z0-9_./-]`；禁止 `//`、禁止 `..`。
- lang 文件按 `lang/<locale>.json`，locale 全小写下划线分隔（`zh_cn`）。

### 4.3 数据包路径（server / 共享）

根：`data/<namespace>/`

```
data/examplemod/
  recipes/ruby_ore_smelting.json
  loot_tables/blocks/ruby_ore.json
  tags/blocks/mineable/pickaxe.json
  advancements/...
```

- 共享资源（方块模型+配方）按 vanilla 约定同时放 `resources/` 与 `data/`，MG 运行时分别注册进客户端资源包与服务端数据包，不做合并。

---

## 5. 依赖（dependencies）

### 5.1 依赖声明结构

```json
"dependencies": [
  {
    "modId": "mirage-api",
    "version": ">=1.0.0 <2.0.0",
    "type": "REQUIRED",
    "side": "BOTH"
  },
  {
    "modId": "jei-integration",
    "version": "*",
    "type": "OPTIONAL",
    "side": "CLIENT"
  },
  {
    "modId": "some-other-mod",
    "type": "INCOMPATIBLE",
    "side": "BOTH"
  }
]
```

- `modId`：被依赖模组的 `mod.id`；`mirage:` 保留段用于运行时自身（如 `"modId": "mirage-core", "version": ">=0.1"`）。
- `version`：语义化版本范围表达式：
  - `*`（任意）、`>=x`、`<=x`、`>x`、`<x`、`x.y.z`（精确）、区间 `>=a <b`（空格分隔交集）。
- `type`：
  - `REQUIRED`：缺失 = FATAL，不启动该模组且整体中止；
  - `OPTIONAL`：缺失 = WARN-LOW，模组继续加载（功能降级）；
  - `RECOMMENDED`：缺失 = WARN，在周报中提示；
  - `INCOMPATIBLE`：同时存在 = FATAL。
- `side`：`CLIENT` / `SERVER` / `BOTH`。专用服务器构建中 `CLIENT` 依赖自动豁免（按 OPTIONAL 处理并 WARN-LOW）。

### 5.2 MC 版本与排序指令

```json
"dependencies": [
  { "modId": "minecraft", "version": ">=26.1 <27.0", "type": "REQUIRED", "side": "BOTH" }
],
"loadOrder": { "after": ["mirage-registry"], "before": ["examplemod-companion"] }
```

- `minecraft` 作为特殊 modId 声明 MC 版本约束。
- `loadOrder.after/before`：**与依赖无关的纯排序提示**。即使不依赖对方，也可声明"我必须在它之后"。排序环 = FATAL。

### 5.3 加载图生成规则（A4 消费）

1. 收集全部 `mod.mirage.json` → 建节点；
2. `REQUIRED` / `OPTIONAL` / `RECOMMENDED` 连有向边 `依赖者 → 被依赖者`；`loadOrder.after` 连 `本mod → after目标`；`loadOrder.before` 连 `before目标 → 本mod`；
3. 拓扑排序；同层节点按 modid 字典序排序，**保证确定性**（禁止 HashMap 遍历顺序）；
4. 环、缺失 REQUIRED、命中 INCOMPATIBLE → FATAL，错误信息必须列出完整环路径（见 MG-Errors.md §6）。

---

## 6. 访问宽化（accessWideners）

模组根目录声明文件列表（见 §0.1 `mod.accessWideners`），格式沿用 Fabric Access Widener 单行语法（Apache 2.0 可参考），MG 不另造格式：

```
accessWidener	v1	named
accessible	field	net/minecraft/world/level/block/state/BlockState	LIGHT_PROPERTIES	Lnet/minecraft/world/level/light/LightProperties;
extendable	method	net/minecraft/server/level/ServerPlayer	getDimensionSelector ()Ljava/util/Optional;
```

- 26.x 无混淆，一律 `named`，不存在 `intermediate` 命名。
- 访问宽化在 `S4 字节码转换` 阶段最先执行，先于本模组自己的 `transforms`。

---

## 7. 版本与演进

- `mirageSpecVersion`：本规范版本。当前 `0.1`。
- A1 冻结后任何字段增删必须升 minor 版本（`0.2`），并在 MG-Interfaces.md 记录变更广播。
- A2/A3 产出的 MG 文件若 `mirageSpecVersion` 高于运行时支持版本 = FATAL（拒绝重放，提示用户升级 MG）；低于支持版本 = WARN 并尝试迁移（迁移映射表由 A1 维护）。
