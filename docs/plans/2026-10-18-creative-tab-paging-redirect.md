# 创造模式标签页分页（@Redirect 落地路线）

- **日期**：2026-10-18
- **关联**：交接文档 `2026-10-18-creative-paging-handover.md`（上游需求）；本目录 `2026-10-18-creative-tab-paging-mixin.md` / `2026-10-18-creative-tab-paging-fix.md`（前两轮失败方案，本计划取代之）。
- **一句话**：只对 `CreativeModeInventoryScreen` 写一个 Mixin，用 4 个注入点（1×`@Inject` init + 1×`@Inject` cancel + 2 组 `@Redirect`）实现分页，不再用 Accessor 改 `CreativeModeTab` 字段。

---

## 1. 目标

在 Mirage Loader（独立 MC 26.2 加载器，JDK 25）上，让创造模式物品栏的标签页支持分页，行为对齐 NeoForge：

1. 原版全部标签在原位、不消失、不位移；
2. 装了多标签 mod（标签总数 > 一页容量）后，GUI 左右两侧出现 `<` `>` 翻页按钮；
3. 点 `<`/`>` 切换页面：非当前页标签隐藏，切回来恢复；
4. 不出现 `Not bootstrapped`、`MixinTransformerError`、任何栈异常。

## 2. 前置条件与阻塞项

- **已确认的根因**（前一轮实测）：部署的 fat jar 内 `mirage.mixins.json` 的 `client` 数组是空的，且 jar 内根本没有 `CreativeModeInventoryScreenMixin.class`。此前所有"Accessor 版本"测试都是在 `defaultRequire:0` 下**静默空转**，从未真正织入。
- **阻塞项**：`mirage-mixin` 模块当前编译不过——旧 `CreativeModeInventoryScreenMixin.java` `import io.mirage.loader.MirageDebug`，但 `core/mirage-mixin` 与 `core/mirage-loader` 是**反向依赖**（loader 依赖 mixin），不能引用 `MirageDebug`。本计划重写该文件即解除。
- 工作区有他人 WIP：`Launcher.java`、`MirageTransformer.java`、已删除的 `MirageDebug` 等（未提交）。本轮**只 add 本轮文件**。
- 游戏只能由用户双击 `启动 26.2-mirage.bat` 启动；不能从 PowerShell 拉 java。
- 编译环境：`D:\bing\MC\mcmoderr-my\.gradle-dist\gradle-9.1.0\bin\gradle.bat`，`JAVA_HOME=C:\Program Files\Java\jdk-25.0.4`。

## 3. 现状分析（javap 实据）

### 3.1 双转换冲突（决定技术路线的关键事实）

- `MirageTransformer.transform()` 对 `net/minecraft/world/item/CreativeModeTab` 做 ASM 注入（补无参 `builder()`），**同时**旧的 `CreativeModeTabAccessor` 是同类上的 Mixin → 正是 `AGENTS.md` §9 记载的「自研 ASM 先改字节码，再交给 Mixin」双转冲突（`SharedConstants` 是同款失败案例）。
- `MirageTransformer` 对 `net/minecraft/client/gui/screens/inventory/CreativeModeInventoryScreen` **显式 `return original`**（不修改），所以该类的 Mixin **安全**。
- 织入顺序（`MirageClassLoader.findClass`）：先 `MirageTransformer`（ASM），后 `MirageMixinBootstrap.transform`（Mixin）。
- **结论**：绝不对 `CreativeModeTab` 下 Mixin。`column()`/`row()`/`isAlignedRight()`/`shouldDisplay()` 在 26.2 全是 **public**，不需要 Accessor 即可调用。

### 3.2 目标类签名（`CreativeModeInventoryScreen`）

- `protected void init()`；
- `private int getTabX(CreativeModeTab)` —— 内部 `CreativeModeTab.column()I` **只有 1 处**调用（offset 1），配合 `isAlignedRight()`：
  - 非右对齐：`x = 27 * column`；右对齐：`x = imageWidth - 27 * (7 - column) + 1`；
