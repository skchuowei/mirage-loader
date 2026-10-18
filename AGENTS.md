# Mirage Loader · AGENTS.md

> 本文件是 AI / 协作者接手本工程时\*\*第一份\*\*要读的文件。
> 只放\*\*长期稳定\*\*的项目约定：构建命令、模块地图、文档索引、开发流程强制规则。
> 已知 bug / 改动记录 / 临时计划\*\*不要\*\*写在这里，分别去 `docs/已知bug.md`、`docs/更新日志.md`、`docs/plans/`。

\---

## 1\. 项目一句话

**Mirage Loader（幻影加载器）**：一个轻量、现代、独立的 Minecraft Mod 加载器，定位介于 Fabric 与 NeoForge 之间。

* 目标运行环境：**Minecraft 26.2（Java 25）**
* 不依赖 Fabric / NeoForge / Forge 任何运行时代码，纯 ASM 织入
* 构建：**Gradle 9.x + Kotlin DSL**，加载器本体 100% Java
* 当前版本：0.4.0（开发版），目标 0.1.0 首个正式版（见 `PLAN.md`）
* 现状：M0–M7 已完成（架构→启动链路→Mod 发现→Mixin→生命周期→注册中心→MDK→Fabric 兼容层）

### 术语（MG / AG）

| 缩写 | 含义 | 范围 |
|-|-|-|
| **MG** | 加载器本身（核心运行时，包名 `io.mirage`） | `launcher/`、`core/mirage-loader/`、`core/mirage-mixin/`、`core/mirage-eventbus/`、`api/`、`modules/` 里属于"加载器自己"的部分 |
| **AG** | 完整项目结构 | MG 加载器 **+** 对 NeoForge / Forge / Fabric 的兼容层、配套工具链、MDK、gradle-plugin——即整个 `mirage-loader/` 仓库 |

讨论"MG 改了什么"= 只动核心运行时；讨论"AG 规划"= 整个项目路线图。文档前缀：`MG-*.md` 聚焦加载器本身，`AG-*.md` 聚焦整个项目结构/兼容层。

## 2\. 环境与命令

|项|值 / 命令|
|-|-|
|JDK|**必须 JDK 25**（本机路径 `C:\\Program Files\\Java\\jdk-25.0.4\\bin\\java.exe`）|
|工程根|`mirage-loader/`（本文件所在目录，也是 git 仓库根）|
|构建|`gradle build`（仓库未提交 wrapper；用全局 gradle）|
|发布产物|`build/libs/\*.jar`，部署目标见 `PLAN.md` 与启动脚本|
|启动冒烟|根目录 `..\\启动 26.2-mirage.bat`（gameDir = `..\\mc-shili\\versions\\26.2-mirage`）|
|运行日志|`..\\logs\\latest.log`（启动后必看）|
|崩溃现场|根目录 `崩溃\*.txt`、`hs\_err\_pid\*.log`|

> 仓库里\*\*没有 gradlew / scripts/run\_regression.ps1 / src/test\*\*。
> "回归"在现阶段 = `gradle build` 全绿 + 启动冒烟看到 MC 主类起来；
> 真正的 `src/test` 单元测试与冒烟脚本是\*\*待建\*\*项，见 `docs/更新日志.md`。

### 虚拟桌面启动规则（硬性）

MC 26.2 运行在**虚拟桌面**环境里。任何需要"启动游戏 / 跑 MC 冒烟 / 观察 MC 窗口"的动作，**必须**：

* 用 `Win+R` 输入命令启动，**或**写一个 `.bat` 文件让用户双击启动；
* **禁止**直接调用内置工具（PowerShell / Bash / computer_use / python 子进程）去拉起 `java -jar` 或 `启动 26.2-mirage.bat`——那样会进入 AI 的宿主会话环境，而不是用户的虚拟桌面，游戏窗口/输入焦点/注册表都对不上，冒烟等于白跑。
* 正确姿势：在 `..\` 根目录已有 `启动 26.2-mirage.bat`，直接让用户双击它；如果要加参数或换启动方式，先写新 `.bat` 到根目录，让用户双击，再读 `..\logs\latest.log` 验证结果。

### 测试 Mod 获取规则

需要装第三方 mod 来测 Mirage 兼容性时，按以下流程走：

1. **首选 CurseForge / Modrinth 官方源**：
   - Modrinth API 直链优先：`https://api.modrinth.com/v2/project/<slug>/version?game_versions=["26.2"]&loaders=["fabric"]`（Mirage 兼容 Fabric mod，loader 字段按实际填）；
   - CurseForge 有 API 时走 API，没有 API key 时走网页。
