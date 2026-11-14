# MSR FakePack 接入真启动类加载器：接线设计与影响面分析

> 2026-11-13 · 用户选「pack 接真启动」后的**设计轮**。
> **本轮零代码改动、未构建 fat jar、未部署、未启动游戏、未碰 `mc-shili/`。**
> 定位：把「接线到底该接在哪、能带来什么、会踩什么」钉死，供下一轮按方案实现；不代替实现。

> **【规则修订附注 · 2026-11-13，只标不删】** 用户裁定：`要不要启动游戏的根本因素是是否拉起 GUI`
> —— 拉 GUI 会影响用户 ⇒ 优先不启动、等用户命令；**不会拉 GUI 的 headless JVM 可以启动**。
> `AGENTS.md` 第 2 节已据此改写。因此本文下面这些原文表述按新判据**收窄**（原文保留备查）：
> §3 路线 B 标题「需用户明确裁决后才可开」、§3「要开需用户单独拍板」、
> §3「按虚拟桌面启动规则…另写 `.bat` 交用户双击」、取舍表「B 需用户同意」、
> §5「本轮及实现轮都不自行启动游戏」、§6「W3 是（B 涉及启动游戏）」。
> **新口径**：B 的**代码实现 + headless 门禁**（纯 JVM 探针、不创建窗口、不写 `mc-shili/`）
> 可自行执行，W1 已按此落地；只有「完整 MC 客户端拉 GUI 的实机对账」仍需用户明确命令。
> **不变的技术结论**：§0/§2 的形状陷阱、UOE 骨架＝假覆盖、三道接线边界、
> B 只能用于诊断录制 —— 规则改的是「谁能拉起进程」，不是「pack 能不能原样接」。
> W1 实现见 `2026-11-13_MSR-FakePack-W1-A+B实现.md`。

---

## 0. 一句话结论

**不建议把当前 P4b 的 fake pack 原样接进真启动的 `defineClass` 路径。**

它是在「沙盒里没有 MC 类」的前提下造出来的**诊断用骨架**（方法体一律抛 `UnsupportedOperationException`，
缺失的 `net.minecraft.*` 超类型回落 `java/lang/Object`）。真启动的 classpath 里有 `26.2.jar`
（10372 个真 `net.minecraft.*` 类），这些超类真实存在 ⇒ 骨架的继承关系与真实类层次不符。
接进去不会让 mod 跑起来，只会把「启动期快速 `NoClassDefFoundError`」换成「运行期更晚、
更难归因的 `UnsupportedOperationException` / `ClassCastException` / `VerifyError`」——
正是 C84 边界铁律里点名的「**假覆盖**」。

真正能落地的「接线」应重新定义为：**让真启动把「自己实际缺哪些 neo 类」说出来，
作为生成器的输入；生成物经 N1 正规路径审成有实现的 stub 再回灌运行时。**
下面 §3 给三条路线，§5 给不变量与门禁断言清单。

---

## 1. 现状取证（全部离线，代码/产物级）

### 1.1 真启动的类加载链路

