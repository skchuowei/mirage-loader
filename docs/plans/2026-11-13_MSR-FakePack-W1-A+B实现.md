# W1：FakePack 接真启动 —— 路线 A（缺口探针）+ B（诊断 fallback）实现

> 2026-11-13 · 用户指令「W1A+B」，并修订启动规则：**要不要启动游戏的根本因素是是否拉起 GUI**。
> 关联：`docs/plans/2026-11-13_MSR-FakePack接入真启动类加载器.md`（W0 设计）、
> `docs/经验与踩坑.md` C84/C85/C92、`AGENTS.md` 第 2 节「启动判据：是否拉起 GUI」。
> 定位：把 W0 的路线 A 与路线 B **都落地**，用 headless JVM 探针把 7 条不变量逐条钉死。
> **不部署、不拉 GUI、不碰 `mc-shili/`。**

---

## 目标

在 `MirageClassLoader.findClass` 的「资源缺失」分支落地两条能力，且**默认行为逐字节不变**：

- **A 缺口探针**：真启动时把自己实际撞到的 `net.neoforged.*` 缺失类记下来并落盘，
  清单格式可直接被 `FakeGenCli --only-file` 消费 ⇒ 把「P3 沙盒录到的缺口」升级成
  「真启动实际撞到的缺口」。**不改类加载结果**，仍抛 `ClassNotFoundException`。
- **B 诊断 fallback**：显式双开关打开时，从 fake pack 读字节码，**由 MCL 自己 `defineClass`**，
  让「缺哪些类」能在一次真启动里被完整观测；带 `FAKE MODE` 横幅、命中计数与清单。

**可验证标准（done 判据）**：

1. 无任何 `-D` 时，MCL 不查磁盘、不打日志、不改 CNFE 行为（默认零变化）。
2. A 开：neo 缺失类被记账且仍 CNFE；`net.minecraft.*` / `io.mirage.neoforge.*` / `java.*` 不记账；
   落盘清单可被 `--only-file` 的解析规则吃下。
3. B 开：`home` + `runtime=on` 两个都给才生效；kill switch `-Dmirage.msr.fakeprovider=off` 能关掉；
   只有 `net.neoforged.` 能进 pack；fat jar / compat classes 里已有的资源**绝不**被 pack 顶掉；
   pack 类 `getClassLoader() == mcl`；不经过 transformer/Mixin/`LoadProgress.increment("mixin.classes")`。
4. `scripts/verify_fakepack_wiring.ps1` 守卫层 + 行为层断言全绿；行为层变异检验
   （改 `.java` → 单文件重编译 → 覆盖 classes 副本 → 探针真跑到改坏的类）**全部变红**，
   含编译路径自检与 byte-identical 还原。
5. `:core:mirage-loader:compileJava` 与 `:launcher:mirage-launch:fatJar` SUCCESSFUL；
   `unzip -l` 核 2 个新类、`javap -c` 核 `findClass` 字节码含 A/B 分支（不信 mtime）。

---

## 前置条件与阻塞项

- W0 设计（`b2c77bd`）已完成，7 条不变量与插入点已取证，本轮直接按它实现。
- 工作区干净（`git status --short` 为空，HEAD `ea6f768`），无别人的 WIP。
- **规则变更已先落地**：`AGENTS.md` 第 2 节判据改为「是否拉起 GUI」——headless JVM 探针
  （不创建窗口、不抢焦点、不写 `mc-shili/`）**可自行启动**，无需逐次请示；
  完整 MC 客户端（拉 GLFW/EarlyDisplay 窗口）仍优先不启动、等用户命令。
  ⇒ 本轮的门禁探针、gradle 构建、`javap`/`unzip` 全部属 (B) 类，可自行执行。
- 阻塞项：**无**。B 的「实机拉 GUI 对账」不在本轮范围（见 §7 边界），不影响 done 判据。

---

## 现状分析

- `MirageClassLoader.java:98-112`：`findClass` 先 `readClassBytes(name)`（`getResource` 咨询
  parent + 自身 URL），为 `null` 时直接 `super.findClass(name)`（同样只找资源 ⇒ 必然 CNFE）。
  **这是唯一自然的插入点**；`loadClass:78-94` 的三段前缀与 `io.mirage.` 委派规则一律不动
  （#2 未修，别顺手改）。
