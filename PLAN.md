# Mirage Loader（幻像加载器）· 项目计划 v0.1

> 一句话定位：**介于 Fabric 与 NeoForge 之间的"现代友好型" Minecraft 加载器**
> 轻量如 Fabric，开箱友好如 Forge；既要启动快、核心薄，又要让 Mod 开发者少写样板。
>
> 目标运行环境：Minecraft **26.2**（Java 25），PCL / 官方启动器均可接入。

---

## 1. 定位与差异化

### 1.1 为什么需要第四个加载器

| 维度 | Fabric | NeoForge | **Mirage（目标）** |
|---|---|---|---|
| 启动额外开销 | ~1–2s | ~4–8s | **目标 < 2s** |
| 核心 jar 体积 | ~1.5MB | ~8MB+ | **目标 < 3MB** |
| Mod 开发者样板 | 多（手写 entrypoints、Mixin 包、混事映射） | 少（@Mod + DeferredRegister） | **少，且构建工具链现代化** |
| 事件模型 | 裸 Mixin 回调 | 强类型事件总线（重） | **轻量事件总线 + 可选 Mixin** |
| 抽象层厚度 | 极薄 | 厚（兼容旧 Forge 包袱） | **薄，但提供"约定"层** |
| 新人上手 | 需读 Wiki | 有 MDK 但 Gradle 脚本老旧 | **一键 MDK + Kotlin DSL** |
| Mod 生态 | 最大（可直接吃） | 中 | **通过兼容层吃 Fabric 生态** |

### 1.2 Mirage 的"魂"

1. **它是一个全新的、独立的加载器**——运行时**不依赖** Fabric Loader / NeoForge / Forge 任何代码。
   用户机器上不需要先装任何其他加载器；PCL 选 `26.2-mirage` 版本即可启动。
   （"Fabric 兼容层"是 Mirage 自己实现的**格式解析器**，不是调用 Fabric Loader。）
2. **核心只做四件事**：类加载隔离、字节码注入、Mod 发现与排序、生命周期事件总线。
   其余（网络、资源、配置 GUI、物品注册 DSL、Fabric 兼容…）全部作为**可插拔模块**。
3. **模块按需加载（懒加载）**：本体启动时只加载核心 jar；
   只有当某个 Mod 在 `mirage.mod.json` 里声明 `requires: ["mirage-config"]`，
   或代码路径真正首次调用 `MirageConfig.builder()` 时，对应模块 jar 才被拉入 `MirageClassLoader`。
   **不调用 = 不加载 = 零开销**。这是 Mirage 启动快、内存小的根本保证。
4. **兼容 Fabric Mod 是杀手级特性**：`mirage-fabric-compat` 模块按需加载，
   把 `fabric.mod.json` 翻译成 Mirage 描述符。目标：**50% 以上纯客户端 Fabric Mod 无需改动即可运行**。
5. **开发体验对齐 2026 年**：Gradle 9 + Kotlin DSL + Version Catalog + 内置 Mojang Mappings。

---

## 2. 核心设计决策（已冻结，后续推翻需评审）