| 位置 | 事实 |
|---|---|
| `lifecycle-starter/.../TransformStage.java:49-57` | MCL 构造点：`urls` 来自 `System.getProperty("java.class.path")`，parent 是 `ClassLoader.getSystemClassLoader().getParent()`（**platform loader**）。随后 `mcl.addJar(mod.jarFile)` 把每个 mod jar 挂进去。 |
| `core/.../MirageClassLoader.java:54-59` | `io.mirage.*` 除 `neoforge`/`fabric`/`mgscript` 三个例外一律委派 **app loader** ⇒ `io.mirage.loader.sandbox.*`（含 `FakePackProvider`）由 app loader 加载；`MirageClassLoader` 自己也在 app loader ⇒ **在 MCL 里直接调用 `FakePackProvider` 不存在跨 loader 障碍**。 |
| `MirageClassLoader.java:70-77` | `org.lwjgl.*` 走 app loader；`java.*`/`javax.*`/`jdk.*` 走 parent。 |
| `MirageClassLoader.java:78-94` | `net.minecraft.*` / `net.neoforged.*` / `io.mirage.neoforge.*` 三段前缀**不委派**，在 `synchronized (getClassLoadingLock(name))` 里 `findLoadedClass` → `findClass(name)`。 |
| `MirageClassLoader.java:98-112` | `findClass`：`readClassBytes(name)`（`getResource(path)`，会咨询 parent + 自身 URL）→ `MirageTransformer.transform` → `MirageMixinBootstrap.transform` → `LoadProgress.increment("mixin.classes")` → `defineClass`。**`readClassBytes` 返回 null 时直接 `super.findClass(name)`**（同样只找资源，必然 CNFE）——这就是 fallback 的唯一自然插入点。 |
| `MirageTransformer.java:19-41` | 只对 6 个特定 `net/minecraft/...` 类做织入，对 `net.neoforged.*` 是 no-op。 |

### 1.2 产物对账（`launcher/mirage-launch/build/libs/mirage-launch-0.1.0.jar`，2 952 303 字节）

| 项 | 数量 |
|---|---|
| `net/neoforged/...` 根路径类（N0/N1 的 clean-room stub） | **141** |
| `net/minecraft/...` 根路径类 | **0** |
| `io/mirage/loader/sandbox/...` 类 | **17** |
| `mirage/sandbox-fake/...` 固定表面 | **21** |

`mc-shili/versions/26.2/26.2.jar`：`net/minecraft/...` **10372** 个类、`net/neoforged/...` **0** 个。
根目录 `启动 26.2-mirage.bat` 的 `-cp` 同时含 fat jar、`26.2.jar` 与 3 个测试 mod jar。
P4b 那 5 个字节不一致类（`ClientTooltipFlag` / `FluidTintSource` / `PartEntity` /
`FluidSlotDisplay` / `FluidTagSlotDisplay`）在 `26.2.jar` 里**均不存在**（它们是 neo 侧的类，
超类/接口指向 mc 侧），不影响「真启动 mc 类齐全」这个判断。

### 1.3 录制端现状（`SandboxRecorder.java:97-129`）

沙盒 provider 链为 **固定表面 → `CompatFakeProvider` → `FakePackProvider`**，
`SandboxClassLoader.findFakeClass` 两级回退，compat 在前（stub 有真桥接逻辑，不能被空架子顶掉）。
`FakePackProvider.fromSystemProperty()` 解析顺序 `PROPERTY` > `HOME_PROPERTY` > 都没有返回 `null`，
**绝不偷扫全盘**；`ALLOWED` 是 `{ "net.neoforged.", "net.minecraft." }`；`findFakeBytes` 收**点分**类名。
kill switch：`-Dmirage.msr.fakeprovider=off`。**这条链目前只被 `SandboxRecorder` 消费。**

### 1.4 生成器形状（`FakeStubGenerator.java:286-303`）

```java
if (neoMissing.contains(dotted)) { emitClass(...); return internal; }
if (!missingAll.contains(dotted)) return internal;
// 缺的 net.minecraft 超类型：不生成，也不能引用（否则 defineClass 直接 CNFE）
return "java/lang/Object";
```

`missingAll` 是**沙盒**的完整缺口集合（neo + mc，P4b 特意保住的完整集）。
因此 pack 里凡是超类/接口指向 mc 的类，形状都是「继承 `Object`」。
`FakeGenCli --ref` 传的是 `modules/mirage-neoforge-compat/build/classes`（只有 141 个 stub），
**不含 `26.2.jar`** ⇒ 分析器把 mc 类当「缺失」，形状与真启动不一致。

### 1.5 静态态唯一性先例（`modules/.../NeoCompatBootstrap.java`）

