# NeoForge 兼容矩阵

> 关联：`docs/plans/2026-11-07_NeoForge大规模测试前收口.md`（N0–N4）、`docs/NeoForge兼容层缺口清单.md`。
> 链接期数据由 `scripts/verify_neo_api_surface.ps1` 生成；行为列在 N1–N4 实机后填写。
> **未实测的格子一律留空，不填推测值。**

## 口径

- 样本只放 26.2 可用构建（Modrinth `versions=26.2` + `loaders=neoforge`），未找到 26.2 构建的不硬下旧版。
- 库存路径：`mods-library/neoforge/<官方文件名>`；测试时复制到 `mc-shili/versions/26.2-mirage/mods/`，测完移回（AGENTS「测试 Mod 获取规则」）。
- 链接期「引用 / 缺失」= jar 内 `.class` 常量池的 `net/neoforged/*` 引用与 Mirage compat 实际提供类的差集。
- 启动耗时/内存只当单次样本，不作为阈值（见 `docs/perf-baseline.md` 顶部口径）。
- 期望日志锚点见文末；**标「待 N2」的锚点当前代码里还没有**，不要拿旧日志当证据。

## N0 基线（3 个真实 mod，2026-11-07）

| mod | 版本 | jar | 大小 | 引用 | 缺失 | 覆盖率 | 门禁角色 | 链接期状态 |
|---|---|---|---|---|---|---|---|---|
| FerriteCore | 9.0.0 | `ferritecore-9.0.0-neoforge.jar` | 70 KB | 5 | 3 | 40% | 门禁 A（Mixin/coremod 型） | 未加载 |
| Cloth Config | 26.2.155 | `cloth-config-26.2.155.jar` | 1109 KB | 7 | 5 | 28.6% | 门禁 A（配置/API 型） | 未加载 |
| JEI | 30.48.0.244 | `jei-26.2-neoforge-30.48.0.244.jar` | 4638 KB | 92 | 81 | 12% | 门禁 B（压力样本） | 未加载 |

**JEI 81 个缺失的包分布**：`neoforge/client/*` 53、`neoforge` 其余 16、`neoforgespi` 6、`fml` 5、`api.distmarker` 1。
**主类构造签名**：JEI = `(IEventBus, Dist)`；Cloth Config / FerriteCore = 无参。`NeoLifecycle` 当前只支持无参与 `(IEventBus)`。

## 门禁样本

| 门禁 | 样本数 | 样本 | 判据 |
|---|---|---|---|
| A（N1+N2 出口） | 3 | FerriteCore、Cloth Config、从候选池按缺失数最少挑 1 个注册/配置型 | 每个都有 `[NeoCompat] ... ctor=ok`，`events`/`regs` 非 0；`NoClassDefFoundError` / `IllegalAccessError` / `MixinApplyFatalException` 为 0（与本次改动相关） |
| B（N3 出口） | 8 | A 的 3 个 + 5 个含客户端事件/配置界面的 mod（含 JEI） | JEI 能完成构造与 `@SubscribeEvent` 注册；客户端事件缺失数为 0 或已明确接受 |
| C（N4 出口） | 20 | 候选池按 6 类取：性能/核心 4、配置 3、内容 4、UI/HUD 4、配方/API 3、其他 2 | 四列（构造/事件/注册/全链路）有数据；兼容率写入本文件 |

## 20 样本候选池（N4 前用扫描脚本按缺失数筛）

Modrinth 上 26.2 + neoforge 的构建共 7769 个，以下仅为首批候选，**不代表已选**：

| 类别 | 候选 |
|---|---|
| 性能/核心 | FerriteCore、Entity Culling、ImmediatelyFast、Lithium |
| 配置/API | Cloth Config、YACL、Architectury API |
| 内容/注册 | VeinMiner、Sodium Extra（注册+客户端混合） |
| UI/HUD | Xaero's Minimap、Xaero's World Map、ETF、EMF |
| 配方/重型 API | JEI |
| 其他 | Iris、Sodium（以客户端渲染为主，放最后） |

## 行为列（待 N1–N4 实机填写）

| mod | 构造 | @SubscribeEvent | 注册（item/tab） | 全链路 | 备注 |
|---|---|---|---|---|---|
| FerriteCore | | | | | 5 个 `[[mixins]]` 当前一个都不加载 |
| Cloth Config | | | | | `META-INF/accesstransformer.cfg` 当前不应用 |
| JEI | | | | | 主类构造签名不匹配；client 事件缺 53 类 |

## 期望日志锚点

| 锚点 | 含义 | 状态 |
|---|---|---|
| `[NeoCompat] mod=<id> ctor=ok` | 主类构造成功（N2 新增） | 待 N2 |
| `[NeoCompat] mixins=<config 数> applied=<n>` | mod 自带 mixin 配置已注册/生效（N2 新增） | 待 N2 |
| `[MODIFIED] <目标类>` | Mixin 真的改写了目标类 | 现有管线（Mixin 日志） |
| `register <类> -> n 个 @SubscribeEvent 方法` | 实例/静态订阅注册 | 现有 `NeoEventBusImpl`（静态扫描待 N2） |
| `DeferredRegister ... 桥接 n 个条目` | 注册项落到 MG DeferredRegister | 现有 |
| 无 `NoClassDefFoundError` / `IllegalAccessError` / `MixinApplyFatalException` | 链接期/织入健康 | 判据 |

## 更新方式

```powershell
# 扫库存里的样本（默认目录 ..\mods-library\neoforge）
powershell -ExecutionPolicy Bypass -File .\scripts\verify_neo_api_surface.ps1

# 指定样本 + 首次生成/有意变更后更新基线
powershell -ExecutionPolicy Bypass -File .\scripts\verify_neo_api_surface.ps1 -ModJars <jar1>,<jar2> -UpdateBaseline

# 日常回归（不更新基线，出现新缺失即退出 1）
powershell -ExecutionPolicy Bypass -File .\scripts\verify_neo_api_surface.ps1 -ModJars <jar1>,<jar2>
```

基线文件 `docs/neo-api-surface-baseline.json` 按 jar 文件名索引；换 mod 版本时文件名变化会被当作新样本（不会误报回归），需重新 `-UpdateBaseline` 并更新本表。