2. **直链找不到 / 页面是 JS 渲染 / 需要搜索**：用 `browser-use-automation` 技能（`computer_use_tool` 且 `plane="bu"`，`import seed_browser_use as bu`）打开 CurseForge/Modrinth 搜索、进入版本页、下载 jar。
3. **库存位置**：下载到仓库外的 `..\mods-library\<loader>\`（`fabric/` `neoforge/` `forge/` `mirage/`，按 mod 原生加载器归类），文件名保留官方名（如 `sodium-fabric-0.6.0+26.2.jar`），下次测同 mod 不用重下。
4. **测试时部署**：把要测的 mod **复制**到 `..\mc-shili\versions\26.2-mirage\mods\`（库存副本保留）。
   - 这是对规则 9 的**显式例外**：用户已授权"测试 mod 时自己下载并部署"，即测试场景下允许往 `mods/` 放指定 mod。
   - **边界**：① 只放用户在本轮指定要测的 mod，不擅自加别的；② 下载/部署前在回复里列清单（mod 名、版本、来源 URL、文件大小、库存路径）；③ **测试完自己把本轮放进去的 mod 从 `mods/` 删掉**（或移回 `mods-library/`），保持游戏 mods 目录干净；④ **不删**用户原本就在 `mods/` 里的 mod；⑤ 不碰 `mods/` 外的任何 `mc-shili/` 文件。
5. **版本适配**：只下标记支持 MC 26.2（或用户指定版本）的版本；找不到 26.2 的兼容版本时，停下来告诉用户"该 mod 没有 26.2 构建"，不要硬下旧版。

## 3\. 模块地图

```
mirage-loader/
├── launcher/mirage-launch/     # 入口：被 java -cp 直接调用的 io.mirage.launch.Launcher
├── core/
│   ├── mirage-loader/          # 类加载、Mod 发现、生命周期（核心）
│   ├── mirage-mixin/           # Mixin 引导与配置扫描（核心）
│   └── mirage-eventbus/        # 自研轻量事件总线
├── api/                        # Mod 开发者看到的世界：@Mod / @SubscribeEvent / DeferredRegister
├── io/                         # IO / 资源相关
├── modules/                   # 可拔插模块：fabric-compat / config / network / resources / kotlin…
├── gradle-plugin/              # mirage-gradle 插件（给 MDK 用）
├── mdk/mdk-example/            # 最小可跑 Mod 模板（含 build.bat）
├── docs/                       # 项目文档（本台账就在这里）
└── PLAN.md / README.md / build.gradle.kts / settings.gradle.kts
```

**核心敏感区**（动了必须按规则 2.6 跑基线）：
`launcher/`、`core/mirage-loader/`、`core/mirage-mixin/`、`core/mirage-eventbus/`、`api/`。

## 4\. 文档索引

|文件|内容|
|-|-|
|`PLAN.md`|项目主计划：定位、决策 D0–D11、架构、里程碑|
|`docs/architecture.md`|系统架构细节|
|`docs/AG-总计划.md`|AG 层面完整项目结构总计划（含兼容层路线）|
|`docs/MG-项目总纲.md`|MG 加载器本身总纲|
|`docs/MG-现状摸底与规划修订.md`|当前现状盘点与规划调整|
|`docs/M7-M8-PLAN.md`|M7/M8 阶段计划|
|`docs/runtime-wiring-changelog.md`|运行时接通（lifecycle/entrypoints/DeferredRegister/harddecode）改动记录|
|`docs/agents/A1-Spec/MG-Spec.md`|A1 规范|
|`docs/agents/A9-Report/PROJECT-STATUS.md`|A1–A9 进度看板|
|`docs/agents/A9-Report/DECISION-LOG.md`|决策日志|
|**`docs/改动登记表.md`**|**本轮正在改什么（规则 2.1）**|
|**`docs/已知bug.md`**|**尚未修复的 bug 与已知限制**|
|**`docs/更新日志.md`**|**每轮改动的追加记录**|
|**`docs/plans/`**|**大改动计划（规则 4）**|

## 5\. 目录外边界（重要）

仓库根是 `mirage-loader/`。它的**上级** `D:\\bing\\MC\\mcmoderr-my\\` 不是本仓库的一部分，里面是：

* `mc-shili/` —— **用户的 MC 游戏目录**（含 `versions/26.2-mirage/`、`mods/`、`libraries/`、`natives/`），启动脚本 gameDir 指向这里。**等同于原版规则里的 `my/`，未经用户同意不得改任何文件**（见规则 9）。
* `26.2/` —— vanilla 26.2 官方目录（PCL 安装的原版），不动。
* `refs/` —— fabric / neoforge 参考源码，只读对照，不进 git。
* `backups/` —— 历史快照，只读。
* `logs/latest.log` —— 运行日志，冒烟时读，不进 git。
* `启动 26.2-mirage.bat`、`崩溃\*.txt`、`.tmp\_\*` —— 根目录杂项，规则 7 清理对象。

\---

## 开发流程强制规则（2026-10-11 起，强制执行）

以下规则对每次会话、每次改动都生效，不得跳过。
（本规则由原锋语项目流程改造而来，路径与术语已对齐 Mirage Loader 现状。）

### 规则 0 · 新会话/子 agent 开工前必须先读本文件（前置门槛，最优先级）

**任何** agent——包括主 agent、被主 agent 派下去的子 agent、被另一个会话独立拉起的执行体——在对本仓库做任何写操作（改代码、下文件、挪 mod、建目录、git commit）之前，**必须**按顺序完成：

1. `Read` 本文件 `AGENTS.md` 全文；
2. `Read` `docs/改动登记表.md` 全文（看"进行中"有没有别人的活）；
3. `Read` `docs/已知bug.md` 全文；
4. `Read` `docs/更新日志.md` 顶部 1~2 条。

**没读这四份文件就动手 = 违规**，事后在更新日志里复盘。

主 agent 派子 agent 时的硬性义务：
* 在 `create_agent` 的 `hint` 参数里**必须**显式写一句："开工前先 `Read` `D:\bing\MC\mcmoderr-my\mirage-loader\AGENTS.md` 全文，并按规则 2.1 在 `docs/改动登记表.md` 登记。"
* 不要假设子 agent 会自己发现 AGENTS.md——它的工作目录、系统提示、历史上下文都可能不带这份文件。
* 如果子 agent 回报的动作看起来绕过了规则（比如直接往 `mc-shili/mods/` 扔 jar、没登记就 commit、`git add -A`），主 agent 必须停下来追问，不要默认它懂。

### 规则 1 · 需求复核与补丁方案

* 用户可能不专业，接到需求必须先**认真琢磨**其真实意图与可行性。
* 发现需求不合理、有歧义、与现有架构/约定冲突、或按字面执行会引入风险时，**不得直接照做**：
先向用户指出问题所在（哪一点不合理、为什么、会带来什么后果），并**主动给出一个补丁/替代方案**（推荐做法 + 理由），等用户确认后再动手。
* 只有在需求明确、可行、无歧义时才直接执行。

### 规则 2 · 改动前的前置检查

开始改任何代码/文档前，**必须按顺序**完成以下动作并在心里核对：

1. **查改动登记表 `docs/改动登记表.md`**：

   * 先看"进行中"列表：如果本轮要改的文件已有别人/上一轮未完成的改动在登记，**不要贸然改**，先报告用户，确认后再动手。
   * 确认无冲突后，**先登记**：在"进行中"表格加一行（日期、主题、涉及文件、状态=进行中），再开始改。
   * 改完提交后，把这行从"进行中"移到"已完成"，补上 commit 哈希。
2. **`git status`**（在 `mirage-loader/` 下执行）看是否有未提交改动。

   * 若工作区存在**不属于本轮任务**的未提交改动，先提示用户并询问如何处理（提交/暂存/保留）；不要在一堆混杂改动上叠新改动。
   * 若是上一轮自己刚做完还没提交的，**先把上一轮改动提交掉**再开新改动。
3. **阅读 `docs/已知bug.md`** 全文，确认本轮要改的文件/模块是否与已知 bug 相关，避免改出新问题或与未修复 bug 冲突。
4. **阅读 `docs/更新日志.md` 顶部最新 1\~2 条**，了解上一版本刚做了什么、动过哪些文件、用什么方式验证的。
5. **检查回归测试是否覆盖上次更新**：对照 `docs/更新日志.md` 最新条目里提到的新特性/修复点，确认是否已有对应 `src/test/` 用例或 mdk-example 冒烟步骤；**没有就先补**，不要在"上次改动根本没被回归覆盖"的基线上继续叠改动。

   * （注：本工程目前 `src/test/` 尚未建立，本条现阶段等价于"确认 mdk-example 或手工冒烟步骤能复现上次改动点"；一旦 `src/test/` 建立，立即切换为用例驱动。）
6. **基线确认（硬性）**：如果上一轮 commit 动过 `launcher/`、`core/mirage-loader/`、`core/mirage-mixin/`、`core/mirage-eventbus/`、`api/` 或 `modules/\*/` 的**类加载 / Mixin 织入 / Mod 描述符解析 / 事件总线 / entrypoint 引导**路径，本轮开始前**必须先跑一次** `gradle build` 并启动一次冒烟（`..\\启动 26.2-mirage.bat`），确认构建退出码 0、`..\\logs\\latest.log` 里 Mirage 加载链正常、MC 主类被反射调起，再开新改动。不要假设"上次应该修好了"——实测为准。
7. **反查已知 bug（提交前）**：提交前翻一遍 `docs/已知bug.md`，看本轮改动是否命中了其中任何一条的根因；如果是，**划掉那条**并在更新日志里注明"连带修复了 xxx"，不要让已修的 bug 长期挂在已知 bug 列表里。
8. **文件硬锁（多会话/多脚本并发时）**：当你可能与另一个会话、或与 `scripts/` 下的并发构建脚本同时改同一文件时，在软登记（第 1 条）之外**再加硬锁**：

```powershell
   $sid = (powershell -ExecutionPolicy Bypass -File .\\scripts\\flock.ps1 new).Trim()
   powershell -ExecutionPolicy Bypass -File .\\scripts\\flock.ps1 lock $sid <相对仓库根的文件路径>
   # ...改代码、构建、冒烟...
   powershell -ExecutionPolicy Bypass -File .\\scripts\\flock.ps1 unlock $sid <同上>
   powershell -ExecutionPolicy Bypass -File .\\scripts\\flock.ps1 logout $sid
   ```

   * 单会话、且 `flock.ps1 status` 显示无活动锁时，可以跳过硬锁，只做软登记。
   * 改完/会话结束**必须 logout**，否则锁会在 1800 秒后被自动 reap（注释里看得到）。
   * `docs/改动登记表.md` 里本轮行的"主题"后面附 `(sid=S001)` 便于追溯。

### 规则 3 · 信息分文件存放，不要塞一起

* **`AGENTS.md`**（本文件）：只放长期稳定的项目约定（构建命令、模块地图、文档索引、本规则全集、已修复 bug 的排错档案）。
* **`docs/已知bug.md`**：只放**尚未修复**的 bug 与已知限制；修完一条就划掉并在更新日志记一笔。
* **`docs/更新日志.md`**：每轮改动完成后**追加**一条（日期、范围、改动点、验证方式、涉及文件），不要改老条目。
* **每轮大改动的计划**：单独写 `.md` 文件（见规则 4）。
* 严禁把"已知 bug"、"本次改动记录"、"临时计划"混进同一份文件，也严禁把它们堆进 `AGENTS.md`。

### 规则 4 · 大改动必须先写计划

满足以下任一条件即视为"大改动"，**动手前必须先写一份计划 `.md`**（放 `docs/plans/` 下，命名如 `2026-10-11\_<主题>.md`）：

* 改动跨 3 个及以上模块 / 源文件；
* 新增/修改 `@Mod` / `@SubscribeEvent` / entrypoint / `mirage.mod.json` 字段 / Mixin 配置格式 / 类加载隔离规则 / DeferredRegister 语义；
* 新增可拔插模块、Gradle 插件行为、Fabric 兼容层映射规则；
* 预计超过 200 行代码改动，或需要多轮迭代。

**计划 `.md` 必须包含以下九节，少一节就算计划不合格**（直接复制这个骨架填；"合规边界"为条件必填，见下）：

```markdown
# <主题>
> 关联：<上游计划 / 复盘文档 / commit；没有就写"无">