- `private int getTabY(CreativeModeTab)` —— 内部 `CreativeModeTab.row()` **1 处**；
- `protected void extractTabButton(GuiGraphicsExtractor, int, int, CreativeModeTab)` —— `row()` 1 处（选 TOP/BOTTOM 精灵 + y）、`getTabX` 1 处（x）、`column()` 1 处（精灵索引 `Mth.clamp(column, 0, 7)`）；
- `private void selectTab(CreativeModeTab)`；`private static CreativeModeTab selectedTab`；
- `checkTabClicked` / `checkTabHovering` 都经 `getTabX`/`getTabY` → **离屏坐标天然不可点、不可悬停**；
- 父类 `AbstractContainerScreen`：`protected int leftPos/topPos`、`protected final int imageWidth/imageHeight`；
- `Screen`：`protected <T ...> T addRenderableWidget(T)`、`protected final Minecraft minecraft`。

- `Mth.clamp(int value, int min, int max)`；tab 精灵数组长度 = 7，索引必须落在 0..6（`clamp(column,0,7)` 对 column≥7 会返回 7 → `aaload` 越界崩溃，必须由我们改写索引）。

### 3.3 vanilla 26.2 布局事实

- `CreativeModeTabs.tabs()` = `BuiltInRegistries.CREATIVE_MODE_TAB.stream().filter(shouldDisplay).toList()`，**按注册顺序**。
- 原版 14 个 tab 占满两行 ×7 列：
  - TOP：building 0 / colored 1 / natural 2 / functional 3 / redstone 4 / hotbar 5（右对齐）/ search 6（右对齐）
  - BOTTOM：tools 0 / combat 1 / food 2 / ingredients 3 / spawn_eggs 4 / op_blocks 5（右对齐）/ inventory 6（右对齐）
- `isAlignedRight()` 读实例布尔字段，**只有上述 4 个为 true**（hotbar / search / op_blocks / inventory）。
- 注册顺序：按 `CreativeModeTabs.<clinit>` 里 `ResourceKey` 的声明顺序 → 过滤掉 4 个右对齐后前 10 个恰好是 `building..redstone`（TOP，列 0-4）+ `tools..spawn_eggs`（BOTTOM，列 0-4）。

### 3.4 NeoForge / Fabric 参考（`refs/`，clean-room 只读，不抄代码）

- NeoForge 打补丁直接改 `CreativeModeInventoryScreen`：每页 **10 个** tab（`maxLength=10`，`topLength=5`），`CreativeTabsScreenPage` 内部把页内 index 映射为：`<5` → TOP 行 `column = index`；`≥5` → BOTTOM 行 `column = index-5`。右对齐的 4 个"默认 tab"永远可见。
- 由此可得：**页 0 = 原版那 10 个普通 tab = 与 vanilla 完全一致**；mod tab 进页 1+；4 个右对齐 tab 常驻。

## 4. 改动文件清单

### 要改

- `core/mirage-mixin/src/main/java/io/mirage/mixin/CreativeModeInventoryScreenMixin.java` —— 整文件重写（见第 5 节）；
- `core/mirage-mixin/src/main/resources/mirage.mixins.json` —— `client` 只留 `CreativeModeInventoryScreenMixin`，`defaultRequire` 改 1；
- `core/mirage-mixin/src/main/java/io/mirage/mixin/MirageMixinBootstrap.java` —— catch 块补 `th.printStackTrace()`（`AGENTS.md` §8）；
- `core/mirage-mixin/build.gradle.kts` —— 加 `compileOnly(26.2.jar)`、本模块 toolchain/release 覆盖为 25；
- 删除 `core/mirage-mixin/src/main/java/io/mirage/mixin/CreativeModeTabAccessor.java`；
- 删除 `core/mirage-mixin/src/main/java/io/mirage/mixin/CreativeModeTabsAccessor.java`（`tabs()` 已是 public，用不上）；
- `mc-shili/libraries/io/mirage/mirage-launch/0.1.0/mirage-launch-0.1.0.jar` —— 用 `jar uf` 热替换 `CreativeModeInventoryScreenMixin.class` + `MirageMixinBootstrap.class` + `mirage.mixins.json`。

