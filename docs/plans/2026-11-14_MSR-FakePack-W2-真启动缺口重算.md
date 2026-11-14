# MSR FakePack · W2 真启动缺口重算

> 2026-11-14 · 用户「继续」后承接 W1，做 W0 计划里的 **W2**（纯离线，W0 已定「否（纯离线）」）。
> 定位：用真实启动 classpath 重算缺口集合 M，与 P3 沙盒 M 对账；验证「现有 P4b 生成物对真实启动是否有效」。

## 0. 结论速览

**W2 的核心结论（反直觉但已门禁锁死）：**

1. 真实启动缺口 M_real 与 P3 沙盒缺口 M_sandbox **完全相等**——都是 80 个 `net.neoforged.*` 类。
2. 用真实 MC jar 作 `--ref`/`--shape` 生成的 pack，与仅用 compat 生成的 pack **逐字节一致**（sha256 相同）。
3. 因此：**现有 P4b 生成物对真实启动同样有效，无需为真启动另造一套。**

反向含义：真正的「real-shape」增强（`net.neoforged.*` 的 stub 去 `extends` 真实的 mc/neo 超类，而非 `java/lang/Object`）**被资源阻塞**——本环境没有含 `net.neoforged.*` 类形状的真实 NeoForge universal jar。

## 1. 取证环境与工具链

- 真实游戏 jar（只读复制，未改 `mc-shili/`）：`mc-shili/versions/26.2-NeoForge_26.2.0.90/26.2-NeoForge_26.2.0.90.jar`
  - `unzip -l` 计数：**10372 个 `net/minecraft/*` 类、0 个 `net/neoforged/*` 类**。
  - 含义：在本加载器架构里，neo 类只来自 `mirage-neoforge-compat`（fat jar 里 141 个 stub），真实游戏 jar 只带 mc 类。这与「neo 由 compat 提供、mc 由游戏 jar 提供」的职责划分一致。
- 生成器：`io.mirage.loader.FakeGenCli`（P4b，无需任何改动——`--ref` 已经驱动 `FakeStubGenerator.resolveSuper` 的判定）。
- 6 个真实 mod 素材（`%TEMP%\msr-mods`，幂等拉取，sha512 锁）：Jade / appleskin / cloth-config / ferritecore / jei / mezz_config。

## 2. 重算结果（实跑数据）

`sandbox`（仅 compat）：`miss = (neo=12/1/0/55/0, mc=43/78/14/291/0)`，`generated=80 skipped=611 failed=0`。
`real-startup`（compat + 真实 jar）：`miss = (neo=36/12/1/0/55/0, mc=0/0/0/0/0/0)`，`generated=80 skipped=0 failed=0`。

| 指标 | sandbox | real-startup |
|---|---|---|
| 生成类数 | 80 | 80 |
| 含 `net.minecraft.*` 类 | 0 | 0 |
| `--verify` 各 mod `mc=` | 43/78/14/291/0 | 0/0/0/0/0 |
| pack sha256（全部 .class 拼接） | `6436b019…` | `6436b019…`（**相同**） |

## 3. 为什么 real-startup pack 与 sandbox pack 逐字节一致

`FakeStubGenerator.resolveSuper` 对 mc 超类的处理是「缺失则回落 `Object`、存在则回真实名」。但本实验里：

- 80 个 neo 类里，compat **已经 stub** 的那部分能从 compat 取到真实形状（super 已是 compat 声明的值）；
- compat **没有**的那部分（正是我们要生成的 80 个），`shapeOf` 返回 `null` → `superName` 默认 `java/lang/Object`。
- 真实 jar 含 0 个 `net.neoforged.*`，作 `--shape` 对 neo 形状**零贡献**；作 `--ref` 只让 mc 类从「缺失」变「齐全」，从而 `--verify` 不再报 mc 缺失——但**不改变任何生成字节**（mc 类本就进 `skipped` 不进 `toGenerate`）。

所以加真实 jar 只改善 `--verify` 账目（mc 不再假性缺失），生成物字节不变。**这正是「缺口集合与运行时不相关」的实证。**

## 4. 仍被资源阻塞的 real-shape 增强（后续项，非 W2 范围）

要让 neo stub 真正 `extends` 真实超类（而非 `Object`），生成器需要**真实 NeoForge universal jar**（含 `net.neoforged.*` 类的 `superName`/`interfaces`）作 `--shape`。本环境：

- `mc-shili` 的 `26.2-NeoForge_26.2.0.90.jar` 含 0 个 neo 类（只是 patched MC 客户端）；
- 没有独立的 `neoforge-universal.jar` 带 neo 类。

拿到该 jar 后，W2.5（real-shape 增强）只需在 FakeGenCli 加一个 `--shape <neoforge-universal.jar>`，生成器即可把 neo stub 接到真实超类。但即便那时，仍要重新验证 **no-arg 构造器陷阱**：stub `extends` 真实 mc 类就必须调其真实 `<init>()V`，而很多 mc 类没有无参构造 → `VerifyError`/`IllegalAccessError`。这需在真实启动里验证，属 W3 范畴。

## 5. 不变量（门禁锁死）

1. 真实启动缺口 M == 沙盒缺口 M（=80 个 neo 类），与运行时不相关。
2. pack 内**绝不**含 `net.minecraft.*` 类（mc 一律走真实 jar，生成器不造 mc 空架子）。
3. pack 内类全部落在 `net.neoforged.*`。
4. 生成确定性：同输入两次生成逐字节一致。
5. 加真实 jar 只改善 `--verify` 账目，不引入新字节差异。

## 6. 门禁

`scripts/verify_fakepack_realstartup.ps1` —— **10/10 全绿**（headless，不拉 GUI、不启动游戏、只读 `mc-shili/` 内的真实 jar、其余只写 `%TEMP%`）：

- (1) sandbox M == 80、0 个 mc 类、全 neo；
- (2) 确定性（两次 sha 相同）；
- (3) real-startup M == sandbox M、pack 逐字节一致、每 mod `--verify` 报 `mc=0`；
- (4) 负向自检：mc 扫描能检出 `net.minecraft.*`（否则 0-mc 断言空转）、去掉 `jei`（贡献 55 类）后 sha 必变（否则字节比对空转）。

## 7. 对后续阶段的影响

- **W3（route C 编进 fat jar）**：可用**现有生成流**直接出 pack，无需改 `FakeGenCli`/`FakeStubGenerator`——W2 已证明缺口运行时不相关。W3 真正的难点是「把 pack 审成有实现的 stub 编进 `mirage-neoforge-compat` 源码（N1 路径）」+ 真实启动验证，与 real-shape 解耦。
- **real-shape 增强**：阻塞于真实 neo jar，列为独立后续项，不在 W3 强制范围内。
- **W1 路线 B 实机对账**：仍按 W0 规则需用户明确同意（涉及启动游戏），本次未做。

## 8. 本轮改动

- 新增 `scripts/verify_fakepack_realstartup.ps1`（W2 门禁，10/10）。
- 台账三份同步；经验新增 C97–C98。
- **生产代码零改动**；未构建 fat jar、未部署、未拉 GUI、未动 `mc-shili/`（仅只读复制真实 jar 到 `%TEMP%` 作门禁输入）。
