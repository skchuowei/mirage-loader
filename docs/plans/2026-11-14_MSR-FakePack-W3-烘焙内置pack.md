# MSR FakePack · W3 路线 C：把 P4b pack 烘焙进 fat jar 作内置 fallback

> 日期：2026-11-14
> 承接：W0（接线设计）/ W1（A+B 实现）/ W2（真启动缺口重算）
> 用户指令：「继续 线路C」
> 范围：纯离线、零 GUI、不碰 `mc-shili/`（只写 `%TEMP%` 与仓库内资源目录）

## 0. 背景与判据

W0 把「pack 接真启动」拆成三条路线：
- **A** 缺口探针（W1 已落地）
- **B** 诊断 fallback（W1 已落地，home 目录驱动）
- **C 构建期固化**：用真启动 classpath 重算 `--ref`，产物审成有实现 stub 合入 compat 源码 N1 路径、编进 fat jar，运行时**不需要外部 pack**。

W2 已实证：真实启动缺口 M_real 与沙盒 M_sandbox **逐字节一致**（sha256 同，均 80 个 `net.neoforged.*` 类），现有 P4b 生成物对真实启动同样有效，**无需另造**。
W2 同时指出真·real-shape 变体（让 neo stub 真正 `extends` 真实超类）被资源阻塞：需要含 `net.neoforged.*` 形状的真实 NeoForge universal jar 作 `--shape`，本环境没有。

⇒ 本轮可落地的 **W3-C 子集**：把现有已验证的 P4b pack（80 个 neo UOE 骨架，沙盒形状）**以资源形式烘焙进 fat jar**，路径前缀 `mirage/fakepack/`，让 W1 已接好的 `RuntimeFakePack` fallback 在**无外部 `home` 目录**时也能从 classpath 内置资源服务；默认关闭 + kill switch 保留。这等价于「W1 路线 B 的内置资源模式」，是 W0 路线 C「构建期固化、运行时不需要外部 pack」的最小可行落地。

## 1. 设计要点（与 W0 三条硬约束对齐）

| W0 约束 | 本轮落法 |
|---|---|
| fallback 只能插在 `MirageClassLoader.findClass` 的 `readClassBytes==null` 之后 | 不改 MCL 插入点；`RuntimeFakePack.findBytesIfEnabled` 在 `readClassBytes==null` 后调用，`defineClass` 仍由 MCL 自己执行 |
| 前缀闸只认 `net.neoforged.`（不能复用 `FakePackProvider.ALLOWED` 含 `net.minecraft.`） | 内置资源只含 `net/neoforged/**`，`findBytesIfEnabled` 窄前缀闸不变 |
| 必须由 MCL 自己 `defineClass`（compat 静态态唯一性） | 内置字节流直接喂 `MirageClassLoader.defineClass`，与 141 个 compat stub 同 loader |
| pack 目录**绝不挂进 MCL 的 URL**（否则 `getResource` 直接命中、fallback 永不触发＝门禁假绿） | 资源前缀用 `mirage/fakepack/`（不是 `net/neoforged/`），MCL 的 `readClassBytes` 不会自动加载它，fallback 仍须显式触发 |

### 资源摆放（关键决策）
- 资源放 `modules/mirage-neoforge-compat/src/main/resources/mirage/fakepack/net/neoforged/**`（80 个 `.class`，60 858 字节，0 个 `net/minecraft`）。
- **不能**放 `net/neoforged/` 根路径：那样 MCL 的 `readClassBytes` 在类查找早期就会从 classpath 自动命中，破坏「默认零变化」与「compat 优先」。用 `mirage/fakepack/` 前缀把内置 pack 与普通类路径隔离，只有 fallback 显式 `getResourceAsStream("mirage/fakepack/<path>.class")` 才拿到。
- `.gitignore` 原 `*.class` 规则会吞掉这些**有意提交的 stub 资源** ⇒ 加否定例外（仅限此目录），见 §5。

### RuntimeFakePack 改动
- `initEnabled()`：`runtime=on` 且 kill switch 未 `off` 即启用，**不再硬性要求 home 目录**（W1 的 `home.isBlank() || ...` 判定移除）。
- `findBytesIfEnabled` 拆为 `readFromHome`（原逻辑，home 非空时优先）+ `readFromBuiltin`（从 `mirage/fakepack/` classpath 资源读，`HITS` 计数 + 横幅 `"builtin=" + BUILTIN_PREFIX`）。
- 新增常量 `BUILTIN_PREFIX = "mirage/fakepack/"`。
- 双路顺序：先 `readFromHome`，null 再 `readFromBuiltin`。两条都 null 才返回 null ⇒ 默认零变化、compat 优先不变。

## 2. 端到端验证（headless，不拉 GUI）