### 明确不做

- **不对 `CreativeModeTab` 下任何 Mixin / Accessor**（双转冲突，§3.1）；
- **不改 `MirageTransformer`**（含其已禁用的 `transformCreativeInventory` 死代码）；
- **不画自定义翻页按钮**，用原版 `Button`；
- **不做页码文字**（交接文档只要求按钮，少一个注入点少一分风险）；
- **不动 `refs/`、不动 `mc-shili` 其它文件**。

## 5. 每处改动要点

### 5.1 `CreativeModeInventoryScreenMixin`（`@Mixin(targets="net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen", remap=false)`）

状态（`@Unique` 静态字段）：`currentPage`、`pageCount`、`paged`（`List<CreativeModeTab>`）、`index`（`IdentityHashMap<CreativeModeTab,Integer>`）。

6 个注入点：

1. `@Inject(method="init", at=@At("TAIL"), require=1)` —— **非静态**（要 `addRenderableWidget` / `leftPos`）：
   - `paged = CreativeModeTabs.tabs().stream().filter(t -> !t.isAlignedRight()).toList()`；
   - 建 `index`；`pageCount = max(1, ceil(paged.size()/10))`；`currentPage = 0`；
   - 若 `pageCount > 1`：`addRenderableWidget(Button.builder(<, b -> switchPage(-1)).bounds(leftPos-20, topPos+10, 18, 12).build())` 与 `>` `bounds(leftPos+imageWidth+2, topPos+10, 18, 12)`；
   - 打印唯一标记：`[Mirage-Mixin] 创造标签分页已生效 pageCount=... paged=... tabs=...`；
   - 整个 body 包 try/catch + `printStackTrace`。
2. `@Inject(method="extractTabButton", at=@At("HEAD"), cancellable=true, require=1)` —— 非当前页 tab 直接 `ci.cancel()`（右对齐 tab 永不取消）。
3. `@Redirect(method="getTabX", at=INVOKE target="Lnet/minecraft/world/item/CreativeModeTab;column()I", require=1)` → `mirage$columnInGetTabX(tab)`；
4. `@Redirect(method="extractTabButton", ...同上...)` → `mirage$columnInExtractTabButton(tab)`；
5. `@Redirect(method="getTabY", at=INVOKE target="Lnet/minecraft/world/item/CreativeModeTab;row()...Row;", require=1)` → `mirage$rowInGetTabY(tab)`；
6. `@Redirect(method="extractTabButton", ...同上...)` → `mirage$rowInExtractTabButton(tab)`。

映射逻辑（4 个 redirect handler 都委托到两个 helper）：

```
column(tab):
  右对齐        → tab.column()                  // 常驻，位置不变
  i = index(tab)
  i/10 != currentPage → -100                    // 离屏 → getTabX 得 x=-2700，不可点/不可悬停
  否则          → (i % 10) % 5                  // 页内列 0..4（精灵索引安全）
row(tab):
  右对齐        → tab.row()
  否则 (i % 10) < 5 ? Row.TOP : Row.BOTTOM
```

- **@Redirect handler 约定**（由离线 harness 实测锚定）：静态/非静态均可，但**第一个参数是被调用方法的接收者**（`column()` 无其它参数 → `(CreativeModeTab tab)`）。这里四个 handler 都写成**非静态**、签名 `(CreativeModeTab)`，与 harness 里 `private int mirage$redirectComputeColumn(probe.Target self)` 一致。
- 必须拆成 4 个不同 handler 方法（一个 handler 只挂一个 `@Redirect`）。

切页（按钮回调）：`currentPage += d; 若新页不含 selectedTab 则 selectTab(该页第一个 tab)`。用 `@Shadow` 的 `selectedTab`（`private static CreativeModeTab`）与 `selectTab`（`private/protected abstract void selectTab(CreativeModeTab)`，Fabric 同款写法；harness 已证明 `@Invoker`/`@Shadow` 在 abstract mixin 上可用）。

