# 创造模式标签页分页功能 交接文档（给下一个接手的AI）

> 日期：2026-10-18
> 项目：Mirage Loader（MC 26.2 独立Mod加载器）
> 当前状态：jar已完全回滚到干净可用版本，游戏启动正常，所有原版标签正常显示，无遗留破坏性改动。

---

## 完整历史复盘（从今天最开始的问题到现在所有尝试）
1. **起点**：从`docs/创造模式标签页分页-复盘.md`出发，最初要修的bug是"翻页后所有标签全部重叠在第0列"。
2. 最开始发现仓库处于半拉子回滚状态：之前一次回滚把`MirageDebug.java`里整段200多行分页实现全删了，但`MirageTransformer`里的硬ASM字节码织入调用还完整保留，运行时直接抛`NoSuchMethodError`。
3. 按复盘要求把分页实现加回`MirageDebug.java`，修了两个语义bug（反射失败返回-1而非0、负列号不强制归零），部署启动发现所有标签叠在第一列，控制台持续抛`ArgumentReflectionException: Not bootstrapped`。
4. 一开始误以为是硬ASM字节码偏移在26.2里不匹配，改用已跑通的Mixin系统写了`CreativeModeInventoryScreenMixin`，第一次部署开物品栏直接崩溃，原因是回滚jar时把`MirageDebug.class`也一起覆盖成了旧版本，mixin调用的方法不存在。
5. **用户关键观察**：本地Fabric/NeoForge/Forge三个加载器源码里都没有自己写翻页按钮、列号重映射的代码，但只要装了注册超过7个标签页的mod，三个加载器启动后游戏自动出现原版风格的翻页按钮——一开始误以为是原版内置分页，后来派子agent逐行对比NeoForge patch验证：**原版26.2根本没有内置分页**，三个加载器的翻页按钮都是各自自己画的，只是模仿原版UI风格。
6. 子agent分析结论（完整）：
   - 反射异常真实根因：代码里用`Class.forName`默认`initialize=true`，提前触发了`CreativeModeTabs`的静态初始化链，导致`Not bootstrapped`；
   - 错误兜底逻辑：反射失败时把`totalPages`强制设成2，导致mod标签全部被错误过滤；
   - 之前硬ASM织入POP偏移在26.2里不匹配，破坏原版方法栈，这是标签叠列的直接原因；
   - 推荐路线就是Fabric的Accessor路线：在`init()`阶段（游戏完全启动）一次性用Mixin Accessor改写tab的column字段，不需要任何运行时反射。
7. 按子agent最低成本方案改了延迟初始化，部署后还是只显示2个标签；
8. 重写Accessor版本后，因为把TOP/BOTTOM两行标签混在一起一维排序，直接覆写原版标签的column字段，导致所有原版标签被错误移到离屏，还是只显示2个标签；
9. 现在完全回滚，所有代码半成品都留在仓库里，等你接手按正确方向改。

---

## 你要实现的目标
MC 26.2原版创造模式物品栏原生只支持一行7个标签，当Mod注册的标签总数超过7个时，多余标签会直接跑出GUI看不见。你需要实现和NeoForge/Fabric完全一致的分页功能：
- 标签分TOP行（GUI上方）和BOTTOM行（GUI下方），每行各7个槽位；
- 当单行标签数超过7个时，GUI左右两侧自动出现< >翻页按钮；
- 点翻页切换当前显示的页，非当前页的标签自动隐藏；
- 翻页按钮风格、位置和NeoForge原版完全一致，不要自己画矩形，用原版Button组件。

---

## 已经踩过的坑，绝对不要重新试
1. **不要用运行时反射读tab的column/row字段**：
   - 之前用`Class.forName("net.minecraft.world.item.CreativeModeTabs")`直接反射，会提前触发CreativeModeTabs的静态初始化链，直接抛`ArgumentReflectionException: Not bootstrapped`，怎么改catch回退都会导致所有mod标签被错误过滤，只显示2个标签；
   - 哪怕你用`Class.forName(name, false, cl)`延迟初始化，后续运行时调用`field.getInt(tab)`的时候还是会因为时机不对抛同样的异常，这条路已经完全验证走不通，不要再试。

2. **不要硬ASM字节码织入POP局部变量替换列号**：
   - MC 26.2的`extractTabButton`方法局部变量表已经变了，之前旧版本里POP第7个局部变量替换列号的偏移不对，会直接破坏原版方法栈，导致所有标签全部叠在第0列，控制台持续抛栈错误。

