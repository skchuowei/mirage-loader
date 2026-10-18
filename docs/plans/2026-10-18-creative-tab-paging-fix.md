# 创造模式标签页分页功能修复 · 实施计划

> 日期：2026-10-18
> 关联复盘：`docs/创造模式标签页分页-复盘.md`

---

## 目标

让创造模式物品栏的标签页分页功能**在真实游戏里验证通过**：
1. 标签总数超过一行7个槽位时，GUI两侧正确显示翻页箭头和页码；
2. 点击翻页按钮能正常切换页面，**所有标签不再全部重叠在第0列**，而是按0-6列正确分布；
3. 启动过程不崩溃，不影响之前已经修好的 Mixin 加载、DeferredRegister 注册、NeoForge 识别、MidCache 等所有现有功能；
4. 全程遵守虚拟桌面启动规则，不手动启动游戏进程。

---

## 现状分析

### 1. 代码状态（之前回滚留下的"半拉子"状态）
- `MirageTransformer.java` 里完整保留了分页相关的字节码织入逻辑：
  - 织入 `extractTabButton()`：开头调用 `MirageDebug.shouldDrawTab(tab)`，不可见的tab直接return不绘制；
  - 织入 `extractTabButton()`：替换原有的列号赋值，调用 `MirageDebug.remapColumn(tab)`；
  - 织入 `getTabX()`：替换返回值，调用 `MirageDebug.remapTabX(tab)`；
  - 织入 `extractBackground()`：绘制翻页按钮和页码，调用 `MirageDebug.drawTabPageControls(...)`；
  - 织入鼠标事件：调用 `MirageDebug.handlePageClick(...)` 处理翻页按钮点击。
- 但 `MirageDebug.java` 在最新提交（8e46669 写复盘文档时）把**整段分页实现代码全部删掉了**，以上方法一个都不存在。
- 这意味着：只要游戏一加载创造模式物品栏，就会立刻抛 `NoSuchMethodError` 崩溃——这就是之前"改完即崩"的真实原因，之前误以为是fat jar打包问题，实际上回滚时删干净了实现端，只留了织入端。

### 2. 已知的逻辑bug（复盘里已经定位清楚）
- `getDisplayColumn()`：反射读取tab列号失败时原来返回`0`，把"反射失败/不可见"和"第0列"这两个完全不同的语义混在一起，导致非当前页的tab全部被识别为第0列；
- `remapColumn()`：原来把负列号强制归零`if (c < 0) c = 0;`，进一步把所有不可见tab叠死在第0列。

### 3. fat jar现状
- 当前部署的 `mirage-launch-0.1.0.jar`（1.9MB，2026-10-18 11:14）是已经修复了Mixin服务加载问题、能正常启动到主菜单的版本；
- 历史上踩过手动打包fat jar的坑：漏`META-INF/services/`注册文件、漏asm-util依赖、签名文件没清理，导致`transformer is null`、启动崩溃；
- 本次改动**不重新打整个fat jar**，只编译单个class文件热替换进去，避免再次踩手动打包的坑。

### 4. 历史遗留
- 分页逻辑从项目初始提交写好之后，**从来没有在游戏里真正跑通过一次**——写完就遇到fat jar问题，回滚时把实现删了，属于"只做了一半"的功能。

---

## 改动文件清单

| 文件 | 改动类型 | 说明 |
|---|---|---|
| `core/mirage-loader/src/main/java/io/mirage/loader/MirageDebug.java` | 加回代码+修bug | 把被删掉的分页静态方法加回来，同时修复两个语义错误 |
| `mc-shili/libraries/io/mirage/mirage-launch/0.1.0/mirage-launch-0.1.0.jar` | 热替换单个class | 只替换编译后的MirageDebug.class，不动jar里其他任何文件 |
| `docs/改动登记表.md` | 登记 | 在"进行中"表格加一行本次改动 |
| `docs/更新日志.md` | 追加 | 验证通过后追加本次修复记录 |

> 不改动 `MirageTransformer.java`、不改动任何构建脚本、不碰其他模块的代码。

---

## 每处改动要点

### 1. MirageDebug.java 加回分页代码
在 `private MirageDebug() {}` 之前，把初始提交（7f4c2ea）里完整的分页代码段恢复回来，包含：
- 静态状态：`tabPage`（当前页）、`TABS_PER_PAGE=7`、反射缓存字段、`sortedTop/sortedBottom`、`totalPages`；
- 反射初始化方法 `ensureTabReflect()`；
- 页数计算方法 `rebuildTabPages()`；
- 翻页方法 `flipPage()`；
- 翻页控件绘制方法 `drawTabPageControls()`；
- 翻页点击处理方法 `handlePageClick()`。