### 5.2 `mirage.mixins.json`

`client: ["CreativeModeInventoryScreenMixin"]`；`injectors.defaultRequire: 1`（强制"注入点找不到就报错"，避免又一次静默空转）。

### 5.3 `MirageMixinBootstrap`

`transform()` 的 catch 块由 `System.err.println(...)` 增补 `th.printStackTrace()`。

### 5.4 `build.gradle.kts`

```kotlin
dependencies { compileOnly(files("../mc-shili/versions/26.2/26.2.jar")) ... }
java { toolchain { languageVersion = JavaLanguageVersion.of(25) } }
tasks.withType<JavaCompile>().configureEach { options.release = 25 }
```

## 6. 回归测试影响面（可能坏什么）

- **织入失败 = 打开创造栏崩溃**：`require=1` 让注入点缺失时报 `MixinTransformerError`（响亮失败，好过静默）。用离线 harness 预检挡在用户启动之前。
- **改 `tabs()` 顺序假设错误** → 页 0 与 vanilla 不一致（原版 tab 位移）。用 init 标记日志打印 `paged` 数与 tab 总数即可初判；必要时打印页 0 名字。
- **`@Shadow` 的 `selectedTab`/`selectTab` 签名写错** → 静默失败/编译错。Fabric 同款签名已核对。
- **mod tab 真实 `column ≥ 7`**：只要在页就返回 0..4、离页就 cancel，绝不把原 column 交给 `extractTabButton` 的精灵索引 → 不越界。
- **只影响创造模式物品栏**，不触及其它 Screen / 注册表 / 启动管线。
- `mc-shili` fat jar 改动仅限 3 个条目热替换，可回滚（备份 `mirage-launch-0.1.0.jar.bak-20261018-pagingfix`）。

## 7. 验证步骤

1. `gradle :core:mirage-mixin:build`（必要时 `:core:mirage-loader:build`）BUILD SUCCESSFUL；
2. **离线 harness 预检**（`.tmp_harness/`）：用 `26.2.jar` + fat jar 建 `MirageClassLoader` → `MirageMixinBootstrap.init(mcl, List.of("mirage.mixins.json"))` → `Class.forName("net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen", false, mcl)`，确认**不抛 MixinTransformerError**，再反射确认混入的静态字段/方法存在；
3. `jar uf` 部署 3 个条目；`jar tf` + 解出 json 核对 `client` 数组与 jar 内 class 一一对应（`AGENTS.md` 规则 5）；
4. 请用户双击 `启动 26.2-mirage.bat`；读 `logs/latest.log` grep `[Mirage-Mixin] 创造标签分页已生效`，确认无 `MixinTransformerError`；
5. 进创造模式物品栏：原版 tab 在位 → 装多标签 mod 后出现 `<` `>` → 翻页/切回正常；
6. `gradle build` → `git commit` → `git push`；`docs/更新日志.md` 顶部追加；登记表移「已完成」补哈希；删 `.tmp_*`。

## 8. 合规边界

- `refs/` 下 NeoForge（LGPL）/ Fabric 源码**只做行为观察与 clean-room 复刻**，不逐行抄写；本实现为独立编写的 Mixin，算法来自公开行为描述（每页 10 个：上行 5、下行 5；右对齐 4 个常驻）。
- 不修改、不反编译 `mc-shili` 内的其它文件；仅按交接文档约定的 3 个条目热替换。

## 9. 回滚方案

- 源码：`git checkout -- core/mirage-mixin/**`（本轮改动集中在 mixin 模块）；
- fat jar：`Copy-Item mirage-launch-0.1.0.jar.bak-20261018-pagingfix mirage-launch-0.1.0.jar`；
- 若线上仍崩：把 `mirage.mixins.json` 的 `client` 数组清空（等价于"未启用分页"的旧状态），游戏其余功能不受影响。
