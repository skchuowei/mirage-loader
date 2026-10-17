# MG 运行时接通改动记录（runtime-wiring）

> 日期：2026-10-11
> 目标：把"定义了但没接通"的三块运行时空壳真正接通——事件生命周期触发、Fabric entrypoints 调用、DeferredRegister 挂到 MC 注册表。

---

## 一、改动文件清单（绝对路径）

| 文件 | 改动内容 |
|---|---|
| `D:\bing\MC\mcmoderr-my\mirage-loader\core\mirage-loader\src\main\java\io\mirage\loader\FabricModManifest.java` | `environment` 从 Gson 枚举改为 `String`（fabric.mod.json 里是小写 `"client"`，Gson 按名匹配枚举会炸）；`entrypoints` 改为 `Map<String, List<JsonElement>>`；新增 `entrypointClasses(side)`，同时兼容纯类名字符串和 `{"value":"..."}` 对象两种 entrypoint 写法。 |
| `D:\bing\MC\mcmoderr-my\mirage-loader\core\mirage-loader\src\main\java\io\mirage\loader\ModContainer.java` | 新增 `fabricEntrypoints` 字段（side -> 类名列表），保留旧构造函数兼容。 |
| `D:\bing\MC\mcmoderr-my\mirage-loader\core\mirage-loader\src\main\java\io\mirage\loader\ModDiscoverer.java` | ① mirage.mod.json 先读 JsonObject 再映射 POJO，补上 `"entrypoint"` 别名（MDK 模板写的是 `entrypoint`，POJO 字段是 `mainClass`，之前 Gson 映射不上导致 mdk 主类被跳过）；② fabric mod 把规范化后的 main/client entrypoints 存进 ModContainer。 |
| `D:\bing\MC\mcmoderr-my\mirage-loader\api\mirage-api\src\main\java\io\mirage\api\registry\DeferredRegister.java` | 新增 `nativeBackend`（BiConsumer<DeferredRegister, entries>）和 `setNativeBackend(...)`；新增公开 `setHookListener(...)`（原来 hookListener 是包私有，core 模块够不着）；`flush()` 优先走 native backend。 |
| `D:\bing\MC\mcmoderr-my\mirage-loader\launcher\mirage-launch\src\main\java\io\mirage\launch\Launcher.java` | 见下"二"。 |
| `D:\bing\MC\mcmoderr-my\mirage-loader\mdk\mdk-example\src\main\java\com\example\mdk\MdkExampleMod.java` | 新增 DeferredRegister 演示物品 `miracle_dust`，并在 `onConstruct` 里 `ITEMS.register()` hook。 |
| `D:\bing\MC\mcmoderr-my\mirage-loader\mdk\mdk-example\src\main\resources\META-INF\mirage.mod.json` | 从 `resources/` 根移到 `resources/META-INF/`（ModDiscoverer 读的是 `META-INF/mirage.mod.json`，原来放在 jar 根会被当成"无描述符"跳过）。 |
| `D:\bing\MC\mcmoderr-my\mirage-loader\core\mirage-loader\build.gradle.kts` | 补 `implementation(libs.asm.core/commons)`（MirageTransformer 在这个模块，但 ASM 依赖原来只声明在 mirage-mixin，属于缺失声明，不是新增依赖）。 |
| `D:\bing\MC\mcmoderr-my\mirage-loader\mdk\mdk-example\build.gradle.kts` | mdk 模块 toolchain 提到 Java 25（MC jar 是 v69 class），files() 挂本机 MC jar + DFU + gson（与 build.bat 的 -cp 对齐，非新增第三方仓库）。 |
| `D:\bing\MC\mcmoderr-my\mirage-loader\modules\mirage-harddecode\src\main\java\io\mirage\harddecode\MetaReader.java` | 补一行 `import java.io.ByteArrayOutputStream;`（别的 agent 的 WIP 模块，一行编译修复，未动逻辑）。 |

---

## 二、Launcher.java 具体改了什么