| # | 决策点 | 选择 | 理由 |
|---|---|---|---|
| D0 | 开源协议 | **MIT** | 最大化开放度，允许闭源 Mod 商业链接，不传染。 |
| D1 | 字节码注入方案 | **内嵌 SpongePowered Mixin（自维护 fork）** | Mixin 是字节码处理库，不是加载器；我们把它 shade 进 `mirage-loader`，对外不暴露"依赖外部加载器"。 |
| D2 | 映射层 | **直接使用 Mojang 官方 ProGuard 映射**，不做 Intermediary | 简化开发者心智：编译期 = 运行期 = 反混淆。代价：跨 MC 版本升级时 Mod 需重编译——这正是"轻量"要付出的代价，写进 README。 |
| D3 | Mod 描述符格式 | **`mirage.mod.json`**（JSON，非 TOML），并自带 `fabric.mod.json` 解析器 | JSON 无外部依赖、跨语言友好；兼容层顺手解析 Fabric 描述符。 |
| D4 | 入口点模型 | **`@Mod("modid")` 注解主类 + 自动发现** | NeoForge 式约定优于配置，少写配置。 |
| D5 | 事件总线 | **自研轻量总线**（注解 `@SubscribeEvent` + 按类分桶），不照搬 NeoForge 的通用事件系统 | 启动快、内存小；复杂事件通过 `Event<T>` 泛型挂接。 |
| D6 | 构建工具 | **Gradle 9.x + Kotlin DSL**，自研 `mirage-gradle` 插件 | Loom 太黑盒；我们的插件要能让开发者看懂每一行在干什么。 |
| D7 | 语言 | 加载器本体 100% Java；可选 `mirage-kotlin` 模块提供 Kotlin DSL | 不强制 Kotlin，但对愿意用的人提供糖。 |
| D8 | 服务端 | **双端**（client + dedicated server），公共 API 不引用 client 类 | 服务器场景必须支持。 |
| D9 | 类加载器 | **单层隔离 + 模块懒加载**：`MirageClassLoader` 挂在 Launch 之上；核心 jar 启动时加载，模块 jar 仅在被 `requires` 或首次类引用时才拉入 | 避免多层 URLClassLoader 复杂度；这是"不调用不加载"的物理基础。 |
| D10 | 兼容性目标版本 | **只支持 MC 26.2 起**，不做向下兼容 | 新项目不背历史包袱。 |
| D11 | 对其他加载器的立场 | **零依赖**：不加载、不反射、不 classpath 引用 Fabric Loader / NeoForge / Forge 的任何类 | "独立加载器"是硬约束。 |

---

## 3. 系统架构

```
┌──────────────────────────────────────────────────────────────┐
│  启动器（PCL / 官方）                                          │
│   versions/26.2-mirage/26.2-mirage.json  (inheritsFrom=26.2)  │
└────────────────────────┬─────────────────────────────────────┘
                         │ java -cp ...
                         ▼
┌──────────────────────────────────────────────────────────────┐
│  mirage-launch  (入口，被 -Dmirage.launch=... 指定)           │
│   1. 解析启动参数、定位 .minecraft/mods                        │
│   2. 创建 MirageClassLoader                                    │
│   3. 加载 Mod 描述符、做依赖拓扑排序                            │
│   4. 引导 Mixin 环境，把 Mixin 配置注入至 MC 类加载前          │
│   5. 反射调用 net.minecraft.client.main.Main                   │
└────────────────────────┬─────────────────────────────────────┘
                         ▼
┌──────────────────────────────────────────────────────────────┐
│  JVM 运行时                                                    │
│  ┌────────────────────┐    ┌──────────────────────────┐     │
│  │ MirageClassLoader    │───▶│ MixinTransformer (ASM)    │     │
│  │  - MC 原版 jar       │    │  - 读 *.mixins.json       │     │
│  │  - Mod jars          │    │  - 在类加载期织入          │     │
│  │  - 库 jars           │    └──────────────────────────┘     │
│  └─────────┬──────────┘                                       │
└────────────┼──────────────────────────────────────────────────┘
             ▼
┌──────────────────────────────────────────────────────────────┐
│  mirage-loader-api (Mod 开发者看到的世界)                      │
│   - @Mod / @SubscribeEvent / @Entrypoint                      │
│   - EventBus / ModContainer / Loader                          │
│   - 注册表：DeferredRegister\<T\>（轻量版）                    │
└──────────────────────────────────────────────────────────────┘
             ▲
             │ 可选模块
┌────────────┴─────────────────────────────────────────────────┐
│  mirage-fabric-compat   mirage-config   mirage-network        │
│  mirage-kotlin          mirage-resources                   │
└──────────────────────────────────────────────────────────────┘
```

---

## 4. 仓库与模块划分