`scripts/verify_fakepack_w3_c.ps1` **33/33 PASS**：
- **核包内容**：fat jar 内置 pack 含 **80 个 neo 类 + 0 个 mc 类**（防假覆盖）；体积较部署基线 2 996 660 → 3 051 866 字节；`RuntimeFakePack` 字节码含 `mirage/fakepack` 常量。
- **守卫层（8 项，行首锚定 `(?m)^\s*`）**：`BUILTIN_PREFIX` 常量、`findBytesIfEnabled` 在 HOME 空时走 `readFromBuiltin`、`readFromBuiltin` 拼 `mirage/fakepack/` 前缀、`initEnabled` 不再硬性要求 home、旧 home 硬性要求已移除、仍只认 `net.neoforged.` 前缀闸、kill switch 保留、`readFromBuiltin` 方法存在。
- **行为层（18 项，2 个独立 JVM 场景）**：
  - `DEFAULT`（不给 runtime）→ 缺 neo 类仍 CNFE、B 默认关闭、B 零命中、缺 mc/io.mirage.neoforge 仍 CNFE、无 `FAKE MODE` 横幅（**默认零变化**）。
  - `BUILTIN_ON`（`-Dmirage.msr.fakepack.runtime=on`，无外部 home）→ 内置 pack 由 MCL 自己 `defineClass` 成功（`NEO_LOAD=OK:mcl`）、超类回落 `Object`（沙盒形状）、恰好命中 1 个、前缀闸守住（`MC_LOAD=CNFE`/`ION_LOAD=CNFE`）、`FAKE MODE` 横幅打出。
- **变异层（3 项）**：把 `readFromBuiltin` 分支置空再编一份 `RuntimeFakePack` 顶在 fat jar 之前，重跑 `BUILTIN_ON` 必须退回 CNFE、B 零命中；负向自检确认未变异版本曾 `OK:mcl`（否则变异断言空转）。

**W1 回归不回退**：`scripts/verify_fakepack_wiring.ps1` 同步更新 `initEnabled` 守卫（W3-C 起不再硬性要求 home），重跑 **90/90 PASS**（含 B_HALF 只给 home 仍 `false`，因 `runtime=on` 仍是硬门槛）。

## 3. 边界与已知限制（继承 W0/W2，未变）

- **B（含内置模式）只用于诊断录制**：UOE 骨架 + 沙盒形状接运行时＝C84 的假覆盖。内置 pack 让「无外部 home 也能跑诊断」更顺手，但**不改变假覆盖本质**——`net.minecraft.*` 缺口（占真实 mod 缺口九成）仍不生成、不服务。
- **real-shape 增强仍未做**：让 neo stub 真正 `extends` 真实超类需真实 NeoForge universal jar 作 `--shape`，本环境缺 ⇒ 列为独立后续项（仍过 no-arg 构造器陷阱）。
- **W3 完整路线 C（审成有实现 stub 编进 compat 源码 N1）未做**：本轮是「构建期固化资源」子集，没动 `FakeGenCli`/`FakeStubGenerator`、没把 stub 审进 N1 源码。可用现有生成流（W2 已证 M_real==M_sandbox）直接出 pack 编 fat jar，本就是此子集。
- **未部署、未拉 GUI、未碰 `mc-shili/`**：按 W1 启动判据（C93），拉 GUI 的完整客户端实机对账需用户明确命令；`.bat` 与部署另待用户指令。

## 4. 涉及文件

| 文件 | 动作 |
|---|---|
| `modules/mirage-neoforge-compat/src/main/resources/mirage/fakepack/net/neoforged/**` | 新增（80 个 `.class`，从已验证 P4b pack 复制；`.gitignore` 加否定例外后随源码版本控制） |
| `core/mirage-loader/src/main/java/io/mirage/loader/RuntimeFakePack.java` | 改：`initEnabled` 去 home 硬性要求；新增 `readFromBuiltin` + `BUILTIN_PREFIX` |
| `scripts/verify_fakepack_w3_c.ps1` | 新增（W3-C 门禁 33/33） |
| `scripts/fixtures/fakepack-w3c/W3CBuiltinProbe.java` | 新增（枚举内置资源反推类名、逐类 `mcl.loadClass` 验证） |
| `scripts/verify_fakepack_wiring.ps1` | 改：W1 守卫同步 `initEnabled` 新判定 |
| `.gitignore` | 改：加 `!modules/.../mirage/fakepack/**/*.class` 否定例外 |
| 台账三份 | 改 |

## 5. 踩坑（详见 `docs/经验与踩坑.md` C99）

1. PowerShell `Out-File -Encoding ascii` 写 `jar tf` 清单带 `\r\n` 行尾 ⇒ `\.class$` 因行尾 `\r` 匹配失败 ⇒ 正则改 `\.class\r?$`。
2. .NET 正则 `(?m)` 下字符类 `[^ ]` 跨行匹配（greedy 吃掉整文件）⇒ 类路径计数要用 `[^\r\n]*` 锁在单行内（bash `grep` 按行作用故无此问题）。
3. `.gitignore` 的 `*.class` 全局规则会吞掉**有意提交的 stub 资源** ⇒ 加 scoped 否定例外，而非散弹 `git add -f`。
