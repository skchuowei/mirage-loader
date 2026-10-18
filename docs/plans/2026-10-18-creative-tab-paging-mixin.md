# 创造模式标签页分页功能 · 重写计划（Mixin实现）

> 日期：2026-10-18
> 关联旧计划：`docs/plans/2026-10-18-creative-tab-paging-fix.md`
> 核心改动：放弃MirageTransformer里硬写的ASM字节码偏移织入，改用项目已有的Mixin系统实现。

---

## 目标

用项目已有的Mixin系统实现创造模式标签页分页：
1. 标签总数超过一行7个槽位时，GUI两侧正确显示翻页箭头和页码；
2. 翻页时标签正确分布在0-6列位置，不再全部叠在第0列；
3. 不破坏原版方法执行流，不出现`Not bootstrapped`这类栈破坏异常；
4. 现有启动链路、Mixin加载、DeferredRegister等功能完全不受影响。

---

## 现状分析

### 1. 之前ASM硬织入方案为什么坏了
- 旧代码在`MirageTransformer.transformCreativeInventory()`里硬写字节码偏移：
  - 在`extractTabButton()`方法开头插`shouldDrawTab`判断，在`istore 7`位置POP掉原版column值调用`remapColumn`；
  - 在`getTabX()`方法的`IRETURN`位置POP掉原版x值调用`remapTabX`；
- 这些偏移是按照旧版MC的方法局部变量表写的，在MC26.2里`extractTabButton`/`getTabX`的字节码结构已经变了：
  - 局部变量7已经不是column变量，POP错了栈位置，直接破坏了原版方法的操作数栈；
  - 导致原版代码执行到一半抛`Not bootstrapped`，标签绘制逻辑中断，最终所有标签叠在错误位置；
- 这种硬偏移方式在MC小版本更新后必然失效，维护成本极高。

### 2. 现有可用基础
- 项目已经有完整跑通的Mixin系统：Mixin服务加载、配置扫描、织入管线在commit 88c2275已经修复，游戏能正常加载mixin配置；
- `MirageDebug`里的分页纯逻辑（翻页状态、页计算、列号映射、翻页按钮绘制、点击处理）已经写好，只需要把这些方法通过Mixin钩子正确挂到原版方法上，不需要重写逻辑；
- NeoForge/Fabric源码已经确认：`CreativeModeTab`的`column`/`row`是private final字段，直接读字段就能拿到稳定值，不会触发getter的bootstrap检查。

### 3. 阻塞点
- 旧的ASM硬织入代码（MirageTransformer里的`transformCreativeInventory`部分）必须删掉，否则它的错误字节码偏移还会继续破坏原版方法。

---

## 改动文件清单

### 要改的文件
| 文件 | 改什么 |
|---|---|
| `core/mirage-loader/src/main/java/io/mirage/loader/MirageTransformer.java` | 删除`transformCreativeInventory`里所有针对创造模式物品栏的硬字节码织入逻辑，不再处理CreativeModeTab相关的类 |
| `core/mirage-mixin/src/main/java/io/mirage/mixin/client/CreativeModeInventoryScreenMixin.java`（新建） | 用Mixin钩子拦截原版`extractTabButton`/`getTabX`/`extractBackground`/鼠标点击方法，调用MirageDebug里已有的分页逻辑 |
| `core/mirage-mixin/src/main/resources/mirage.mixins.json` | 注册新建的CreativeModeInventoryScreenMixin |
| `core/mirage-loader/src/main/java/io/mirage/loader/MirageDebug.java` | 保留已有的分页逻辑，只删掉硬织入需要的不兼容部分，适配Mixin的回调方式 |

### 不做的事
- 不重写分页纯逻辑（页计算、翻页状态、按钮绘制这些已经写好的部分直接复用）；
- 不改动MirageTransformer其他类的织入逻辑（资源包暴露、启动overlay这些保持原样）；
- 不引入shadow插件、不重新手动打整个fat jar；
- 不修改mc-shili目录下除fat jar之外的任何文件。

---

## 每处改动要点

### 1. 先删掉MirageTransformer里的硬织入
- 在`MirageTransformer.transform()`方法里，删掉针对`net/minecraft/client/gui/screens/inventory/CreativeModeInventoryScreen`类的所有transform分支，把之前`transformCreativeInventory`的调用注释掉，让原版这个类完全不被硬字节码修改，从根源上消除栈破坏问题。

