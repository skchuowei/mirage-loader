# Mirage Loader 架构时序

## 启动流程（M1 当前阶段）

```
PCL/官方启动器
   │  java -Xmx2G -cp <原版jar+库jar> io.mirage.launch.Launcher --gameDir ... --assetsDir ...
   ▼
[io.mirage.launch.Launcher.main]
   │  1. 打印 banner：[Mirage] Hello from Mirage Launch 0.1.0
   │  2. Class.forName("net.minecraft.client.main.Main")
   │  3. method.invoke(null, (Object) args)
   ▼
[net.minecraft.client.main.Main.main]
   │  1. SharedConstants.tryDetectVersion()
   │  2. joptsimple 解析 args（gameDir/assetsDir/quickPlay...）
   │  3. new GameConfig(...).run()
   ▼
Minecraft 主菜单
```

## 目标流程（M2 之后）

```
Launcher.main
   │
   ├──► 1. 定位 gameDir/mods/
   ├──► 2. 扫描 mods/*.jar，读 mirage.mod.json
   │       └─ 收集 requires:[module-id] → 仅加载被引用的模块 jar
   ├──► 3. 依赖拓扑排序（环检测 + 缺失报错）
   ├──► 4. new MirageClassLoader(原版jar, modJars, requiredModuleJars)
   ├──► 5. 在 MirageClassLoader 上挂 MixinTransformer
   │       └─ 扫描每个 Mod jar 的 *.mixins.json
   ├──► 6. 用 MirageClassLoader 加载 net.minecraft.client.main.Main
   └──► 7. main.invoke(null, args)
           │
           ▼
       MC 类被加载时 → MixinTransformer 命中 → 织入 Mod 字节码
           │
           ▼
       Mod 主类收到 ModConstructEvent → 注册事件回调 → 进游戏
```

## 类加载层级

```
AppClassLoader
   └─ MirageClassLoader  (parent = AppClassLoader)
        ├─ MC 原版 jar        （Mojang 官方映射）
        ├─ Mod jars            （mods/*.jar，每个 Mod 一个 URL）
        ├─ 核心模块 jar        （mirage-loader-core, mirage-eventbus, ...）
        └─ 按需模块 jar        （首次被 Mod 引用时 appendURL）
```

## 懒加载判定

模块 jar 内含 `META-INF/mirage-module.json`：
```json
{
  "id": "mirage-config",
  "packagePrefix": "io.mirage.config.",
  "providedApi": ["io.mirage.config.ConfigBuilder"]
}
```
当 `MirageClassLoader.findClass("io.mirage.config.ConfigBuilder")` 首次被触发：
  - 检查该模块 jar 是否已加入
  - 未加入 → appendURL → 打日志 `[Mirage] on-demand loaded module: mirage-config`
  - 加入后正常加载类
