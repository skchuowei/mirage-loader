# mirage-harddecode (A2)

> MG 项目的静态字节码分析器。输入一个 mod jar，纯静态地用 ASM 解析类结构、方法调用图、MG/Fabric 关键注解，输出符合 [A1 MG-Spec](../docs/agents/A1-Spec/MG-Spec.md) 的 **MG 静态描述 JSON**（`mirageSpecVersion: "0.1"`）。

不运行 mod 字节码，不猜测 MC 内部类名，不解析 lambda 真实目标——那些留给 A3 沙箱。

## 构建

本模块跟随根项目统一 toolchain（Java 21）。项目自带的 Gradle 发行版位于：

```
D:\bing\MC\mcmoderr-my\.gradle-dist\gradle-8.10\bin\gradle.bat
```

构建（在 `mirage-loader/` 根目录下）：

```powershell
$env:JAVA_HOME = "C:\Program Files\Java\jdk-21.0.11"
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
& "D:\bing\MC\mcmoderr-my\.gradle-dist\gradle-8.10\bin\gradle.bat" :modules:mirage-harddecode:build --no-daemon
```

> 项目尚未生成 `gradlew` wrapper。后续可在根目录跑一次
> `gradle wrapper --gradle-version 8.10`，之后改用 `.\gradlew.bat`。

## CLI 用法

方式一（Gradle application 插件）：

```powershell
& "...\gradle.bat" :modules:mirage-harddecode:run `
    --args="<mod.jar> -o <out.json>" --no-daemon
```

方式二（打好的 jar + 手工 classpath）：

```powershell
java -cp "modules\mirage-harddecode\build\libs\mirage-harddecode.jar;<asm.jar>;<asm-commons.jar>;<gson.jar>" `
    io.mirage.harddecode.Main <mod.jar> -o <out.json>
```

参数：
- `<mod.jar>`（必填）：待分析的 mod jar 路径。
- `-o <out.json>`（可选）：输出路径；缺省时 JSON 打到 stdout。

## 输出字段（对齐 MG-Spec §0.1）

| 顶层字段 | 来源 / 章节 | 说明 |
|---|---|---|
| `mirageSpecVersion` | §0.1 / §7 | 固定 `"0.1"`。 |
| `mod` | §0.1 | `id`/`version`/`environment`/`entrypoints`/`accessWideners`。id 优先取 `@Mod("...")`，其次 `mirage.mod.json` / `fabric.mod.json`；version 取 manifest `Implementation-Version` 或 mod json。 |
| `dependencies` | §5 | 从 `depends` 提取；A2 只给 `modId`/`version`。 |
| `transforms` | §1.3 | 扫到 `@Inject` 就登记 `handler`+`injector:"INJECT"`，`confidence:"MEDIUM"`；**不猜目标类/方法**（A3 的事）。 |
| `events` | §2.2 | 扫到 `@SubscribeEvent` 就取唯一参数类型作 `eventType`，`confidence:"HIGH"`。 |
| `registries` | §3 | A2 静态阶段留空数组（注册表注册是运行时行为，A3 捕获）。 |
| `resources` | §4.1 | `namespaces: [<modid>]`。 |
| `classes` | 扩展字段 | 每个类的完整结构：`name`/`superClass`/`interfaces`/`access`/`annotations`/`methods`/`fields`（含各自的注解描述符）。 |
| `methodCalls` | 扩展字段 | 去重后的方法调用边 `{from,to,method,desc}`。 |
| `mixinTargets` | §1.1 | `.mixins.json` 文件路径列表 + `@Mixin` 注解的目标类。 |
| `unresolved` | §0.3 | invokedynamic/lambda 登记，`confidence:"LOW"`、`unresolved:true`。 |
| `_stats` | 内部 | `classCount`/`methodCount`/`methodCallCount`/`unresolvedCount`。 |

每个能静态确定的条目挂 `_src: {origin:"STATIC", confidence:"HIGH"}`；`@Inject` 类挂 `MEDIUM`；invokedynamic 挂 `LOW`。

## 已知限制

- **invokedynamic / lambda 不解析**：只登记 bsm 名称，不反推真实目标（§0.3）。
- **不跑 mod 字节码**：纯静态，不加载类、不初始化。
- **不猜 MC 内部类名**：类名直接用 JVM 内部形式输出。
- **`@Inject` 不猜目标**：不知道被注入的 vanilla 方法是哪个，等 A3 沙箱。
- **fabric.mod.json 只取根目录**；mirage.mod.json 同时识别根目录与 `META-INF/` 两处。
- **不打 fat jar**：本模块只 `build` 通过；ASM/Gson 的 fat jar 打包留到后续。

## 示例

对 `examplemod-1.0.0.jar` 跑一次的输出见 [`sample-output.json`](./sample-output.json)：

```
classes=1 methods=3 calls=16 unresolved=1
mod.id=examplemod  version=1.0.0
```