> `net.neoforged.*` 与 `io.mirage.neoforge.*` 由 MCL 加载，lifecycle-starter 由 app loader 加载。
> LS 直接 import 会得到 app loader 的另一份静态态 —— `FMLPaths` / `ModList` /
> `ModLoadingContext` 各两份，mod 侧读到的永远是未初始化的那份。

所以接线**必须**由 MCL 自己 `defineClass`（与 141 个 stub 同一 loader），
且不能让任何 `net.neoforged.*` 类在 app loader 里再出现一份。
`CompatFakeProvider` 的注释已把这条写成「读字节码 → 自己定义是唯一安全的形式」。

---

## 2. 三条硬约束（决定了方案空间）

1. **形状不匹配**：pack 是沙盒形状（mc 超类回落 `Object`），真启动 mc 类齐全。
   原样接入 ⇒ mod 做 cast / 覆写 / 按超类解析方法时得到 CCE / `AbstractMethodError` / `VerifyError`。
   这类失败比 CNFE 更晚、堆栈更脏，且与「加载器能力不足」难区分。
2. **UOE 骨架 ≠ 实现**：方法体一律抛 UOE。沙盒里它的用途是「让 fake pass 跑完、
   产出 `missing_fake` / `stub_incomplete` 诊断清单」，是**测量工具**而非运行时实现。
   真启动接进去 = 把「明确失败」变成「看似加载成功、随后诡异崩溃」（C84 的假覆盖）。
3. **静态态双份风险**：见 §1.5。MCL 与 app loader 的边界已经被 N1 踩过一次，
   接线方式稍偏（`Class.forName` 用错 loader、从 app 读类再 define 到别处）就会触发 `LinkageError`。

---

## 3. 三条路线

### 路线 A（推荐，默认可开、零风险）：**真启动缺口探针**，pack 不进 `defineClass`

- 在 `MirageClassLoader.findClass` 的「`readClassBytes(name) == null`」分支里，
  **仅当 `name.startsWith("net.neoforged.")`** 时把类名记进一个进程内的 `MissingNeoTracker`（`Set<String>`）。
- **不改类加载结果**：仍然 `super.findClass(name)` 抛 CNFE，行为与现在逐字节一致。
- 启动收尾（或 FATAL 时）把清单写到 `<gameDir>/mirage-msr/missing-neo.txt` + 一行汇总日志。
  这份清单直接喂 `FakeGenCli --only-file` / `--from-mid` 的合成白名单。
- 生成物**不回灌运行时**，走 N1 正规路径：审成有实现的 compat stub 编进 fat jar。
- **价值**：把「P3 沙盒录到的缺口」升级成「真启动实际撞到的缺口」，闭环更准；
  零假覆盖、零 LinkageError、默认可开（记账是一次 `Set.add`）。
- **门禁可完全离线验**：构造 MCL + 临时 URL，断言「缺 neo 类被记账且仍抛 CNFE」、
  「mc / `io.mirage.neoforge` / `java.*` 不记账」、「开关关闭时零行为变化」、
  「落盘清单格式可被 `--only-file` 吃下」。

### 路线 B（诊断模式，需用户明确裁决后才可开）：`findClass` 加一级 fallback

- **插入点**：`MirageClassLoader.findClass`，`readClassBytes(name) == null` 之后、
  `super.findClass(name)` 之前。由 `loadClass` 的 `synchronized (getClassLoadingLock(name))`
  保护，`findLoadedClass` 已保证同名只 define 一次。
- **白名单只认 `net.neoforged.`**。必须显式禁用 `net.minecraft.`（真 MC 类一律走 `26.2.jar`）
  与 `io.mirage.neoforge.`（真兼容层）。注意 `FakePackProvider.ALLOWED` 目前**含** `net.minecraft.`
  —— 真启动侧要么另写一个更窄的 provider，要么在调用点加前缀闸；**不能直接复用而不收紧**。