```
mirage-loader/
├── PLAN.md                      # 本文件
├── settings.gradle.kts
├── build.gradle.kts             # 根构建
├── gradle/
│   └── libs.versions.toml       # Version Catalog
├── launcher/                    # 启动器侧：被 java -cp 调用的入口
│   └── mirage-launch/
├── core/                        # 加载器核心
│   ├── mirage-loader/           # 类加载、Mod 发现、生命周期
│   ├── mirage-mixin/            # Mixin 引导与配置扫描
│   └── mirage-eventbus/         # 事件总线
├── api/
│   └── mirage-api/              # Mod 开发者看到的全部 API
├── modules/                     # 可选模块
│   ├── mirage-fabric-compat/   # 解析 fabric.mod.json
│   ├── mirage-kotlin/
│   ├── mirage-config/
│   └── mirage-network/
├── gradle-plugin/
│   └── mirage-gradle-plugin/    # 开发者用的 Gradle 插件
├── mdk/                         # Mod 开发模板（一键复制）
│   └── mdk-example/
└── docs/
    ├── architecture.md
    ├── modder-guide.md
    └── dev-setup.md
```

---

## 5. 里程碑（每个 M 都有"可玩"产出）

> 顺序强约束：M1 → M2 → M3 是技术主轴，任何一步跑不通就不进入下一步。

### M0 · 架构冻结（当前）
- [x] 定位、差异化、技术选型写入本文件
- [ ] 在 `docs/architecture.md` 画出类加载时序图
- [ ] 列出 MC 26.2 的 `Main` 入口签名、main args 列表（反编译 26.2.jar 确认）

### M1 · 启动握手（预计 2–3 天）
**目标：** PCL 选 `26.2-mirage` 版本能进入游戏主菜单，日志里看到 `[Mirage] Hello from Mirage Launch 0.0.1`。
- [ ] 写 `mirage-launch`：一个带 `main(String[])` 的瘦入口
- [ ] 构造 `versions/26.2-mirage.json`，`inheritsFrom: 26.2`，覆盖 mainClass 为 `io.mirage.loader.launch.Launcher`
- [ ] Launcher 内反射调用原 `net.minecraft.client.main.Main`
- [ ] **验收：** 原生 MC 能正常进入主菜单，无报错。

### M2 · Mod 发现与隔离（预计 3–5 天）
**目标：** `mods/` 目录放一个空 Mod jar，日志能列出 Mod id、版本、依赖。
- [ ] `mirage.mod.json` schema 定义
- [ ] `MirageClassLoader`：扫描 `mods/*.jar`，读描述符，做依赖拓扑
- [ ] ModContainer 数据结构
- [ ] **验收：** 一个只含 `mirage.mod.json` + 空主类的 jar 能被识别、加载、不崩溃。

### M3 · Mixin 管线打通（预计 5–7 天，最难）
**目标：** 写一个 Mixin，把 MC 启动日志某一行改写成 `[Mirage] patched!`，游戏能正常跑。
- [ ] 引入 SpongePowered Mixin（自建 fork 或临时用 upstream）
- [ ] 在 `MirageClassLoader` 上挂 `MixinTransformer`
- [ ] 读取 Mod jar 内的 `*.mixins.json`
- [ ] **验收：** Mixin 命中目标方法，日志改写成功，无类加载冲突。

### M4 · 生命周期 + 事件总线（预计 3 天）
**目标：** Mod 主类收到 `ModInitEvent`、`ClientSetupEvent`，日志打印。
- [ ] `@Mod` 注解处理器
- [ ] EventBus：按事件类分桶，支持 `@SubscribeEvent`
- [ ] 定义首批事件：`ModConstructEvent`、`ClientStartingEvent`、`ServerStartingEvent`
- [ ] **验收：** 示例 Mod 在三个阶段各打一行日志。

### M5 · 注册中心（预计 5 天）
**目标：** 示例 Mod 注册一个自定义物品，进游戏能拿到。
- [ ] `DeferredRegister<T>` 轻量实现
- [ ] 挂接 MC 的 `Registries`（26.2 版本反编译后确认入口）
- [ ] Creative Tab 注册
- [ ] **验收：** 物品栏能看到自定义物品，能扔到地上。

### M6 · Gradle 插件 + MDK（预计 5 天）
**目标：** 新人从 GitHub clone MDK，改 `modid`，10 分钟内跑起客户端。
- [ ] `mirage-gradle-plugin`：自动配 classpath、下载 MC、应用官方映射
- [ ] MDK 模板：含示例物品、示例事件、示例 Mixin
- [ ] **验收：** 在一台干净机器（无 Gradle 缓存）跑通 `./gradlew runClient`。

