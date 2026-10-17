# MG-Interfaces.md — 模块间接口定义

> 版本：**v0.1**（初版，2026-10-11 起 3 天内冻结）
> 维护：A1（MG-Spec）
> 配套文档：《MG-Spec.md》（数据格式真相）、《MG-Lifecycle.md》（阶段顺序真相）、《MG-Errors.md》（错误语义真相）

---

## 0. 总则

1. 本文档是模块间通信的**唯一契约**。代码可以变，接口以本文档为准。
2. 所有接口签名均为**伪 Java 签名**，仅描述形状，不是要落地的 `.java` 文件；模块实现时自行选型（A4 运行时为最终宿主）。
3. 模块间**只通过下文接口与 MG 数据对象**通信，禁止互相引用实现类、禁止反射拿内部状态。
4. 所有方法异常统一走 `ErrorSink`（§11），不在签名上抛受检异常——错误分级由 MG-Errors.md 裁决，调用方只判断 `boolean success` 或读取结果对象。
5. 数据对象统一引用《MG-Spec.md》中的 JSON 结构，本文档不再重复字段。

---

## 1. 模块责任对照表

| 接口 | 责任 | 提供方 | 消费方 |
|---|---|---|---|
| `ModDiscoveryService` | 扫描 mods 目录，产出原始模组清单 | A4 运行时 | A4 后续阶段 |
| `ModDescriptorParser` | 读 `mod.mirage.json`，产出 `ModDescriptor` | A4 / A6（fabric.mod.json 适配） | A4 |
| `HardDecodeService` | ASM 静态分析，产出 MG 静态描述 | A2 | A4（合并器） |
| `SandboxRecordService` | 沙箱拦截运行时行为，产出 MG 动态描述 | A3 | A4（合并器） |
| `DescriptionMerger` | 静态 + 动态描述合并、校验 | A4 | A4 全部下游 |
| `TransformerPipeline` | 字节码变换编排（含 Mixin） | A4 主编排 | A4 类加载器 |
| `MixinService` | Mixin 集成与注入执行 | A5 | A4、A6 |
| `RegistryMergeService` | 注册表条目合并与冻结 | A4 | A4、A6 |
| `EventBus` / `EventBusFactory` | 事件订阅登记与派发 | A4 核心；A6 提供 Fabric 风格事件实例 | A4、A6、A7 |
| `ModInitializer` | 入口点调用 | A4 核心；A6 桥接 fabric entrypoints | A4、A6 |
| `FabricCompatBridge` | `fabric.mod.json` → MG 描述 | A6 | A4（解析阶段） |
| `ErrorSink` | 错误分级接收与输出 | A4（全局单例） | 全部模块 |

---

## 2. 发现与扫描 — `ModDiscoveryService`（A4）

**输入**：mods 目录路径列表（可多个，如 `mods/`、版本子目录）。
**输出**：未解析的原始模组候选列表。

```java
interface ModDiscoveryService {
    /** 扫描给定目录，返回每个模组文件（jar/目录）的原始句柄；损坏文件不抛异常，记录后出结果。 */
    List<RawModHandle> scan(List<Path> modRoots);
}

class RawModHandle {
    Path source;            // jar 路径
    String suggestedId;     // 从文件名粗取，仅用于报错定位
    long fileSizeBytes;
    String sha1;            // 去重与缓存校验用
}
```

- 失败回退：单个文件不可读 → WARN-LOW 记录后跳过该文件，不影响其他模组（见 MG-Lifecycle.md S1）。
- 重复 jar（同 sha1 出现两次）→ WARN，保留先发现者。

---

## 3. 解析 — `ModDescriptorParser` / `FabricCompatBridge`

### 3.1 `ModDescriptorParser`（A4）

```java
interface ModDescriptorParser {
    /** 解析 mod.mirage.json；成功返回描述，失败返回 null 并向 ErrorSink 报 FATAL。 */
    ModDescriptor parse(RawModHandle handle);
}

class ModDescriptor {
    String modId;
    String version;
    ModMetadata meta;               // 对应 MG-Spec §0.1 mod 段
    List<DependencyDecl> deps;     // MG-Spec §5
    List<Transform> transforms; // MG-Spec §1
    List<EventSubscriptionDecl> eventSubs; // MG-Spec §2
    List<RegistryEntryDecl> registryEntries; // MG-Spec §3
    ResourceDecl resources;        // MG-Spec §4
    List<EntrypointDecl> entrypoints; // MG-Spec §0.1
    String specVersion;            // mirageSpecVersion
}
```