- **由 MCL 自己 `defineClass`**（`CompatFakeProvider` 先例）。绝不委托 parent、绝不在 app loader 里加载。
- **不进** `MirageTransformer` / `MirageMixinBootstrap` 管线：前者对 neo 是 no-op 但白走一趟，
  后者对 UOE 骨架做注入可能直接炸；也不要 `LoadProgress.increment("mixin.classes")` 污染计数。
- **compat stub 永远优先**：fallback 语义天然保证（资源存在时根本走不到 pack），
  与 `FakePackProvider` 类注释「compat 在前」一致，**不会撞名、不会打瘸 N1 已有功能**。
- **三道闸 + 横幅**：`-Dmirage.msr.fakepack.home=<dir>` + `-Dmirage.msr.fakepack.runtime=on`
  两个都给才生效；日志开头打 `FAKE MODE` 横幅；kill switch 复用 `-Dmirage.msr.fakeprovider=off`。
  每个来自 pack 的类计数并记名，收尾打印命中清单，实机可一眼对账。
- **启动方式**：按 `AGENTS.md`「虚拟桌面启动规则」，**不直接改**根目录 `启动 26.2-mirage.bat`、
  不碰 `mc-shili/`；另写一份带 `-D` 的 `.bat` 交给用户双击，再读 `mirage-launch.log` 验结果。
- **必须承认的代价**：只用于诊断录制，绝不作为发布默认；开了之后「mod 加载过去了」
  不再代表任何兼容性结论。要开需用户单独拍板。

### 路线 C（正解，工程量最大）：构建期固化，运行时不需要 pack

- 用**真启动 classpath** 作为分析基准重新算缺口：`--ref` 传
  `modules/mirage-neoforge-compat/build/classes` **＋ `26.2.jar`**，`--shape` 用真实形状源，
  让 `missingAll` 里不再有 mc 类 ⇒ 生成物继承关系正确。
- 产出的类**逐个审成有实现的 stub** 合入 `modules/mirage-neoforge-compat` 源码（N1 路径），
  编进 fat jar；真启动 `net.neoforged.*` 全部由 fat jar 提供，形状与静态态都对。
- 这是唯一「让 mod 真跑起来」的路线，规模接近 N2/N3，须单独立项排期。
- 与路线 A 天然衔接：A 的清单就是 C 的输入。

### 取舍表

| | 假覆盖风险 | LinkageError 风险 | 需启动游戏 | 能让 mod 真跑 | 建议 |
|---|---|---|---|---|---|
| A 缺口探针 | 无（不改加载结果） | 无 | 否（落地需实机对账清单，但门禁离线） | 否，但给出准确缺口 | **本轮推荐，W1 做** |
| B 诊断 fallback | **高**（UOE 晚失败 + 沙盒形状） | 中（接线方式敏感） | **是，需用户同意** | 否 | 待裁决，不作为默认 |
| C 构建期固化 | 无 | 低 | 最终验收需 | **是** | W2/W3，按 N2 规模排 |

---

## 4. 影响面清单（改动点逐项）

| 文件/位置 | 路线 A | 路线 B | 备注 |
|---|---|---|---|
| `core/.../MirageClassLoader.java` `findClass` 98-112 | 加记账分支 | 加 fallback 分支 | 唯一插入点；`loadClass` 78-94 的委派规则**一律不动**（#2 未修，别顺手改） |
| `core/.../MirageClassLoader.java` `readClassBytes` 114-127 | 不动 | 不动 | `getResource` 咨询 parent 的行为是既有语义 |
| `core/.../loader/` 新增 `MissingNeoTracker` | 新增 | 复用 | 纯 JDK、可离线测 |
| `lifecycle-starter/.../TransformStage.java:49-57` | 不动 | 不动 | 系统属性由 JVM 启动参数给，不需要在 LS 里读 |
| `core/.../sandbox/FakePackProvider.java` | 不动 | 调用点加前缀闸或另写窄 provider | `ALLOWED` 含 `net.minecraft.` 是真启动侧的隐患 |
| `modules/mirage-neoforge-compat/.../NeoCompatBootstrap.java` | 不动 | 不动 | 静态态唯一性基线，接线不得破坏 |
| 根目录新 `.bat` | 不需要 | 需要 | 不改用户现有 `启动 26.2-mirage.bat`，不碰 `mc-shili/` |
| `scripts/verify_fakepack_wiring.ps1`（新） | 新增 | 新增 | 见 §5 |

