# Mirage Loader 架构时序

## 启动流程（M1 当前阶段）

```
PCL/官方启动器
   │  java -Xmx2G -cp <原版jar+库jar> io.mirage.launch.Launcher --gameDir ... --assetsDir ...
   ▼
[io.mirage.launch.Launcher.main]
   │  1. 打印 banner：[Mirage] Hello from Mirage Launch 0.1.0
   │  2. Class.forName("net.minecraft.client.main.Main")
   │  3. method.invoke(null, (Object) args)
   ▼
[net.minecraft.client.main.Main.main]
   │  1. SharedConstants.tryDetectVersion()
   │  2. joptsimple 解析 args（gameDir/assetsDir/quickPlay...）
   │  3. new GameConfig(...).run()
   ▼
Minecraft 主菜单
```

## 目标流程（M2 之后）

```
Launcher.main
   │
   ├──► 1. 定位 gameDir/mods/
   ├──► 2. 扫描 mods/*.jar，读 mirage.mod.json
   │       └─ 收集 requires:[module-id] → 仅加载被引用的模块 jar
   ├──► 3. 依赖拓扑排序（环检测 + 缺失报错）
   ├──► 4. new MirageClassLoader(原版jar, modJars, requiredModuleJars)
   ├──► 5. 在 MirageClassLoader 上挂 MixinTransformer
   │       └─ 扫描每个 Mod jar 的 *.mixins.json
   ├──► 6. 用 MirageClassLoader 加载 net.minecraft.client.main.Main
   └──► 7. main.invoke(null, args)
           │
           ▼
       MC 类被加载时 → MixinTransformer 命中 → 织入 Mod 字节码
           │
           ▼
       Mod 主类收到 ModConstructEvent → 注册事件回调 → 进游戏
```

## 类加载层级

```
AppClassLoader
   └─ MirageClassLoader  (parent = AppClassLoader)
        ├─ MC 原版 jar        （Mojang 官方映射）
        ├─ Mod jars            （mods/*.jar，每个 Mod 一个 URL）
        ├─ 核心模块 jar        （mirage-loader-core, mirage-eventbus, ...）
        └─ 按需模块 jar        （首次被 Mod 引用时 appendURL）
```

## 懒加载判定

> **【错误/已过时】** 以下是 M2 时期的设计草案，**与实际实现不符**，
> 保留原文供对照。错在两处：
> ① 描述符字段名不是 `packagePrefix` / `providedApi`，实际是
>    `provides` / `requires` / `entrypoint`；
> ② 加载时机不是「`findClass` 首次触发时按需 appendURL」，实际是
>    **S3 阶段按 mod 声明的 `requires` 预先判定并一次性加载**——
>    理由见下方「模块懒加载（G9）实际时序」。
>
> 模块 jar 内含 `META-INF/mirage-module.json`：
> ```json
> {
>   "id": "mirage-config",
>   "packagePrefix": "io.mirage.config.",
>   "providedApi": ["io.mirage.config.ConfigBuilder"]
> }
> ```
> 当 `MirageClassLoader.findClass("io.mirage.config.ConfigBuilder")` 首次被触发：
>   - 检查该模块 jar 是否已加入
>   - 未加入 → appendURL → 打日志 `[Mirage] on-demand loaded module: mirage-config`
>   - 加入后正常加载类

## 模块懒加载（G9）实际时序

> 本节是 2026-11-01 落地后的**实际实现**，上一节的草案作废。

### 描述符格式

模块 jar 内含 `META-INF/mirage-module.json`：

```json
{
  "id": "mirage-config",
  "version": "0.1.0",
  "provides": ["dev.mirage.config"],
  "requires": [],
  "entrypoint": "dev.mirage.config.ConfigModule"
}
```

| 字段 | 含义 | 约束 |
|---|---|---|
| `id` | 模块唯一 id | 正则 `[a-z][a-z0-9-]{1,63}`（**至少 2 个字符**） |
| `version` | 语义化版本 | 非空 |
| `provides` | 本模块提供的公共 API 包前缀 | **不得以 `io.mirage.` 开头**（见下方「包名约定」） |
| `requires` | 本模块依赖的其他模块 id | 参与依赖闭包 |
| `entrypoint` | 初始化入口类全限定名 | 可空；被调用时依次尝试 `static init()` → `instance init()` → `static setup()` |

mod 侧在 `META-INF/mirage.mod.json` 里用 `requires` 声明需要哪些模块
（与既有 `depends`（mod 依赖，modId→版本范围）语义区分）。

### 时序

```
S3 TransformStage
   │
   ├─ 1. 建 MirageClassLoader（classpath 全量 URL + 每个 mod jar）
   ├─ 2. mcl.addJar(每个 mod jar)
   │
   └─ 3. ModuleRegistry.load(ctx.gameDir(), mcl, mods)
          │
          ├─ 3a. 定位模块仓库：系统属性 mirage.modules.dir 优先，
          │         否则 <gameDir>/modules/。目录不存在 → 静默跳过（不是错误）
          ├─ 3b. 扫仓库 *.jar → 读 META-INF/mirage-module.json
          │         ├─ 没有 → 不是模块，忽略
          │         ├─ 非法 → WARN 跳过该 jar
          │         └─ id 重复 → WARN，后一个跳过
          ├─ 3c. 需求集合 = 所有 mod 的 manifest.requires 并集
          │         （ModuleGraph.collectRequests）
          ├─ 3d. 依赖闭包 = 需求 + 各需求自身的 requires 递归展开
          │         （ModuleGraph.collectClosure，visited 集合防环）
          ├─ 3e. 缺失模块（声明了但仓库里没有）→ WARN，继续启动
          ├─ 3f. 对闭包内每个模块：mcl.addJar(jar)
          │         → 日志 `[LS] 模块已加载: <id> v<ver> (jar=..., 需求来源: <mod>)`
          │         → 反射执行 entrypoint
          ├─ 3g. 仓库里但闭包外的模块 → 日志 `[LS] 模块跳过: <id> v<ver> (无 mod 声明 requires)`
          └─ 3h. 结果放进 ctx.properties()["S3.modules"]
```

### 为什么不在 `findClass` 时才加载

草案的「首次 findClass 时 appendURL」在真实实现里有两个硬伤：

1. **`findClass` 里做加锁/IO** —— 类加载是热路径，且加载器可能正在被别的线程用；
   在里面 `addURL` 会与并发的类查找打架。
2. **找不到就报错太晚** —— mod 若在**静态初始化**里引用模块类，第一次 `findClass`
   就必须能命中；等到「触发点」再加载会递归触发加载，栈上很难看。

本实现改成**声明驱动 + 阶段预判**：S3 时 mod 清单已全部读入（`requires` 已知），
此时一次性把需要的模块 jar 加进 mcl，之后所有 `findClass` 都是普通命中。
「不声明就不加载」这个语义仍然成立，而且可观测（跳过会打日志）。

### 包名约定（硬约束）

`MirageClassLoader.loadClass` 把整个 `io.mirage.` 前缀委派给 `SystemClassLoader`。
模块 jar 只加在 mcl 上、不在 app loader 的 classpath 里，所以模块的公共 API
**不能**放在 `io.mirage.` 下，否则委派后必然 `NoClassDefFoundError`。
约定用 `dev.mirage.<模块>` 这类独立前缀，并由 `verify_module_loading.ps1` 源码守卫盯着。

模块**用** Mirage 自有能力（`io.mirage.api.*`）没有问题——那些类随 fat jar 走
app loader，模块由 mcl 加载后引用它们时委派给 app loader，拿到的是同一份。