### 3.2 `FabricCompatBridge`（A6）

```java
interface FabricCompatBridge {
    /** 判断该 handle 是否 Fabric 模组（含 fabric.mod.json）。 */
    boolean accepts(RawModHandle handle);

    /** 把 fabric.mod.json + 其 mixins 配置翻译成 ModDescriptor。
     *  不支持的 Fabric API 调用点不静默丢弃：登记到 UnsupportedApiFeature 列表返回。 */
    BridgeResult translate(RawModHandle handle);
}

class BridgeResult {
    ModDescriptor descriptor;
    List<UnsupportedApiFeature> unsupported; // A6 必须逐项分级（WARN/FATAL），禁止空着不说
}
```

- A4 在解析阶段对每个 handle 先问 A6 `accepts`，是则走 A6，否则走 A4 原生解析。

---

## 4. 硬解码 — `HardDecodeService`（A2）

```java
interface HardDecodeService {
    /**
     * 对单个模组 jar 做静态字节码分析。
     * 输入：原始 jar、A1 规范版本号。
     * 输出：StaticDecodeReport（transforms/events/registry/accessWidener 的 STATIC 候选集）。
     * 不做任何运行时执行；不可解析点必须标 unresolved=true。
     */
    StaticDecodeReport decode(RawModHandle handle, String mirageSpecVersion);
}

class StaticDecodeReport {
    String modId;
    List<Transform> staticTransforms;   // _src.origin=STATIC
    List<EventSubscriptionDecl> staticEventSubs;
    List<RegistryEntryDecl> staticRegistryEntries;
    List<AccessWidenerDecl> accessWideners;
    List<UnresolvedItem> unresolved;        // invokedynamic/反射/MethodHandle 等
}
```

- 边界：A2 不产出 `mod.mirage.json` 文件本身，只产出报告对象，由 A4 合并进描述。
- `UnresolvedItem` 数量 > 0 时报告 `completenessRatio`，A8 用于统计。

---

## 5. 沙箱记录 — `SandboxRecordService`（A3）

```java
interface SandboxRecordService {
    /**
     * 在隔离 JVM 中用原加载器加载该模组，Java Agent 拦截类加载/Mixin/事件/注册表/资源/网络。
     * 异步：启动沙箱进程，通过 RecordSink 流式回写观察项。
     * 服务端优先；client 标记的模组在服务端模式下跳过。
     */
    SandboxSession startSession(RawModHandle handle, SandboxOptions opts);
}

class SandboxSession {
    String sessionId;
    boolean success;
    DynamicDecodeReport awaitReport(Duration timeout); // 超时 → 会话失败
}
```

- A3 输出与 A2 同构（`DynamicDecodeReport` 字段同 §4，`_src.origin=DYNAMIC`），保证合并器不用区分类型。
- 沙箱进程崩溃 = WARN（该模组动态描述缺失，重放时降级为仅静态描述 + 启动时运行时再拦截一次），不直接 FATAL——但缺失动态描述的模组必须进 A9 周报。

---

## 6. 描述合并 — `DescriptionMerger`（A4）

```java
interface DescriptionMerger {
    /** 合并 ModDescriptor(若有) + StaticDecodeReport(A2) + DynamicDecodeReport(A3)。
     *  冲突规则见 MG-Spec §0.2：两边一致 → BOTH；只一边 → 标注来源；
     *  同字段矛盾 → 动态优先，但登记 ConflictNote，向 ErrorSink 报 WARN。 */
    MergedModDescription merge(RawModHandle handle,
                                ModDescriptor parsed,
                                StaticDecodeReport staticReport,
                                DynamicDecodeReport dynamicReport);
}
```

- 合并后对象是 S4/S5/S6/S7 阶段的唯一输入。

---

## 7. 字节码转换 — `TransformerPipeline`（A4）+ `MixinService`（A5）

### 7.1 `TransformerPipeline`

```java
interface TransformerPipeline {
    /**
     * 在类被 ClassLoader .defineClass 前调用。
     * 顺序：accessWideners → MG 自定义 transforms → Mixin 注入（委托 MixinService）。
     * 返回 null 表示无需修改；修改失败按 MG-Errors.md 分级。
     */
    byte[] transform(String internalClassName, byte[] originalBytes, MergedModDescription desc);
}
```