1. **端侧过滤**：客户端运行时跳过 `side=server` 的 mod。
2. **main 侧入口列表**：fabric mod 取 `entrypoints.main`；mirage 原生 mod 回退到 `mainClass`。
3. **实例化后**：`eventBus.register(instance)` → post `ModConstructEvent` → post `ModPreInitEvent` → 调 `callOnInitialize(instance)` → post `ModInitEvent`。
   - `callOnInitialize`：先 `instance instanceof net.fabricmc.api.ModInitializer` 强转调 `onInitialize()`；不是则反射找 `onInitialize()` 方法（兼容现有 examplemod/testtab/addonmod——它们有该方法但不实现接口）。
4. **client 侧入口**：当前 dist=CLIENT 时，遍历 `entrypoints.client`，实例化后 `instanceof ClientModInitializer` → `onInitializeClient()`，否则反射兜底。
5. **DeferredRegister 闭环**：unfreeze 注册表后安装 native backend（按 `type().getSimpleName()` 映射 `item`→BuiltInRegistries.ITEM、`block`→BLOCK，反射调 `Registry.register(reg, Identifier.fromNamespaceAndPath(modId,path), value)`）；收集所有 hook 的 DeferredRegister，mod 初始化完后统一 `flush()`（仍在 unfreeze 窗口内，re-freeze 之前）。
6. **ClientStartingEvent**：在反射调用 `net.minecraft.client.main.Main.main` 之前 `eventBus.post(new ClientStartingEvent())`。

---

## 三、三块任务完成状态

### 任务 1：事件生命周期真正触发 —— ✅ 接通
- 现状摸底：Launcher 原本就 post ModConstruct/PreInit/Init/PostInit，但有两个断点让 mdk 的 `@SubscribeEvent` 永远不被调到：
  1. mdk 的 mirage.mod.json 用 `entrypoint` 键，POJO 字段是 `mainClass`，Gson 映射不上 → mainClass=null → 主类被跳过；
  2. mdk 的描述符放在 jar 根而不是 `META-INF/` → ModDiscoverer 直接跳过整个 jar。
- 修复：两处都改了（见上）。现在 mdk jar 放进 mods 目录后，启动时会：实例化 MdkExampleMod → post ModConstructEvent → 触发 `onConstruct` → 打印 `[MdkExample] 模组构造中...` / `已注册方块` / `已注册物品` / `DeferredRegister 已 hook` / `模组加载完成!`。
- ClientStartingEvent 已在 MC main 反射调用前补发。

### 任务 2：Fabric entrypoints 真正调用 —— ✅ 接通（代码路径就绪）
- 现状：`FabricModManifest` 能解析 fabric.mod.json，但 `environment` 是枚举（真实 fabric.mod.json 是小写字符串，Gson 会炸）；`toMirageManifest()` 只取 `main[0]` 当 mainClass，`client` 侧 entrypoints 完全丢弃；Launcher 无条件反射 `onInitialize()`，没有 `instanceof ModInitializer/ClientModInitializer` 分派。
- 修复：environment 改 String；entrypoints 规范化后存 ModContainer；Launcher 按 main/client 两侧分别实例化、用接口强转调用。
- 本地 mods 目录里没有真正的 fabric mod（3 个测试 mod 都是 mirage.mod.json），所以这条路径未经真实 fabric mod 运行验证；但字符串写法和 `{"value":...}` 对象写法都已处理。

### 任务 3：DeferredRegister 接通 MC 注册表 —— 🟡 最小闭环完成（item/block）
- 现状：DeferredRegister 有完整 API（create/register/flush/setBackend），但 `hookListener` 是包私有，core 模块够不着；也没人 `setBackend` / 调 `flush()`，等于空壳。
- 修复：暴露 `setHookListener` + `setNativeBackend`；Launcher 在 unfreeze 窗口内安装后端，flush 时按证据充分的签名（从测试 mod 字节码里看到的 `Registry.register(Registry, Identifier, Object)` 和 `Identifier.fromNamespaceAndPath`）反射写 BuiltInRegistries.ITEM/BLOCK。
- mdk 演示 mod 新增 `miracle_dust` 物品走这条闭环。

---

## 四、构建结果

命令（本机无 gradle wrapper，用腾讯镜像下的 gradle 8.10.2）：

