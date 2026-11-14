# MSR FakePack · W3 real-shape 增强（2026-11-14）

> 用户指令：「real-shape 增强，jar 你自己下」。
> 本文是**实现与取证结论**（非待办计划）；W0 设计见 `2026-11-13_MSR-FakePack接入真启动类加载器.md`。

## 1. 目标与范围

W3-C 把 P4b pack（80 个 `net.neoforged.*` UOE 骨架）烘焙进 fat jar，但那是**沙盒形状**：
`superName` 一律 `java/lang/Object`，接口/enum/成员按调用点猜。real-shape 的目标是用真实
NeoForge 类作形状源，让 stub 真正 `extends` 真实 neo 超类、`implements` 真实接口、识别
enum / annotation、带真实成员签名。

**不做**：把 stub 审成有实现的真类编进 compat 源码 N1 路径（W3 完整路线 C 的另一半）；
不部署、不拉 GUI 实机对账（按 C93 需用户命令）；不生成任何 `net.minecraft.*` 类。

## 2. jar 来源（「自己下」的实际结果）

沙箱**外网不可达**（`maven.neoforged.net` 与 `github.com` 均 `HTTP:000`，curl rc=28/35），
但用户游戏目录里已有 NeoForge 26.2.0.90 完整安装的三件套，**无需下载、版本精确匹配**：

| 形状源 | 路径（`mc-shili/libraries/net/neoforged/…`） | 用途 |
|---|---|---|
| `neoforge-26.2.0.90-universal.jar` | `neoforge/26.2.0.90/` | 1892 个 `net/neoforged/*` 类，主形状源 |
| `bus-8.0.5.jar` | `bus/8.0.5/` | `bus.api.Event` / `ICancellableEvent` 等 |
| `loader-11.0.16.jar` | `fancymodloader/loader/11.0.16/` | `fml.*` 加载期类 |

三者正是 `scripts/common/MsrP4.ps1` 的 `Find-MsrShapeJars` 默认收集的组。
**只读**，不写 `mc-shili/`、不进版本控制。W2 记录的「本环境没有 real-shape 形状源」据此作废
（按「只标不删」在更新日志与本文件标注，不改历史条目）。

## 3. 生成法（复用现有 CLI，不改生成器语义）

```text
java -cp <loader classes>;<asm-9.9.jar> io.mirage.loader.FakeGenCli \
  --ref <compat classes> --out <pack> --only-file <114 类闭包清单> \
  --shape <universal> --shape <bus> --shape <loader> --verify <6 mod jars>
```

- 白名单 = 原 80 个 mod 缺口 + **34 个传递超类/接口闭包**（用 `FakeStubGenerator.FileShapeSource`
  从三个 jar 算 BFS：`superName` + `interfaces` 里所有 `net/neoforged/*`），共 114 类。
- `--shape` 可多次传入；`--verify` 是唯一验收判据（补齐后白名单每个类可解析）。
- 结果 `generated=114 skipped=611 failed=0 bytes=213848`，`--verify` 全 OK。
- 三个 jar 由脚本自动发现，但 `msr_p4_generate_fakes.ps1` 的参数名 `$Home` 与 PowerShell
  只读自动变量冲突（C100），故本轮直接调底层 `FakeGenCli` 绕过包装器。

## 4. 三个必须一起做的修复（缺一不可）

### 4.1 `FakeGenCli`：按需白名单里的间接超类要补进 `missing`
`generate()` 里 `toGenerate = neoMissing ∩ whitelist`，而 `neoMissing` 来自 **mod 常量池缺口**。
34 个间接超类 mod 没直接引用 → 不在 `missing` → 被 `retainAll` 裁掉（`generated=80`、
`verify MISS` 34 条）。修法：按需模式在 `generate` 之前把白名单里的 `net.neoforged.*` 类
`missing.add(w)`，让 `resolveSuper` 能递归生成整条继承链。既有用法（白名单 ⊆ 缺口）
行为逐字节不变；`emit-missing` 与 per-mod 缺口统计不受影响（放在它们之后）。

