# MG 项目现状摸底与规划修订

> 基于对 `mirage-loader/` 实际源码的逐文件审查，2026-10-11

---

## 一、真实进度（不是规划，是代码里实际有的）

### 已跑通的部分

| 模块 | 文件 | 行数 | 状态 |
|---|---|---|---|
| 启动器 | `launch/Launcher.java` | 280 | ✅ MC 26.2 能启动到主菜单、进世界 |
| 加载画面 | `launch/LoadingScreen.java` | 397 | ✅ 自定义加载画面 |
| 类加载器 | `core/MirageClassLoader.java` | 75 | ✅ Mod jar 通过它加载 |
| 字节码转换器 | `core/MirageTransformer.java` | 518 | ✅ **自研 ASM，不是 Mixin** |
| Mod 发现 | `core/ModDiscoverer.java` | 80 | ✅ 扫描 mods/*.jar |
| 依赖排序 | `core/DependencySorter.java` | 58 | ✅ 拓扑排序 |
| Mod 容器 | `core/ModContainer.java` + `ModManifest.java` | 41 | ✅ |
| Fabric 描述符 | `core/FabricModManifest.java` | 41 | ✅ 能解析 fabric.mod.json |
| 事件总线 | `eventbus/EventBus.java` | 40 | ✅ |
| API 注解 | `api/@Mod` `@SubscribeEvent` `@Entrypoint` | 58 | ✅ |
| 事件类 | ModConstruct/Init/PreInit/PostInit/Client/Server | ~80 | ✅ |
| 注册表 | `DeferredRegister` `RegistryKey` `RegistryObject` | 189 | ✅ 代码有，实际是否挂到 MC Registries 待验证 |
| Fabric 入口 | `ModInitializer` `ClientModInitializer` 接口 | 12 | ✅ 接口在 |
| 调试 | `MirageDebug.java` | 381 | ✅ |

### 实测验证

- `mirage-launch-0.4.0.jar`（238KB，2026-10-05 构建）
- mods 目录里有 3 个测试 mod：`examplemod-1.0.0.jar`、`testtab-1.2.0.jar`、`addonmod-1.0.0.jar`
- 崩溃日志显示游戏**真的进了世界**（chunk 加载、UBO resize、存档保存），不是停在主菜单
- Realms 401 报错是离线账号正常现象，不是 bug

### 空目录（建了壳但没代码）

| 目录 | 状态 |
|---|---|
| `core/mirage-mixin/` | **空**——没有 .java 文件，目录树到 `io/mirage/mixin/` 就断了 |
| `modules/mirage-config/` | 空 |
| `modules/mirage-fabric-compat/` | 空 |
| `modules/mirage-kotlin/` | 空 |
| `modules/mirage-network/` | 空 |
| `gradle-plugin/` | 有 build.gradle.kts，无源码 |

---

## 二、原规划与现实的差距

| 原规划说的 | 实际情况 |
|---|---|
| "直接集成 Mixin（MIT），不重写注入框架" | **实际是自研 ASM 转换器**（MirageTransformer 518 行），mirage-mixin 模块是空的 |
| "四层：硬解码→软解码沙箱→对比提取→重放" | **四层完全不存在**。没有硬解码器、没有沙箱记录器、没有 MG 描述格式。当前是直接 classloader + transformer |
| "9 个 agent 并行" | 整个项目约 3000 行 Java，一个人写的。9 个 agent 并行会严重过载 |
| "Fabric API 兼容层高频子集" | 只解析了 fabric.mod.json 和 ModInitializer 接口，没有事件/注册表/网络/资源兼容 |
| "只做 26.1+" | 实际目标是 **26.2**（JDK 25） |
| "MG 描述格式" | 不存在。Mod 直接用 mirage.mod.json / fabric.mod.json |

---

## 三、关键决策点（需要你拍板）

### 决策 1：字节码注入走哪条路？

现状：MirageTransformer 是自研 ASM（518 行），能改字节码但能力有限。

- **选项 A：继续自研 ASM**——轻量、可控，但要自己实现 @Inject/@Redirect/回调，工作量巨大
- **选项 B：接入真正的 Mixin**——把 SpongePowered Mixin shade 进来，复用成熟的注入框架。mirage-mixin 模块本来就是为这个留的空壳
- **建议：B**。原规划也是这么写的，只是没落地。自研 ASM 已经证明能启动游戏，但做 Mod 生态需要 Mixin 的注入能力，重造轮子不现实。

### 决策 2：MG 描述格式/沙箱记录器还要不要？

原规划的"四层实现"是一个很宏大的研究方向，但当前项目是一个**能跑的加载器**。

- **选项 A：砍掉四层规划**——就做一个直接的独立加载器，Mod 用 Java 写，通过 MG API + Mixin 注入，不搞自动转译
- **选项 B：保留四层作为远期研究**——当前先把加载器做扎实，沙箱/硬解码作为 v2 方向
- **建议：B**。当前先把"能加载 Fabric mod"这个核心目标做通，四层是以后的事。

### 决策 3：9-agent 还要不要？

项目当前 ~3000 行代码，一个人+一个 AI 就能推进。9 个 agent 并行是给 10 万行级别的项目用的。

- **建议：缩到 3 个角色**
  - **开发**：写代码、修 bug、加功能（我直接做）
  - **验证**：构建、启动游戏、看日志、跑 mod
  - **你**：拍板决策、定优先级

---

## 四、修订后的现实路线图

### 阶段 1：把现有东西做扎实（当前 → 1-2 周）

1. **接入真正的 Mixin**
   - 把 SpongePowered Mixin 加入 mirage-mixin 模块
   - 让 MirageTransformer 委托给 Mixin 的 transformer
   - 验证：写一个 @Inject 把 MC 日志改成 `[MG] patched!`
2. **验证注册表真的能用**
   - DeferredRegister 代码写了但不知道有没有挂到 MC 26.2 的 Registries
   - 测试：examplemod 注册一个物品，进游戏能拿到
3. **修好已知崩溃**
   - 进世界后 Realms/认证报错是离线模式正常，但要确认没有其他崩溃

### 阶段 2：Fabric 兼容闭环（2-4 周）

1. 让 1 个真实的小型 Fabric mod（非自己写的 examplemod）跑起来
2. 补最基础的 Fabric API：`fabric-api-lifecycle-v1`（就是 ModInitializer 调用时机）
3. 统计：选 10 个小型 Fabric mod，看几个能跑

### 阶段 3：补模块（按需）

- mirage-config：配置文件
- mirage-network：网络包
- mirage-fabric-compat：逐步加 Fabric API

### 远期（v2，不阻塞当前）

- 硬解码器（ASM 静态分析）
- 沙箱记录器（多 JVM Java Agent）
- MG 描述格式自动转译

---

## 五、命名统一

- 代码里包名：`io.mirage.*`
- 文档里简称：MG
- 建议：**不改名**。代码继续用 io.mirage，文档里 MG 就是 Mirage 的缩写。包名改动工作量大且没必要。

---

## 六、一句话

**现状比规划好**——游戏真的能启动、能进世界、能加载 mod。但原规划的"四层/9 agent/沙箱"是空中楼阁，当前实际是一个 3000 行的自研 classloader + ASM transformer。下一步最该做的不是加 agent，而是**接入真正的 Mixin + 验证注册表 + 跑通一个真实 Fabric mod**。