---

## 5. 不变量（下一轮门禁的断言来源）

1. **默认零变化**：没有任何 `-D` 时，MCL 不查磁盘、不打日志、不改 CNFE 行为，与 HEAD 行为一致。
2. **前缀闸**：只有 `net.neoforged.` 能进 pack/记账；`net.minecraft.*`、`io.mirage.neoforge.*`、
   `java.*`、`org.lwjgl.*` 一律不碰。
3. **compat 优先**：fat jar 资源存在时**绝不**走 pack（fallback 语义，不是覆盖语义）。
4. **defineClass 归属**：pack 类由 MCL 自己定义，与 141 个 stub 同一 loader；不得出现第二份同名类。
5. **可观测**：A 的清单落盘可复现；B 的每次命中都有计数与类名，收尾能对账。
6. **开关可逆**：kill switch 关闭后零开销、零残留状态。
7. **形状一致**：路线 B/C 若要开，生成物必须以真启动 classpath 为 `--ref` 重算，
   否则 §2.1 的形状不匹配没有被消除。

**验证方式**（按项目铁律）：
- 新增/改脚本**必须做变异检验**，且分两层：守卫层（源码行首锚定 `(?m)^\s*`）/ 行为层
  （构造真实 MCL + 临时 pack，让探针真跑到改坏的类，C77）。
- 落盘型变异脚本 `try{…}finally{字节级还原}` + 动手前预检锚点 + 还原后 byte-identical（C82）。
- `.ps1` 落盘 UTF-8 BOM；读源码用 `ReadAllText(p, UTF8)`，禁用 `Get-Content -Raw`/`Set-Content`（C76）。
- 出包后 `javap -c` 核字节码分支 + `unzip -l` 核类存在，别只信 mtime（部署铁律）。
- 实机部分：只由用户双击新 `.bat`，读 `mc-shili/.../logs/mirage-launch.log`；本轮及实现轮都不自行启动游戏。

---

## 6. 分期

| 阶段 | 内容 | 是否需用户同意 |
|---|---|---|
| W0（本轮） | 本设计 + 影响面分析，零代码改动 | — |
| W1 | 路线 A：缺口探针 + `verify_fakepack_wiring.ps1`（守卫/行为两层变异全红） | 否（纯离线） |
| W2 | 用真启动 classpath 重算缺口清单，产出「真启动 M」并与 P3 沙盒 M 对账 | 否（纯离线） | **已完成**（2026-11-14）：real-startup M == sandbox M == 80 neo 类，pack 逐字节一致；`verify_fakepack_realstartup.ps1` 10/10；real-shape 增强阻塞于真实 neo jar，列为后续项 |
| W3 | 路线 C 审 stub / 路线 B 若裁决要开则实现 + 新 `.bat` + 实机对账 | **是**（B 涉及启动游戏） |

---

## 7. 本轮明确不做

- 不改任何 `.java` / `.ps1` / `.bat`；不构建 fat jar；不部署；不启动游戏；不动 `mc-shili/`。
- 不擅自把 `FakePackProvider.ALLOWED` 收窄（会改变录制端既有行为，须与实现轮一起改并补门禁）。
- 不借机改 `MirageClassLoader` 的 `io.mirage.` 委派规则（#2 待单独立项）。
- 不把 C84「只生成 neo、mc 只报告不生成」的边界放宽。

> 参考：`docs/plans/2026-11-07_MSR-完整形态-P0至P4.md`、
> `docs/经验与踩坑.md` C84/C85、`docs/改动登记表.md` P4(`a23ce01`)/P4b(`faa262f`)。