```
gradle build -I C:\Users\chuowei\gradle-dist\init.gradle --console=plain --no-daemon
# 需要 $env:GRADLE_OPTS="-Dfile.encoding=UTF-8"（项目 .kts 含中文注释，本机默认 GBK 会解析炸）
```

最终结果：**BUILD SUCCESSFUL**（29 actionable tasks: 29 executed）。关键 tail：

```
> Task :modules:mirage-network:build

BUILD SUCCESSFUL in 16s
29 actionable tasks: 29 executed
```

全模块 compileJava 通过（api / eventbus / loader core / launcher / mdk / harddecode 等）。

### 构建过程中修掉的前置问题（非本任务范围但不修就红）
1. `:core:mirage-loader` 缺 ASM 依赖声明 → 补 libs.asm.core/commons。
2. `:mdk:mdk-example` 无 MC classpath 且 toolchain 是 21（MC jar 是 v69）→ toolchain 25 + files() 挂 MC jar/DFU/gson。
3. `:modules:mirage-harddecode` MetaReader 缺 `ByteArrayOutputStream` import → 补一行。

---

## 五、部署动作（让下次启动游戏能看到效果）

- 重新打包 fat jar：`D:\bing\MC\mcmoderr-my\mirage-loader\build\mirage-launch-0.4.0.jar`（旧版备份为 `.bak`），已校验内含新 Launcher.class（含 `ClientStartingEvent` / `DeferredRegister` / `onInitializeClient` 引用）。
- mdk mod jar 已装到 `D:\bing\MC\mcmoderr-my\mc-shili\versions\26.2-mirage\mods\mdk-example-1.0.0.jar`。
- 下次用 PCL 启动 26.2-mirage 版本时，启动日志应出现 `[MdkExample] 模组构造中...` 等输出。

---

## 六、留下的 TODO

1. **ClientStartedEvent 未补发**：时机是 TitleScreen 渲染完成后，需要一个 TitleScreen 钩子；transformer 织入的 `MirageDebug.loaded=true` 拿不到 EventBus 实例，待把 bus 做成静态可访问再补发。（已在 Launcher.java 里留 TODO 注释。）
2. **DeferredRegister 后端只映射了 item/block**：`creativemodetab` / `enchantment` / `entity_type` 等 BuiltInRegistries 字段名在没有确切证据前不瞎猜，留 TODO（Launcher.java 内）。
3. **flush() 兜底路径会重复调 supplier()**：backend 未回填 holder 时用 supplier 结果兜底，同一对象可能实例化两次；小问题，未处理。
4. **真实 fabric mod 未本地验证**：mods 目录里没有 fabric.mod.json 的 mod，object-form entrypoints 分支只做了代码兼容，没跑过。
5. **根 build.gradle.kts 中文注释**：本机 GBK 默认下跑 gradle 需要 `-Dfile.encoding=UTF-8`，未改项目文件（避免动别人的配置）。

---

# IR v0.1 适配（追加，2026-10-11）

> 依据：`docs/agents/A1-Spec/MG-IR隔离规范.md`（只读参考，未改动）+ `modules/mirage-harddecode/sample-output.json`（A2 实际产出）。

## 改动文件清单