- `FakePackProvider.ALLOWED` 是 `{ "net.neoforged.", "net.minecraft." }`。**真启动侧不能复用**：
  `26.2.jar` 里有 10372 个真 `net.minecraft.*` 类，一旦 pack 能顶 mc 类就会遮蔽真 MC 类。
  W0 §3 已点名。故 B 另写窄 provider，而不是改录制端既有行为。
- pack 是**沙盒形状**（`resolveSuper` 对缺失的 mc 超类型回落 `java/lang/Object`，`--ref` 不含
  `26.2.jar`），方法体一律抛 `UnsupportedOperationException` ⇒ B 只是**诊断录制工具**，
  绝不是让 mod 跑起来的实现。横幅必须把这条讲清，避免把「mod 加载过去了」误读成兼容性结论（C84）。
- `FakeGenCli --only-file` 解析规则（`FakeGenCli.java:104-110`）：逐行 `trim()`、跳过空行与
  `#` 开头、`replace('/', '.')` 收进 `TreeSet`。A 的清单必须遵守这个格式。
- 现有门禁范式：`verify_msr_p4.ps1`（`Read-Utf8` / `Assert-True` / `Assert-Match`）、
  `verify_msr_p2_mutation.ps1`（落盘变异 + 单文件重编译 + 编译路径自检 + 字节级还原）。
  本轮沿用，不另造轮子。

**一个必须避开的假绿陷阱**：pack 目录**绝不能**挂进 MCL 的 URL。`getResource` 会直接从 URL
找到 pack 类 ⇒ `readClassBytes != null` ⇒ 永远走不到 fallback 分支，门禁会「绿得毫无意义」。
探针的 MCL URL 只放 compat classes（或 fat jar），pack 只通过 `-D...home` 给 `RuntimeFakePack`。

---

## 改动文件清单

| 文件 | 改什么 |
|---|---|
| `AGENTS.md` | 第 2 节「虚拟桌面启动规则」→「启动判据：是否拉起 GUI」(A)/(B)/(C)；规则 4 计划骨架的验证步骤同步 |
| `docs/plans/2026-11-13_MSR-FakePack接入真启动类加载器.md` | 顶部**追加**「规则修订附注」（只标不删：原文「B 需用户同意 / W3 是」保留，标注其判据已被 GUI 规则收窄） |
| `docs/plans/2026-11-13_MSR-FakePack-W1-A+B实现.md`（新） | 本计划 |
| `core/.../loader/MissingNeoTracker.java`（新） | A：纯 JDK、静态开关、ConcurrentHashMap.newKeySet、shutdown hook、显式 `flush(Path)` |
| `core/.../loader/RuntimeFakePack.java`（新） | B：窄前缀闸、三道闸、横幅、命中计数、显式 `flush(Path)`、shutdown hook 收尾 |
| `core/.../loader/MirageClassLoader.java` | `findClass` 的资源缺失分支按「A 记账 → B fallback → `super.findClass`」插三行 |
| `scripts/fixtures/fakepack-wiring/WiringProbe.java`（新） | headless JVM 探针：构造真实 MCL + 临时 pack，按场景输出可断言文本 |
| `scripts/verify_fakepack_wiring.ps1`（新） | 守卫层 + 行为层门禁 |
| `scripts/verify_fakepack_wiring_mutation.ps1`（新） | 行为层变异检验（含编译路径自检 + byte-identical 还原） |
| 台账三份 + 工作区记忆 | 更新日志 / 改动登记表 / 经验与踩坑（C93+）、`MEMORY.md`、今日日志 |

> **明确不做**：
> - 不改 `MirageClassLoader.loadClass` 的 `io.mirage.` 委派规则（#2 单独立项）；
> - 不改 `FakePackProvider.ALLOWED`（会改变录制端既有行为，须与实现轮一起单独裁决）；
> - 不碰 `modules/mirage-neoforge-compat/`、`NeoCompatBootstrap`、任何 mixin 配置；
> - 不改生成器 `FakeStubGenerator` / `FakeGenCli` / `--ref` 形状（路线 C 是 W2/W3 的事）；
> - 不写 `.bat` 启动脚本、不部署 fat jar 到 `mc-shili/libraries/`、不拉任何 GUI 窗口；
> - 不跑裸 `gradle build`（会把 fat jar 覆盖成几十 KB 瘦 jar，C12）。

---

## 每处改动要点

### 1. `MissingNeoTracker`（路线 A）

