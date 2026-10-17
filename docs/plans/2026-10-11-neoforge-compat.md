# 2026-10-11 · 新建 modules/mirage-neoforge-compat（NeoForge 静态兼容层）

## 目标
新建 `modules/mirage-neoforge-compat/`（包名 `io.mirage.neoforge`），输入一个 NeoForge mod jar，纯静态解析 `META-INF/neoforge.mods.toml` + 扫描 `@Mod` / `@SubscribeEvent` / `DeferredRegister` 字节码，输出符合 MG IR v0.1 契约的 `mod.mirage.json`。模块本身编译通过、能在最小 fixture jar 上跑出正确 IR。本轮**不**做运行时接通。

## 现状分析
- A2 硬解码器（mirage-harddecode）已完成并被用户验收（"硬解码已经可以了"），已在上一 commit 入库。
- IR v0.1 字段冻结：mod / dependencies / transforms / events / registries / resources + `_diagnostics`。
- 运行时 ModDiscoverer 当前查找顺序：`mod.mirage.json` → legacy `META-INF/mirage.mod.json` → `fabric.mod.json`；没有任何 NeoForge 格式识别。
- refs/neoforge 是 NeoForge 26.3 全量源码（LGPL），只能 clean-room 对照格式，不抄代码。
- mc-shili mods/ 下现有 4 个 mod（examplemod / testtab / addonmod / mdk-example），回归冒烟必须全部仍被发现加载。

## 改动文件清单
| 文件 | 改什么 |
|---|---|
| `modules/mirage-neoforge-compat/build.gradle.kts` | 新建。照抄 harddecode：java + application 插件，deps asm/commons/gson，mainClass=io.mirage.neoforge.Main |
| `modules/mirage-neoforge-compat/src/main/java/io/mirage/neoforge/*.java` | 新建。ModsTomlParser / ModsTomlModel / NeoModAnalyzer / IrWriter / Main |
| `modules/mirage-neoforge-compat/sample/` | 新建 fixture：stub 注解源码 + 编译后的 exampleneomod-1.0.0.jar + output.json |
| `modules/mirage-neoforge-compat/README.md` | 新建。CLI 用法、IR 映射表、TODO |
| `settings.gradle.kts` | 加 `include(":modules:mirage-neoforge-compat")` 与 projectDir 两行 |

## 每处改动要点
- ModsTomlParser：手写行级 TOML 子集解析器（顶层 key=value、`[[mods]]`、`[[dependencies.<id>]]`、`[[mixins]]`、`[[accessTransformers]]`、# 注释、占位符 `${version}` 兜底 1.0.0）。
- NeoModAnalyzer：ASM ClassReader 遍历每个 .class，按字符串描述符匹配 `Lnet/neoforged/fml/common/Mod;`、`Lnet/neoforged/bus/api/SubscribeEvent;`、`Lnet/neoforged/neoforge/registries/DeferredRegister;`/`RegistryObject;`。不加载类，不需要 NeoForge classpath。
- IrWriter：按 IR v0.1 形状输出（见 A1 规范 §1.1）。events 带 `_src`，registries confidence=LOW（DeferredRegister 的 RegistryKey 未静态解析）。
- FMLJavaModLoadingContext：本轮只在 README 标 TODO，不写运行时桥。

## 回归测试影响面
- 新模块是独立 application 模块，不被任何其他模块依赖；编译对现有 29 个 task 零侵入。
- settings.gradle.kts 只加两行 include，不改现有 projectDir 映射。
- 冒烟：启动 `..\启动 26.2-mirage.bat`，控制台应仍看到 4 个现有 mod 的 discovered 行与生命周期日志。

## 验证步骤
1. `$env:JAVA_HOME="C:\Program Files\Java\jdk-25.0.4"; $env:GRADLE_OPTS="-Dfile.encoding=UTF-8"; & "D:\bing\MC\mcmoderr-my\.gradle-dist\gradle-9.1.0\bin\gradle.bat" :modules:mirage-neoforge-compat:build --console=plain --no-daemon` 退出码 0。
2. 跑 Main 分析 sample\exampleneomod-1.0.0.jar，检查 output.json：mod.id=exampleneomod、dependencies 含 neoforge、events 含 NeoModMain、registries 含静态字段。
3. 虚拟桌面双击启动 bat，等游戏进主菜单，截图控制台确认 4 个旧 mod 全部 discovered、无异常栈。

## 回滚方案
- git revert 本模块首个 commit + settings.gradle.kts 两行 include；harddecode / 运行时链路不受影响（独立模块）。