| 文件 | 改动内容 |
|---|---|
| `D:\bing\MC\mcmoderr-my\mirage-loader\core\mirage-loader\src\main\java\io\mirage\loader\IrDescriptorParser.java` | **新增**。IR v0.1 宽松解析器：只显式读 §1.1 核心字段（mirageSpecVersion / mod{id,version,group,environment,entrypoints{main,client,server},accessWideners} / dependencies / transforms / events / registries / resources.namespaces）；未知字段天然丢弃，不做白名单反序列化报错；unresolved 非空时打一行 WARN（不 FATAL）；诊断字段（classes/methodCalls/mixinTargets/_stats，以及将来迁到 `_diagnostics` 子对象下的形态）不读、不校验。 |
| `D:\bing\MC\mcmoderr-my\mirage-loader\core\mirage-loader\src\main\java\io\mirage\loader\ModDiscoverer.java` | 新增 IR 分支：查找顺序改为 ① `mod.mirage.json`（jar 根，回退 `META-INF/mod.mirage.json`）→ ② legacy `META-INF/mirage.mod.json` → ③ `fabric.mod.json`；IR 无效时自动回退 legacy 检查；`mapIrEnvironment` 把 BOTH/CLIENT/SERVER 映射成 ModManifest.side；IR entrypoints.main[0] 填入 mainClass（Launcher 的 ModPostInitEvent 循环和显示逻辑直接复用）；fabric 分支补 server 空表。 |
| `D:\bing\MC\mcmoderr-my\mirage-loader\core\mirage-loader\src\main\java\io\mirage\loader\ModContainer.java` | 新增 `ir` 字段（IrResult，legacy 来源为 null）；`fabricEntrypoints` 语义扩展为"side→类名列表"统一分派表（IR 和 fabric 都填它，Launcher 现有 instanceof ModInitializer/ClientModInitializer 分派零改动对接）；toString 标注 `[IR x.y]` / `[legacy]`。 |
| `D:\bing\MC\mcmoderr-my\mirage-loader\mdk\mdk-example\src\main\resources\mod.mirage.json` | **新增**。mdk 最小可用 IR 描述符（jar 根），entrypoints.main = com.example.mdk.MdkExampleMod；legacy `META-INF/mirage.mod.json` 保留作 fallback，未删除。 |

## ModDiscoverer 新查找顺序

```
每个 mod jar：
  1. mod.mirage.json (root)  ──找不到──>  META-INF/mod.mirage.json
       命中且有效 → discovered (IR x.y)
       命中但无效 → 打印提示，自动落到第 2 步
  2. META-INF/mirage.mod.json        （legacy 原生，保留）
  3. fabric.mod.json                 （fabric 兼容，保留）
  都没有 → "has no mod descriptor, skipping"
```

## 诊断字段忽略策略

- 解析器只显式 `parent.get(key)` 认识的 key，未知 key（含顶层 `classes`/`methodCalls`/`mixinTargets`/`_stats`，以及将来迁到 `_diagnostics` 子对象的形态）天然不进入 IrResult，不抛异常、不计数、不校验。
- `unresolved` 条目单独处理：非空时打一行 `[Mirage] ! IR unresolved N 条（沙箱待补，不阻断）`，继续加载。
- IR 核心字段已接住但暂未消费：`dependencies`/`transforms`/`events`/`registries`/`resources.namespaces` 挂在 ModContainer.ir 上，后续 A5/A3/后端注册阶段取用；DeferredRegister 的 native backend（上一轮反射写 BuiltInRegistries.ITEM/BLOCK）原样保留。

## Gradle 9.1.0 + JDK 25 切换过程

- 命令：`$env:JAVA_HOME="C:\Program Files\Java\jdk-25.0.4"` + `D:\bing\MC\mcmoderr-my\.gradle-dist\gradle-9.1.0\bin\gradle.bat build -I <init.gradle> --console=plain --no-daemon`，仍需 `-Dfile.encoding=UTF-8`。
- **结果：BUILD SUCCESSFUL in 16s（29 tasks，28 executed + 1 up-to-date），零 breakage**。
- 遇到的问题：无编译错误；仅有一条 `Deprecated Gradle features were used, incompatible with Gradle 10` 警告（输出给了 problems-report.html 路径），属 Gradle 9→10 的前瞻性提醒，不影响 9.1.0 build，未就地改 .kts（改 deprecation 超出本次范围）。
- 根 build.gradle.kts 的 toolchain 21 自动探测到本机 JDK 21（C:\Program Files\Java\jdk-21.0.11），mdk 模块上一轮配的 toolchain 25 在 gradle 9 下同样工作。

## 部署动作

- fat jar `build\mirage-launch-0.4.0.jar` 已用新模块类重打包（301991 字节，校验内含 `io/mirage/loader/IrDescriptorParser.class`）。
- mdk mod jar 已装到 `mc-shili\versions\26.2-mirage\mods\mdk-example-1.0.0.jar`（jar 内同时含 `mod.mirage.json` 根描述符 + `META-INF/mirage.mod.json` legacy 描述符；运行时会优先走 IR 路径，日志行应为 `+ discovered (IR 0.1) mdk-example v1.0.0 ...`）。