- 开关 `-Dmirage.msr.missingneo=on`，落盘路径 `-Dmirage.msr.missingneo.out=<file>`。
  **默认关闭**是硬要求：满足 W0 不变量 1「没有任何 `-D` 时零变化」。
- 静态 `final boolean ENABLED` 在类初始化时算好；`recordIfEnabled(String name)` 首行
  `if (!ENABLED) return;`，随后 `if (!name.startsWith(NEO_PREFIX)) return;`（前缀闸）。
  ⇒ 未启用时零 IO、零日志、零集合写入。
- `Set<String>` 用并发集合；`snapshot()` 返回排序副本；`flush(Path)` 写 UTF-8：
  首行 `# ...` 头注释，随后一行一个点分类名（`--only-file` 会跳过 `#`）。
- 启用时注册 shutdown hook 落盘（防止启动中途 FATAL 丢清单），并暴露公开 `flush(Path)`
  供离线门禁/显式收尾确定性调用。**落盘失败只 WARN，绝不升级成启动 FATAL**。

### 2. `RuntimeFakePack`（路线 B）

- 三道闸：`PROPERTY_HOME = mirage.msr.fakepack.home`（非空）+ `PROPERTY_RUNTIME =
  mirage.msr.fakepack.runtime` 等于 `on` + kill switch `mirage.msr.fakeprovider != off`。
  三者全满足才 `ENABLED=true`；静态初始化**不抛异常**（路径不存在只记为不可用）。
- `findBytesIfEnabled(String name)`：先 `if (!ENABLED) return null;` →
  `if (!name.startsWith("net.neoforged.")) return null;`（**只认 neo**）→
  路径 `root.resolve(name.replace('.', '/') + ".class").normalize()` 且必须 `startsWith(root)`
  （防 `..` 逃逸）→ `Files.isRegularFile` → `readAllBytes`。
- 命中记入并发集合，横幅 `FAKE MODE` 在**首次命中时**打一次（用 `MirageLog.LOADER.warn`，
  内容写明「诊断模式 / 骨架抛 UOE / 形状不匹配 / 不代表兼容性」）。
- 可选 `-Dmirage.msr.fakepack.out=<file>` 落盘命中清单；shutdown hook 收尾打印汇总
  （类名 + 数量），格式与 A 清单一致。

### 3. `MirageClassLoader.findClass`

```java
byte[] original = readClassBytes(name);
if (original == null) {
    MissingNeoTracker.recordIfEnabled(name);            // A：只记账，不改结果
    byte[] fake = RuntimeFakePack.findBytesIfEnabled(name); // B：双开关 + 窄前缀闸
    if (fake != null) {
        return defineClass(name, fake, 0, fake.length);     // 必须 MCL 自己 define
    }
    return super.findClass(name);                        // 仍抛 CNFE
}
// 原样：transformer → Mixin → LoadProgress → defineClass（B 绝不进这条管线）
```

- 顺序不可换：A 在 B 前 ⇒ 即使 B 提供了 fake，仍如实记录「真启动缺这个类」。
- `readClassBytes != null` 的分支**一行不改** ⇒ compat stub 与真 MC 类天然优先（不变量 3）。
- `defineClass` 不经过 `MirageTransformer` / `MirageMixinBootstrap` / `LoadProgress.increment`
  （W0 §3 路线 B：UOE 骨架进 Mixin 可能直接炸，且会污染计数）。

---

## 回归测试影响面

| 旧行为 | 可能被打破的点 | 防护 |
|---|---|---|
| 默认启动的类加载 | 多两次静态字段读取；若开关解析抛异常会 ExceptionInInitializerError | `ENABLED` 静态初始化 try/catch 包住，异常记 WARN 不抛；门禁「默认零变化」断言 |
| compat stub 141 个类 | pack 撞名顶掉有实现的 stub | fallback 只在资源缺失分支；门禁「compat 优先」断言 |
| 真 `net.minecraft.*` 10372 个类 | 窄前缀闸写错会遮蔽真 MC 类 | 门禁「mc fake 类仍 CNFE」+ 变异（把前缀放宽成 `net.` 必须变红） |
| MCL/app loader 静态态唯一性 | `RuntimeFakePack` 若在别的 loader 定义类会 LinkageError | 由 MCL 自己 `defineClass`；门禁断言 `getClassLoader() == mcl` |
| P4b 录制端 `FakePackProvider` | 共用属性名但语义不同 | 只复用 `HOME_PROPERTY` 字符串，**不改类**；门禁断言录制端行为不变（无 `runtime=on` 时零影响） |
| 现有 `verify_msr_p4.ps1` / p1/p2/p3 门禁 | 无侵入（不改被验源码） | 全量回归复跑 |