## 目标
<一句话说清要达成什么，可验证。末尾给"可验证标准"：完成时怎么判断 done>

## 前置条件与阻塞项
<本计划依赖哪个上游计划/commit 先落地？当前工作区有没有别人/上一轮未提交的 WIP？现在能不能立刻开干，还是要等？
没有阻塞就写"无，工作区干净，可立即开干">

## 现状分析
<代码 as-is：现在是什么样、为什么要改、卡在哪、踩过什么历史坑。
注意：前置依赖/阻塞项不要塞在这里，归到上一节>

## 改动文件清单
<表格分两区块：先列"要改"，再单列"明确不做"，防止范围蔓延>
| 文件 | 改什么 |
|---|---|
| <要改的文件> | <改什么> |
> 明确不做：<列出本轮故意不碰的模块/文件/构建脚本，哪怕是相关的也写清楚>

## 每处改动要点
<逐文件写关键类/方法/字节码变化，不要只写"改 bug">

## 回归测试影响面
<风险分析：哪些旧行为可能被打破？要新增/修改哪些 src/test 用例或 scripts/*.ps1？
哪些模块零侵入？mdk-example 冒烟步骤要不要变？
注意：这一节写 what could break，执行命令归下一节>

## 验证步骤
<执行动作：具体 gradle 命令、启动后 logs/latest.log 里应看到什么关键字、人工冒烟点。
凡涉及启动 MC，必须显式提醒"遵守虚拟桌面规则，用户双击 bat，AI 不直接拉 java">

## 合规边界（条件必填）
<仅当改动涉及 modules/mirage-neoforge-compat/、modules/mirage-forge-compat/、
或引用 refs/neoforge/、refs/forge/ 源码时必填。
写清楚：NeoForge/Forge 是 LGPL 2.1，本计划只做行为观察/接口枚举/clean-room 实现，
不抄其实现代码。其他计划（纯 MG 原生、Fabric 兼容层）此节可写"不涉及 LGPL 代码">

## 回滚方案
<万一坏了，git revert 哪个 commit？保留哪些已修好的部分？
有没有 jar/文件级备份？回滚后按规则 6 写回退报告>
```

**与旧七节骨架的差异**（2026-10-18 修订）：
- 新增 **前置条件与阻塞项**：多会话并行项目里，这一节决定能不能立刻开干，不能再塞在现状分析里。
- 新增 **合规边界**：兼容层计划的 LGPL 红线，必须显式声明。
- **改动文件清单**强制分"要改 / 明确不做"两区块，防止范围蔓延。
- 头部加 **关联** 行，指向上游计划/复盘文档。
- 各节加注释明确分工，避免"回归测试影响面"和"验证步骤"重复。

小改动（单文件、<50 行、纯文案/注释）可直接改，不必写计划。

### 规则 5 · 每轮改动完成后必须提交

* 一轮改动完成、`gradle build` 通过、冒烟确认后，**立即** `git add` 相关文件并 `git commit`，提交信息用 conventional 风格（`feat:` / `fix:` / `chore:` / `docs:` / `refactor:` 前缀），一句话讲清做了什么。
* 不要把多轮不相关的改动攒成一个提交。
* 提交前确认：没有把 `build/`、`.gradle/`、`run/`、`local.properties`、`run-m3.log` 等产物/日志误加进去（参考 `.gitignore`）。
* 提交后在 `docs/更新日志.md` 顶部追加对应条目（若适用），并把 `docs/改动登记表.md` 里本轮行从"进行中"移到"已完成"补上 commit 哈希。

### 规则 6 · 回退代码必须写回退报告

* 任何 `git revert` / `git reset --hard` / 手动撤销已提交改动的动作，**都必须**在 `docs/` 下写一份回退报告 `.md`（命名如 `回退报告\_YYYY-MM-DD\_<主题>.md`），至少包含：

  * **回退原因**（为什么必须回退，触发了什么现象/崩溃/回归）；
  * **回退范围总结**（哪些 commit / 文件 / 行被撤掉，保留了哪些）；
  * **后续打算**（是永久放弃、还是等条件成熟后重新实现）。
* 动手回退前**必须先确认**：要回退的 commit 是否包含上一版本（或最近几次更新）引入的内容。

  * 如果**包含**上次更新的内容：尽量不要直接回退，先尝试**补全/修复**（把坏的部分修掉、把好的部分留下），在更新日志里记录；
  * 如果**确实必须整体回退**（比如回退会连带上次更新），在回退报告里明确写出"连带回退了哪些上次更新的特性"，并在 `docs/更新日志.md` 追加一条回退条目。
* 严禁静默 `git reset --hard` 或 `git checkout .` 丢掉工作区改动而不留任何记录。

### 规则 7 · 结束对话前整理现场

每次会话结束（或告一段落）前，**必须**做以下清理：

1. **删除临时调试文件**（在仓库**外部** `D:\\bing\\MC\\mcmoderr-my\\` 根目录）：`.tmp\_build/`、`.tmp\_classes/`、`.tmp\_cls/`、`.tmp\_jarwork/`、`.tmp\_vpr2/`、`.tmp\_disasm.txt`、`.tmp\_screen\*.png`、`.tmp\_vpr.txt`、`崩溃\*.txt`、一次性复现脚本（确认无保留价值后删；拿不准的留着并说明）。
2. **构建中间产物**：`mirage-loader/\*\*/build/`、`.gradle/`、根目录 `logs/` 里被新一次冒烟顶掉的旧日志——这些**不进 git**（已在 `.gitignore`），若对应样例已验证通过，下次构建前会被覆盖，不必手动删；但实验结束后不再对应的旧 `build/` 目录可清。
3. **补全回归测试**：本轮新引入的特性 / 修过的 bug，若还没有对应的 `src/test/` 用例或 mdk-example 冒烟步骤，**当场补上**，不要把"待补测试"拖到下次。
4. **不要把文件堆在仓库根**：新脚本放 `scripts/`（待建）或对应模块下，新文档放 `docs/`，新样例放 `mdk/` 或 `modules/` 对应子目录；仓库根只留 `build.gradle.kts` / `settings.gradle.kts` / `AGENTS.md` / `README.md` / `PLAN.md` / `.gitignore`。
5. 整理完后再 `git status` 看一眼，确认工作区干净或仅剩明确说明的未提交项。
6. **注意**：本规则的清理范围**不包括 `mc-shili/`**（见规则 9）。

### 规则 8 · （已废弃，本规则原面向锋语项目的"桌面版导出函数中英双语"要求，在 Mirage Loader 无对应物）

* 保留条目编号以兼容老引用；本工程**没有**桌面版、没有 `gui\_map.rs`、没有 C FFI 导出层，本规则**不生效**。
* 若未来 Mirage 暴露面向用户的公共 API（`api/` 下的 `@Mod` / 事件类 / `DeferredRegister`）需要做双语文档注解，另立规则，不在此处默认生效。

### 规则 9 · `mc-shili/` 是用户游戏目录，未经同意不得修改

* **`mc-shili/` 目录是用户本人的 MC 运行沙盒**（含 `versions/26.2-mirage/`、`mods/`、`libraries/`、`natives/`、`options.txt` 等），里面的文件归属用户本人，AI 在任何情况下都不得擅自创建、修改、删除、重命名、移动其中任何文件或子目录。
* 包括但不限于：不要为了"整理现场"去清 `mc-shili/` 下的日志、截图、shaderpacks、saves；不要把用户丢在 `mods/` 里的测试 mod 挪到 `mdk/`；不要改 `versions/26.2-mirage/\*.json` 配置。
* **唯一例外**：用户在本轮对话中明确说"删掉 mc-shili/ 下的 xx"或"清理 mc-shili/"时，才可以按用户指定的范围操作；操作前先列出要删的文件清单让用户确认（除非用户已明确说"全部删掉"）。
* `mc-shili/` 下的产物也**不要 `git add`**——它在仓库外，本来就不进版本库。
* 这条优先级高于规则 7 的"结束前整理现场"——规则 7 的清理范围**不包括 `mc-shili/`**。
* 同样受保护的还有：`26.2/`（vanilla 官方目录）、`refs/`（只读参考源码）、`backups/`（历史快照）。

