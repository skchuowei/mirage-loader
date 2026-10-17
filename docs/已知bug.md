# 已知 Bug 与已知限制

> **只放尚未修复的 bug / 已知限制**。修完一条就把它划掉（~~删除线~~），并在 `更新日志.md` 注明"连带修复了 xxx"。
> 不要把"计划中要做的事"写在这里——那是 `PLAN.md` / `docs/plans/` 的事。

## 已知 Bug

| # | 发现日期 | 现象 | 根因 / 位置 | 严重度 | 状态 |
|---|---|---|---|---|---|
| ~~1~~ | ~~2026-10-11~~ | ~~部署含 IR 分支的 fat jar 启动游戏，mdk-example 初始化并 flush DeferredRegister 后，MC 在 `MappedRegistry.freeze()` 抛 `IllegalStateException: Some intrusive holders were not registered: [Reference{null=[unregistered]}]`，游戏致命错误退出~~ | ~~`launcher/mirage-launch` 的 DeferredRegister native backend 只反射写 `BuiltInRegistries.ITEM/BLOCK`，没有把注册结果回填到 MC 的 `Holder.Reference`~~ | ~~高~~ | **已修 `c0a3035`**：native backend 在 `Registry.register()` 后主动 `unregisteredIntrusiveHolders.remove(value)` 精准清理；freeze 前改为打印残留 + 兜底 clear，不再静默吞。需人工双击 bat 实测确认。 |

## 已知限制（设计如此，不是 bug）

- **无 `src/test/` 单元测试**：当前回归依赖 `gradle build` + 启动冒烟。待建。
- **无 Gradle Wrapper**：必须用全局 `gradle` 9.x。待补 `gradlew`。
- **无 `scripts/run_regression.ps1`**：冒烟靠手动跑 `..\启动 26.2-mirage.bat` 看 `..\logs\latest.log`。待补自动化脚本。
- **只支持 MC 26.2**：不向下兼容（决策 D10）。
- **Mojang 官方 ProGuard 映射**：MC 升级时 Mod 需重编译（决策 D2 的代价）。