**零侵入**：`launcher/`、`lifecycle-starter/`、`modules/`、`api/`、`mdk/` 的生产代码均不动；
`TransformStage` 不需要读系统属性（由 JVM 启动参数给）。
新增断言全部落在**新脚本**，不改已有门禁，避免动别人的基线。

---

## 验证步骤

> 按 `AGENTS.md` 第 2 节启动判据：以下**全部是 headless、不创建窗口、不写 `mc-shili/`** 的
> (B) 类动作，可自行执行。**不拉 GUI**；完整 MC 客户端的实机对账仍需用户明确命令。

1. **基线**（规则 2.4，改核心敏感区前）：`gradle :core:mirage-loader:compileJava --no-daemon`
   （JDK 25、`-p <repo>`；**不跑裸 `build`**）。
2. 实现后：`gradle :core:mirage-loader:compileJava :launcher:mirage-launch:fatJar --no-daemon`；
   `unzip -l <fat jar>` 核 `MissingNeoTracker.class` / `RuntimeFakePack.class` 存在、
   体积 ~2.9 MB；`javap -c -p -classpath <fat jar> io.mirage.loader.MirageClassLoader`
   核 `findClass` 里确有 `recordIfEnabled` / `findBytesIfEnabled` / `defineClass` 三条调用。
3. `scripts/verify_fakepack_wiring.ps1`：
   - 守卫层：行首锚定 `(?m)^\s*` 核三行插入顺序、前缀闸字面量、双开关与 kill switch 判定。
   - 行为层：`WiringProbe` 分 6 个场景各起一次 headless JVM（系统属性启动时固定，必须分进程）：
     默认 / A 开 / B 双开关 / B 单开关 / B + kill / B + compat 优先 / B + mc 前缀闸。
     探针输出 `KEY=value` 行，PowerShell 逐条断言。
4. `scripts/verify_fakepack_wiring_mutation.ps1`：**编译路径自检**（未变异源码走同流程仍全绿）
   + ≥5 个变异全红 + 每次 byte-identical 还原 + 基线回绿。变异点：
   ① A 前缀闸放开；② B 前缀闸放宽成 `net.`；③ 去掉 `runtime=on` 闸；
   ④ 去掉 kill switch 闸；⑤ 把 fallback 的 `defineClass` 改成委派 parent；
   ⑥ A 落盘只写头注释。
5. 全量回归复跑：`verify_msr_p4.ps1`（DEFAULT/REALMODS/MUTATION/FROMMID）、
   `verify_msr_p2.ps1`、`verify_msr_p2_mutation.ps1` 至少抽 DEFAULT 与 p2 确认无回归。
6. 收尾：台账三份 + 记忆双写，精确 `git add` 本轮文件、commit、补哈希、`push origin main`。

---

## 合规边界

不涉及 LGPL 代码：本轮只改 MG 原生 `core/mirage-loader/` 的类加载分支与两个纯 JDK 新类，
不引用 `refs/neoforge/` / `refs/forge/` 源码，也不改 `modules/mirage-neoforge-compat/`。
`net.neoforged.*` 仅作为**字符串前缀**与**沙盒生成的骨架字节码**出现，属行为观察/接口枚举范畴。

---

## 回滚方案

- 本轮是单个逻辑改动（A+B 同批），出问题时 `git revert <W1 主体 commit>` 即可整体撤掉；
  `MirageClassLoader` 只有 `findClass` 的资源缺失分支被改，回滚后该分支恢复成
  `return super.findClass(name);`，另外两个新类与两个新脚本无人引用，撤掉无残留。
- **保留**：W0 设计（`b2c77bd`）、P4b（`faa262f`）、p2/M4（`ca866cb`）全部不受影响。
- 产物级备份：回滚后重新 `fatJar`；若已部署过（本轮**不会部署**），需按规则 6 写回退报告
  并从 `.bak` 恢复 fat jar。本轮不部署 ⇒ 无 jar 级备份需求。
- 规则文档 `AGENTS.md` 的判据修订属用户明确指令，**不随代码回滚**；若回滚代码，
  在更新日志注明「规则修订保留」。