### 2. 修复两个核心bug
| 方法 | 原错误逻辑 | 修复后逻辑 | 语义说明 |
|---|---|---|---|
| `getDisplayColumn(Object tab)` | catch分支`return 0;` | catch分支`return -1;` | -1表示"不可见/反射失败"，和合法列号0严格区分 |
| `remapColumn(Object tab)` | `if (c < 0) c = 0;` | `if (c < 0) return 0;` | 能走到这个方法的tab已经被`shouldDrawTab`过滤过（c<0的直接被return不绘制），这里c<0属于兜底情况，直接返回0即可，不再把所有负列号强行映射到第0列 |

### 3. 其他逻辑保持原样
不重构、不优化分页逻辑本身（比如不抽纯函数、不写单元测试），先确保功能能在游戏里跑通、行为正确，后续再做重构。

### 4. 热替换class
- 用gradle只编译`mirage-loader`模块，拿到编译生成的 `MirageDebug.class`；
- 直接用zip命令把这个class写入fat jar内的 `io/mirage/loader/MirageDebug.class` 路径，覆盖旧文件；
- 不重新打包、不解压整个jar、不改动jar里其他任何文件（META-INF/services、依赖jar、其他class全部保持原样）。

---

## 回归测试影响面

1. **不影响现有已验证功能**：
   - 所有改动都集中在MirageDebug类里新增几个静态方法，之前已经修好的Mixin加载、DeferredRegister注册、NeoForge识别、MidCache缓存、资源包暴露等逻辑完全不碰；
   - 之前因为方法缺失导致的`NoSuchMethodError`会直接消失，启动链路恢复正常。
2. **新增行为验证点**：
   - 启动日志里应该能看到`[Mirage] 标签页反射就绪`、`[Mirage] 重建标签页: 最大列=X totalPages=Y`两行输出；
   - 进入创造模式物品栏时不崩溃，tab绘制正常；
   - tab数>7时GUI两侧出现翻页箭头和页码数字；
   - 点击翻页箭头后页面切换，tab不再重叠。
3. **无旧行为破坏**：原来没有分页的时候，tab超过7个会跑出GUI数组越界，现在分页逻辑会把越界的tab隐藏，反而解决了潜在的数组越界崩溃问题。

---

## 验证步骤

### 步骤0：前置备份（必须做，避免之前"没备份"的坑）
```powershell
Copy-Item "D:\bing\MC\mcmoderr-my\mc-shili\libraries\io\mirage\mirage-launch\0.1.0\mirage-launch-0.1.0.jar" "D:\bing\MC\mcmoderr-my\mc-shili\libraries\io\mirage\mirage-launch\0.1.0\mirage-launch-0.1.0.jar.bak-20261018-pagingfix"
```
改坏了直接复制这个备份覆盖回去，1秒回滚。

### 步骤1：代码修改
按上面的改动要点修改 `MirageDebug.java`。

### 步骤2：编译验证
```powershell
Set-Location D:\bing\MC\mcmoderr-my\mirage-loader
gradle :core:mirage-loader:build -x test
```
确认BUILD SUCCESSFUL，没有编译错误。

### 步骤3：热替换class
从 `core\mirage-loader\build\classes\java\main\io\mirage\loader\` 找到生成的 `MirageDebug.class`，用zip命令更新进fat jar，不碰其他文件。

### 步骤4：启动冒烟（遵守虚拟桌面规则）
不主动启动游戏，提示用户双击根目录的`启动 26.2-mirage.bat`（通过Win+R启动进虚拟桌面），启动完成后读取 `D:\bing\MC\mcmoderr-my\logs\latest.log`，确认：
- 没有`NoSuchMethodError`、`StackOverflowError`、Mixin服务相关错误；
- 能看到"标签页反射就绪"、"重建标签页"的日志行；
- 日志末尾正常进入Minecraft主菜单，没有崩溃退出。

### 步骤5：游戏内功能验证
用户进入创造模式物品栏，确认：
1. 标签栏正常显示，没有重叠；
2. 标签数>7时，GUI左右两侧出现灰色翻页箭头和`当前页/总页数`的黄色页码；
3. 点击右箭头，页面切换到下一页，显示下一组7个标签，每个标签位置正确分布，不重叠；
4. 点击左箭头，能切回上一页；
5. 翻页过程中游戏不崩溃、不闪屏。

---

## 回滚方案

1. **快速回滚（jar层面）**：如果启动崩溃，直接把备份的 `mirage-launch-0.1.0.jar.bak-20261018-pagingfix` 复制覆盖当前的 `mirage-launch-0.1.0.jar`，立刻回到改之前能正常启动主菜单的状态。
2. **代码层面回滚**：`git checkout -- core/mirage-loader/src/main/java/io/mirage/loader/MirageDebug.java`，回到HEAD版本（没有分页实现、但能正常编译运行的状态）。
3. **边界保护**：
   - 不删除、不修改 `mc-shili/` 下除了这次备份的jar之外的任何文件；
   - 不清理gradle缓存、不手动重新打整个fat jar、不碰META-INF目录；
   - 不做任何额外优化、不引入shadow插件、不重构现有分页逻辑，先把功能跑通再说。