### 4.2 `FakeStubGenerator.resolveSuper`：间接 mc 超类一律回落 `Object`
旧实现只对「在 `missingAll` 里的 mc」回落；形状源带来的**间接 mc 超类**
（`net.minecraft.core.component.DataComponentHolder` 等，mod 没直接引用故不在 `missingAll`）
被 `if (!missingAll.contains(dotted)) return internal` 当成「确定存在」直接写进
`superName` / `interfaces`，headless classpath 无真实 MC jar ⇒ 5 个 `NoClassDefFoundError`。
修法：`dotted.startsWith("net.minecraft.")` 时无条件回 `java/lang/Object`，与 W0 边界
「mc 只报告不生成、绝不引用」一致。真形状价值在 neo→neo 链，mc 侧保持 Object 回落才自洽。

### 4.3 `FakeStubGenerator.emitField`：清 `ACC_PROTECTED`
`(access & ~(ACC_FINAL | ACC_PRIVATE)) | ACC_PUBLIC` 没清 `ACC_PROTECTED`，真实形状源里的
protected 字段变成 `public|protected` (0x5) ⇒ 7 个类 `ClassFormatError: Illegal field modifiers`。
修法：mask 加上 `ACC_PROTECTED`。

## 5. 关键取证：为什么「直接换 real-shape」是退化

| 阶段 | 内置类数 | 全量 loadClass 结果 |
|---|---|---|
| W3-C 原 UOE 骨架 | 80 | 80/80 OK（全部回落 Object） |
| 直接喂 `--shape`、白名单仍 80 | 80 | **38 OK / 42 NCDFE**（悬空真实超类） |
| 闭包 114 + 生成器三修复 | 114 | **114/114 OK**，零 NCDFE / 零 ClassFormatError |

结论：**只换形状源不补闭包 = 把「能加载的假类」换成「加载不了的假类」**，正是 C92
「把启动期 CNFE 换成运行期晚失败」的另一种形态。必须做**全量加载**验证，不能只信
`--verify`（它只查资源存在，不查 `defineClass`）。

## 6. 门禁与回归

| 门禁 | 结果 |
|---|---|
| `verify_fakepack_realshape.ps1`（新） | **29/29**：114 类全量加载、REALSUPER=66 / REALITF=33、生成器 3 守卫、守卫层 3 变异 + 行为层删 `ScreenEvent` 超类产生 29 个 NCDFE、负向自检 114/114 |
| `verify_fakepack_w3_c.ps1` | **33/33**（数量 80→114；`NEO_SUPER=Object` 断言改为 real-shape 口径；探针跳过 compat 原生类） |
| `verify_fakepack_wiring.ps1` | 90/90 |
| `verify_fakepack_wiring_mutation.ps1` | 7/7 全红 + GREEN-OK + 基线回绿（同步了遗留的 B_RUNTIME 旧锚点） |
| `verify_msr_p4.ps1` 四模式 | DEFAULT 37/37、RealMods 48/48、FromMid 52/52、Mutation 45/45 |
| 其它 | p2 60/60、p3 53/53、real_mods 27/27、msr_mutation 全符合预期、W2 realstartup 10/10 |

**默认零变化**：DEFAULT 下 114 类仅 6 个能加载（compat 原生 stub），`B_ENABLED=false`、
`B_HITS=0`、无 FAKE MODE 横幅。BUILTIN_ON 下 `B_HITS=108`、横幅打出。

## 7. 边界（不变量，勿回退）

- pack 仍是 **UOE 骨架（方法体抛 UnsupportedOperationException）**，只是形状真实；
  「加载成功」**不代表兼容性结论**，禁止作为发布默认（FAKE MODE 横幅不变）。
- 只服务 `net.neoforged.*`；`net.minecraft.*` 一律回落 `Object`、0 个 mc 类进 pack。
- 114 个类放 `mirage/fakepack/` 前缀，**不挂进 MCL 的 URL**（C96），compat 天然优先。
- 默认关闭 + kill switch + `runtime=on` 三闸不变。
- 纯 headless、未部署、未拉 GUI、未写 `mc-shili/`（只读取 jar）。
- fat jar 3 089 637 字节（W3-C 时 3 051 866）。

## 8. 仍未做

- W3 完整路线 C 的另一半：把 stub 审成有实现的真类编进 compat 源码 N1 路径。
- 拉 GUI 的完整 MC 客户端实机对账（C93，需用户明确命令）。
- 真实 mod 在 real-shape pack 下的行为级矩阵（当前只验到链接期全绿）。