### 2. 新建CreativeModeInventoryScreenMixin
- 用`@Mixin(CreativeModeInventoryScreen.class)`标注；
- 用`@Inject(method="extractTabButton", at=@At("HEAD"))`：在方法开头调用`MirageDebug.shouldDrawTab(tab)`，如果返回false就`ci.cancel()`直接跳过绘制，不需要再POP局部变量；
- 用`@Inject(method="getTabX", at=@At("RETURN"), cancellable=true)`：方法返回前用`cir.setReturnValue(MirageDebug.remapTabX(tab))`替换返回值，不需要硬POP操作数栈；
- 用`@Inject(method="extractBackground", at=@At("TAIL"))`：方法末尾调用`MirageDebug.drawTabPageControls(...)`绘制翻页按钮；
- 用`@Inject(method="mouseClicked", at=@At("HEAD"), cancellable=true)`：在鼠标点击开头调用`MirageDebug.handlePageClick(...)`，如果点中翻页按钮就`ci.setReturnValue(true)`拦截事件。
- 所有Mixin回调都用`@Local`或者方法参数传入原版对象，不需要硬编码字节码偏移。

### 3. 适配MirageDebug的分页逻辑
- 把`getDisplayColumn`里直接读`column`/`row`字段的逻辑保留，去掉对旧硬织入的依赖；
- `remapColumn`逻辑保留，通过Mixin拿到原版column值之后直接做映射，不需要再从局部变量里POP；
- 所有方法保持public static，让Mixin能直接调用。

### 4. 注册Mixin
- 在`mirage.mixins.json`的client mixin列表里加上新建的`CreativeModeInventoryScreenMixin`，确保Mixin系统启动时能扫描到。

---

## 前置条件与阻塞项
1. 必须先删掉MirageTransformer里针对创造模式界面的硬织入代码，否则旧的错误字节码还会和Mixin冲突；
2. 确认Mixin系统在当前jar里是正常工作的（之前commit 88c2275已经验证过），不需要重新修Mixin加载管线；
3. 编译后只热替换新增/修改的3个class文件，不重新打整个fat jar，避免踩手动打包的坑。

---

## 合规边界
- 不修改任何原版MC的逻辑，所有分页逻辑都在Mixin回调和MirageDebug里，对原版方法的侵入只通过标准Mixin注解完成；
- 不删除、不移动用户mc-shili目录下的存档、mod、配置文件；
- 所有改动都只在mirage-loader仓库内，不碰refs、backups、26.2原版目录；
- 每一步编译后先确认BUILD SUCCESSFUL，再热部署单个class，不直接改fat jar里其他文件。

---

## 回归测试影响面
1. 之前已经验证过的功能不受影响：游戏启动到主菜单、DeferredRegister注册、NeoForge mod识别、MidCache缓存、资源包暴露这些逻辑完全不碰；
2. 新增Mixin不会和现有Mixin冲突（现有mixin配置里没有针对CreativeModeInventoryScreen的内容）；
3. 旧的硬织入删掉之后，之前因为栈破坏导致的`Not bootstrapped`异常会直接消失；
4. 验证时需要确认：创造模式物品栏打开时标签正常显示，没有重叠，翻页按钮能点，翻页后标签正确切换。

---

## 验证步骤
1. 第一步先删掉MirageTransformer里的创造模式硬织入，编译部署，启动游戏确认物品栏能正常显示所有标签（和原版完全一样，没有分页按钮），确认栈破坏问题消失；
2. 第二步新建Mixin类，注册到mixin配置，编译部署，启动游戏打开物品栏：
   - 日志里能看到Mixin成功注入的输出；
   - 标签数>7时翻页按钮和页码显示出来；
   - 没有`NoSuchMethodError`、栈异常；
3. 第三步游戏内验证：
   - 所有标签正常分布在原版位置，没有重叠；
   - 点击右箭头翻页，下一组7个标签正确显示；
   - 点击左箭头切回上一页；
   - 游戏不崩溃，能正常玩。

---

## 回滚方案
1. 如果新Mixin导致启动崩溃，直接把`mirage.mixins.json`里新建的Mixin条目删掉，热替换两个class，就回到没有分页、原版标签正常显示的状态；
2. 代码层面直接`git checkout`对应的三个文件，回到当前已提交的版本；
3. fat jar有备份，出问题直接用`mirage-launch-0.1.0.jar.bak-20261018-pagingfix`覆盖，1秒回到正常启动状态。