### M7 · Fabric 兼容层原型（预计 7–10 天）
**目标：** 把一个真实的小型 Fabric 客户端 Mod（如一个简单 HUD Mod）放进 `mods/` 能跑。
- [ ] `fabric.mod.json` → `ModContainer` 翻译
- [ ] `ClientModInitializer.onInitializeClient()` 桥接到 Mirage 生命周期
- [ ] 不要求支持 Fabric API 的全部模块，先做 `fabric-api-lifecycle-v1`
- [ ] **验收：** 选 3 个知名小型 Fabric Mod 做测试矩阵，至少 2 个跑通。

### M8 · 文档、示例、发布 0.1.0
- [ ] `docs/dev-setup.md`：环境搭建
- [ ] `docs/modder-guide.md`：写第一个 Mod
- [ ] 发布到 GitHub（先不开源协议待定，建议 LGPL-2.1）
- [ ] 做一个"幻像"主题示例 Mod：把主菜单 Logo 换成自定义图案

---

## 6. 关键技术难点与对策

| 难点 | 风险 | 对策 |
|---|---|---|
| MC 26.2 是 2026-06 新版，内部类名/方法签名可能有大改 | Mixin target 全部找不到 | M0 阶段先反编译 `26.2.jar`，建立目标方法清单；M3 优先选稳定方法做 PoC |
| Java 25 的类加载器行为（密封类、hidden class） | 反射调用 Main 失败 | 用 `MethodHandles.privateLookupIn` 而非 `setAccessible(true)` |
| Mixin 与新版 JDK 的兼容 | Mixin 内部用的反射 API 在 JDK 25 被封 | 维护 Mixin fork，及时跟进；或在 M3 之前先验证 upstream Mixin 是否支持 JDK 25 |
| PCL 启动器对自定义 mainClass 的处理 | 启动器不允许覆盖 mainClass | M1 用 `inheritsFrom` + 版本 json 覆盖，PCL 已验证支持 |
| Mod 依赖环 / 缺失 | 启动崩溃难定位 | M2 就做严格拓扑排序，错误信息打印完整依赖链 |

---

## 7. 开放问题（需要后续决策，不阻塞 M1）

1. **Mod 元数据是否签名？** 短期不做，v1.0 再考虑。
2. **是否内置 Mod 更新检查？** 不做，交给启动器。
3. **是否支持从 `mods/` 加载 jarjar（嵌套 jar）？** M2 后期加。
4. **混淆映射策略未来是否演进到自研 intermediary？** 暂不，等 Mod 数量 > 50 后再评估。
5. ~~开源协议~~ → **已决策：MIT**。

### 7.1 模块懒加载机制（M2 设计时落地）

```
启动时：
  classpath = { mirage-launch, mirage-loader-core, MC 原版 jar, 必备库 }

mods/*.jar 扫描后：
  读 mirage.mod.json → 收集所有 requires: [module-id]
  仅把被 requires 的模块 jar 加入 classpath

运行时：
  MirageClassLoader.findClass() 首次命中某模块包路径时：
    → 检查该模块 jar 是否已加入
    → 未加入则动态 appendURL + 记录"按需加载"日志
```

约束：模块必须在 jar 的 `META-INF/mirage-module.json` 里声明
`id`、`packagePrefix`、`providedApi`，loader 据此做懒加载判定。

---

## 8. 下一步立即执行（M0 收尾 + M1 启动）

按顺序做，每完成一项勾掉：

- [ ] 反编译 `mc-shili/versions/26.2/26.2.jar` 的 `net.minecraft.client.main.Main`，确认 `main` 方法签名与参数解析逻辑
- [ ] 在 `docs/` 下建 `architecture.md`，把第 3 节的图扩成时序图
- [ ] 初始化 Gradle 多模块骨架（空 `build.gradle.kts` + `settings.gradle.kts`）
- [ ] 写 `mirage-launch` 最小入口：打印一行日志就退出
- [ ] 构造 `26.2-mirage.json`，在 PCL 里试启动一次

---

**文档状态：** v0.1 · 初版计划，待 M0 完成后升级到 v0.2（补充反编译得到的 Main 真实签名）。
