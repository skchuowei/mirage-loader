# MG 中间层格式规范（IR v0.1 冻结）

> 冻结日期：2026-10-11
> 维护：A1
> 本文是 IR（中间表示）与面向开发者 API 的**隔离契约**。
> 所有 agent 必须遵守，IR 字段不得随意增删。

---

## 0. 两层分离

MG 有两个完全独立的格式，**严禁混用**：

| 层 | 名称 | 谁写 | 谁读 | 格式 |
|---|---|---|---|---|
| **IR 层** | `mod.mirage.json` | A2（硬解码）/ A3（沙箱）产出 | A4（运行时）消费重放 | JSON，见 §1 |
| **API 层** | Java 注解 + 接口 | 模组开发者写源码 | A4 反射调用 | Java，见 §2 |

**铁律**：
- IR 是给机器读的中间表示，开发者**不直接写** `mod.mirage.json`。
- API 是给开发者写的 Java 接口，IR 由 A2/A3 从 mod jar 自动提取。
- A4 重放时只看 IR，不直接扫描 mod 源码注解（注解是 A2/A3 的输入线索）。

---

## 1. IR 字段清单（v0.1 冻结）

### 1.1 A4 必须消费的字段（IR 核心）

这些字段 A4 运行时**必须读取并重放**：

```json
{
  "mirageSpecVersion": "0.1",
  "mod": {
    "id": "", "version": "", "group": "", "environment": "BOTH",
    "entrypoints": { "main": [], "client": [], "server": [] },
    "accessWideners": []
  },
  "dependencies": [],
  "transforms": [],
  "events": [],
  "registries": [],
  "resources": { "namespaces": [] }
}
```

字段含义见 `MG-Spec.md` §0–§6。**增删字段 = 升版本号（0.2）+ A1 广播。**

### 1.2 诊断字段（A4 不消费，仅分析用）

这些字段是 A2 硬解码器额外输出的**分析数据**，A4 **不读取**，仅供：
- 开发者调试（"这个 mod 调了哪些 MC 类"）
- A8 QA 统计（兼容率、注入成功率）
- A3 沙箱补漏对照

```json
{
  "classes": [],
  "methodCalls": [],
  "mixinTargets": [],
  "unresolved": [],
  "_stats": {}
}
```

**规则**：
- 诊断字段以 `_` 开头或放在独立命名空间，A4 忽略。
- `classes`/`methodCalls` 是完整字节码图谱，**不要求完整**，漏分析不报错。
- `unresolved` 里的条目 A4 不报错，标记为"沙箱待补"即可。
- 诊断字段格式可以随时变，不升 IR 版本号。

### 1.3 完整 IR 样例

见 `modules/mirage-harddecode` 分析 examplemod 的输出（已验证）。

---

## 2. 面向开发者的 API（独立于 IR）

开发者写 mod 时用的是 Java，**不是 JSON**：

```java
// 开发者写的源码（IR 的输入，不是 IR 本身）
@Mod("examplemod")
public class ExampleMod implements ModInitializer {
    @Override
    public void onInitialize() {
        // 注册方块、物品、事件
    }
}
```

A2 硬解码器从这段源码（编译后的 class）提取信息，**翻译成** IR JSON。

### 2.1 API 与 IR 的映射关系

| 开发者写的（API） | A2 提取到 IR 字段 |
|---|---|
| `@Mod("examplemod")` | `mod.id = "examplemod"` |
| `implements ModInitializer` + `onInitialize()` | `mod.entrypoints.main = ["com.example.ExampleMod"]` |
| `@SubscribeEvent` 方法 | `events[]` |
| `DeferredRegister.register(...)` | `registries[]` |
| `.mixins.json` 文件 | `mixinTargets[]`（诊断）+ transforms 直通 A5 |
| `fabric.mod.json` depends | `dependencies[]` |
| mod 资源目录 | `resources.namespaces` |

### 2.2 API 层约束

- API 注解和接口在 `api/mirage-api/` 模块定义。
- API 可以迭代版本，不影响 IR 版本号。
- 同一个 API 注解可以映射到不同 IR 字段（A2 负责翻译）。
- **开发者不需要知道 IR 的存在。**

---

## 3. 各 agent 适配要求

### A2（硬解码器，已完成）
- ✅ 输出已分两层：IR 核心字段 + 诊断字段
- ⚠️ `classes`/`methodCalls`/`unresolved` 应移到 `_diagnostics` 子对象下，与 IR 核心分开
- ⚠️ 不要把诊断字段当 IR 字段校验

### A3（沙箱记录器，未启动）
- 产出的**动态描述**只写 IR 核心字段（§1.1）
- 动态拦截到的注册表/事件/transform 用 `_src.origin = "DYNAMIC"` 标记
- 不输出诊断字段（沙箱本身就是动态分析，不需要再输出字节码图谱）

### A4（运行时）
- 启动时只读 IR 核心字段（§1.1）
- 遇到诊断字段（`classes`/`methodCalls` 等）**直接忽略**，不报错
- `unresolved` 条目不 FATAL，按 WARN 处理

### A5（Mixin 集成）
- 只消费 `transforms[]` 里的 Mixin 直通项和 `mixinTargets[]`
- 不解析 IR 的其他字段

### A6（Fabric 兼容层）
- 解析 `fabric.mod.json` → 填充 IR 核心字段
- Fabric API 事件注册 → 映射到 `events[]`

### A8（QA）
- 用诊断字段统计兼容率
- 不要求诊断字段完整

---

## 4. 版本规则

| 变更类型 | 版本号变化 | 谁批准 |
|---|---|---|
| IR 核心字段增删改 | 0.1 → 0.2（minor） | A1 广播 |
| 诊断字段增删改 | 不变 | A2 自行 |
| API 注解增删改 | API 包版本 | A1 通知 |
| `_src` 标记格式 | 不变（已稳定） | - |

---

## 5. 当前状态

- [x] IR v0.1 冻结（本文）
- [x] A2 硬解码器已按此格式输出
- [ ] A2 需把诊断字段移到 `_diagnostics` 子对象（小改）
- [ ] A4 运行时需加"忽略诊断字段"逻辑
- [ ] A3 沙箱记录器未启动，启动时按本文产出动态 IR