### 7.2 `MixinService`（A5）

```java
interface MixinService {
    /** 注册模组的 mixin 配置文件（.mixins.json 路径 + 类加载器域）。 */
    void registerMixinConfig(String modId, Path configPath);

    /** 对目标类字节执行已注册的全部 mixin 注入。 */
    byte[] applyMixins(String internalClassName, byte[] targetBytes);

    /** 查询某个 mixin 是否成功命中（A8 统计注入成功率用）。 */
    InjectionStats stats();
}
```

- A5 注入失败语义：目标方法不存在 → 默认 FATAL；`locals=CAPTURE_FAIL_SOFT` 的降级点 → WARN-LOW。具体判定表见 MG-Errors.md §5。
- A6 通过 `registerMixinConfig` 让 Fabric 模组的 mixin 直接生效，不重写。

---

## 8. 注册表合并 — `RegistryMergeService`（A4）

```java
interface RegistryMergeService {
    /**
     * 按加载图顺序接收各模组的 RegistryEntryDecl。
     * 重复 id 处理规则见 MG-Spec §3.3：同 modid 重复=FATAL；跨 modid 重复=FATAL。
     */
    void accept(String modId, List<RegistryEntryDecl> entries);

    /** 冻结注册表；冻结后只接受 phase=DYNAMIC 的运行时注册（经事件回调进入）。 */
    void freeze();

    /** 查询（A6/A7 读 vanilla 注册内容用）。 */
    Optional<RegistryEntryDecl> lookup(String registryId, String entryId);
}
```

- `freeze()` 触发 `mirage:registry_frozen` 事件（MG-Spec §2.3）。

---

## 9. 事件总线 — `EventBus` / `EventBusFactory`（A4 核心，A6 提供桥接）

```java
interface EventBusFactory {
    /** 在 S6 阶段创建全局 EventBus；之后不允许重建。 */
    EventBus create();
}

interface EventBus {
    /** 登记订阅（S6 从 MergedModDescription 批量灌入）。 */
    void subscribe(EventSubscriptionDecl decl);

    /** 派发事件；按 priority 降序执行；cancellable 语义见 MG-Spec §2.2。 */
    <E> E post(String eventId, E event);

    /** 查询（A8 统计事件触发率用）。 */
    EventDispatchStats stats();
}
```

- A6 额外提供 `FabricEventBridge`：把 Fabric `Event<T>` 实例适配到 `EventBus.post`，签名形状由 A6 自选，但**必须**通过 `subscribe` 登记，不得绕开总线另发。

---

## 10. 模组初始化 — `ModInitializer`（A4 核心，A6 桥接）

```java
interface ModInitializer {
    /**
     * 按加载图顺序、同层按 modid 字典序，实例化并调用 entrypoint。
     * entrypoint 构造抛异常 → 按 MG-Errors.md：pre_launch 阶段 FATAL，普通 init 按异常类型裁决。
     */
    void invokeEntrypoints(List<MergedModDescription> loadGraph);
}
```

- A6 桥接：`fabric.mod.json` 的 `entrypoints.main/client/server` 映射到 MG 的 `EntrypointDecl`，对应时机在 MG-Lifecycle.md S7 定义。

---

## 11. 错误接收 — `ErrorSink`（全局，A4 宿主）

```java
interface ErrorSink {
    /** level: "WARN_LOW" | "WARN" | "FATAL"；
     *  stage: 发现/解析/转换/注册合并/事件总线/模组初始化/加载完成（对齐 MG-Lifecycle.md）；
     *  code: MG-Errors.md §4 错误码；
     *  context: 可序列化键值对，必须包含 modId、相关类名/id。 */
    void report(String level, String stage, String code, String message, Map<String, String> context);
}
```

- FATAL 上报后：调用方尽快收尾（刷盘日志），由 A4 主循环决定中止时机，不允许模块自己 `System.exit`。

---

## 12. 接口版本规则

- 本文档任何方法签名增删 = 破坏式变更，必须：
  1. A1 升版本号（v0.1 → v0.2）；
  2. 在本文档末尾"变更记录"追加一行（日期、接口名、变更摘要）；
  3. A1 广播 A2–A9。
- 仅新增可选方法、新增字段（带默认值语义）= 兼容变更，升 patch。

### 变更记录

| 版本 | 日期 | 变更 |
|---|---|---|
| v0.1 | 2026-10-11 | 初版：定义 §2–§11 全部接口形状 |
