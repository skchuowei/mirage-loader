# 第三方组件与许可证声明

> 本文件记录 Mirage Loader 构建与分发时涉及的第三方组件。本仓库自身代码按 `LICENSE`（MIT）授权；
> 第三方组件仍各自受其原许可证约束，本文件不替代各项目随发行版提供的 LICENSE / NOTICE 原文。
> 版本与依赖声明以 `gradle/libs.versions.toml` 及各模块 `build.gradle.kts` 为准。

## 1. 随 fat jar 分发的运行时依赖

部署产物 `mirage-launch-0.1.0.jar` 会把 `runtimeClasspath` 以及
`mc-shili/libraries/io/mirage/mirage-deps/` 下的 jar 一并 shade 进单个 jar
（见 `launcher/mirage-launch/build.gradle.kts` 的 `fatJar` 任务），主要包含：

| 组件 | 版本 | 许可证 | 说明 |
|---|---|---|---|
| ASM (`org.ow2.asm`: asm / asm-commons / asm-tree / asm-analysis / asm-util) | 9.9 | BSD-3-Clause | 字节码读写与分析；Mixin 内部另有 shaded ASM |
| SpongePowered Mixin (`org.spongepowered:mixin`) | 0.8.7 | MIT | 运行时注入框架，版本目录注释标明为临时上游依赖 |
| Gson (`com.google.code.gson:gson`) | 2.11.0（版本目录）；部分模块另以文件路径直接引用 2.14.0 | Apache-2.0 | JSON / 描述符解析；两个版本并存是现状，统一版本待后续处理 |
| Guava (`com.google.guava:guava`) | 33.4.0-jre | Apache-2.0 | 集合与工具 |
| Apache Log4j (`log4j-api` / `log4j-core`) | 2.24.1 | Apache-2.0 | 日志后端接口 |

上述 Apache-2.0 / BSD-3-Clause / MIT 组件均要求或建议在再分发时保留其版权与许可证声明。
当前 `fatJar` 配置里有 `exclude("META-INF/maven/**")`，且 `duplicatesStrategy = EXCLUDE`，
因此**并不保证**每个依赖的 LICENSE / NOTICE 文件都被完整保留在产物内；本文件即为对外的集中声明，
后续若要正式发行，应逐项核对并在产物中附上各依赖的许可证原文。

## 2. 只在编译期使用、不随产物分发

| 组件 | 版本 | 许可证 | 说明 |
|---|---|---|---|
| LWJGL 3（lwjgl / lwjgl-glfw / lwjgl-opengl / lwjgl-stb） | 3.4.1 | BSD-3-Clause | `compileOnly`，来自用户本机 `mc-shili/libraries/org/lwjgl`；运行时使用 Minecraft 自带的同一份库 |

## 3. 参考源码与 clean-room 边界

项目对其它 Mod 加载器生态只做**行为观察与接口枚举**，兼容层为自写实现：

- **Fabric / Mixin 系**：Apache-2.0 / MIT，可作参考（见 `AGENTS.md` 的许可证红线）。
- **NeoForge / Forge**：LGPL 2.1。按 `AGENTS.md` 规则 4「合规边界」的约束，只观察行为、枚举 API 签名，
  以 clean-room 方式实现，**不复制其实现代码**。`refs/neoforge/`、`refs/forge/` 属仓库外的只读对照材料，
  不进 git、不进产物。若将来兼容层引入任何源自 LGPL 项目的代码，须重新评估整体授权口径。

## 4. 状态

当前为 0.4.0 开发版，尚无正式发行版与 release tag。首个正式版发布前，应复查本清单与实际
`fatJar` 内容是否一致（依赖增删、版本变更都要同步更新本文件）。