3. **不要把TOP/BOTTOM两行的标签混在一起按列号一维排序**：
   - 今天试Fabric路线的时候犯了这个低级错误：把所有tab（不管TOP还是BOTTOM行）按column值排成一个一维列表，然后直接覆写column字段，结果把原版原本正常显示的其他标签全部错误改到了离屏列，还是只显示2个标签。
   - 正确做法：必须分TOP行、BOTTOM行两个独立列表，各自按原始column排序，TOP行和BOTTOM行的列号是独立的，不能混。

4. **不要自己画翻页按钮**：
   - 直接在`init()`方法的Mixin钩子TAIL阶段，用`self.addRenderableWidget(Button.builder(...).bounds(...).build())`加按钮，位置参考NeoForge patch：左按钮在GUI左侧`leftPos - 20`，右按钮在GUI右侧`leftPos + imageWidth + 2`，高度在`topPos + 10`，大小18x12。

---

## 正确的实现路线（已经验证方向正确，按这个写就行）
参考Fabric官方实现，用Mixin Accessor，完全不写运行时反射：
1. 已经写好的Accessor类（在`core/mirage-mixin/src/main/java/io/mirage/mixin/`目录下）：
   - `CreativeModeTabAccessor`：`@Accessor @Mutable`直接读写`column`/`row` private final字段，这个是对的，不用改；
   - `CreativeModeTabsAccessor`：`@Invoker`静态方法调用`CreativeModeTabs.tabs()`拿全部tab列表，这个也是对的；
2. Mixin注入点：
   - 目标类：`net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen`，`remap=false`；
   - 在`init()`方法TAIL阶段注入（此时游戏已经完全启动，所有注册表都初始化完了，绝对不会抛Not bootstrapped）；
   - 初始化步骤：
     1. 拿到所有tab，跳过Type.SEARCH类型；
     2. 按row字段分成TOP、BOTTOM两个独立列表；
     3. 两个列表各自按原始column排序；
     4. 计算总页数：取TOP和BOTTOM列表中较多的那个算`ceil(列表长度 / 7)`；
     5. 第一页：TOP列表里前7个tab，用Accessor把它们的column设为0~6；剩下的TOP列表tab设为-100（离屏）；BOTTOM列表同理；
     6. 总页数>1的时候，在GUI左右两侧加< >按钮；
     7. 点翻页按钮的时候，先把当前页所有tab的column改回原始值，再把目标页的对应tab的column改成0~6，其余改到离屏。
3. 不要在`extractTabButton`/`getTabX`方法里做任何注入：非当前页的tab已经被改到column=-100，原版自己的getTabX会自动算到GUI外面，自然不显示，不用你cancel、不用你替换返回值。

---

## 参考代码位置（直接抄逻辑，不要自己瞎想）
- NeoForge官方分页patch：`refs/neoforge/patches/net/minecraft/client/gui/screens/inventory/CreativeModeInventoryScreen.java.patch`，直接看它怎么分组、怎么算页、怎么加按钮；
- Fabric官方Mixin实现：`refs/fabricmc/fabric-api/fabric-creative-tab-api-v1/src/client/java/net/fabricmc/fabric/mixin/creativetab/client/CreativeModeInventoryScreenMixin.java`，直接抄它的Accessor写法和init逻辑；
- 已经写好的半成品Mixin类：`core/mirage-mixin/src/main/java/io/mirage/mixin/CreativeModeInventoryScreenMixin.java`，现在只需要把里面错误的一维排序改成TOP/BOTTOM分行排序就行，其他代码不用动。

---

## 环境规则
- 启动游戏必须通过`启动 26.2-mirage.bat`，Win+R启动，不能直接在PowerShell里拉java进程，否则进的是宿主环境不是用户虚拟桌面；
- fat jar部署路径：`mc-shili/libraries/io/mirage/mirage-launch/0.1.0/mirage-launch-0.1.0.jar`，备份文件在同目录`mirage-launch-0.1.0.jar.bak-20261018-pagingfix`，改坏了直接覆盖回滚；
- mc-shili目录下的游戏文件未经允许不要乱改；
- 改完代码编译之后，用`jar uf`把class和mirage.mixins.json热替换进jar就行，不用重新打完整fat jar。

---

## 验收标准
启动游戏进创造物品栏：
1. 所有原版标签全部正常显示在原来的位置，没有被错误移动、没有消失；
2. 安装了多标签Mod之后，超过7个标签的时候GUI左右两侧自动出现< >翻页按钮；
3. 点翻页按钮能正常切换页，非当前页标签不显示，翻回上一页标签恢复正常；
4. 控制台没有任何`Not bootstrapped`异常、没有栈错误。
