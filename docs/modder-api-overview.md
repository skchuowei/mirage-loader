# Mirage 开发者 API 总览（v0.4）

> 本文件面向 **Mod 开发者**。加载器内部实现见 `architecture.md`。
>
> 所有公共 API 都在 `io.mirage.api.**` 包下；
> `net.fabricmc.api.**` 仅为 Fabric 兼容层保留，新 Mod 不必使用。

---

## 1. 包结构

```
io.mirage.api
├── Mirage.java                # 静态门面：一切入口
├── MirageProvider.java         # SPI（加载器实现，Mod 勿碰）
├── Mod.java                    # @Mod("modid") 主类注解
├── Entrypoint.java             # @Entrypoint("client"/"server"/"main")
├── SubscribeEvent.java         # @SubscribeEvent 方法注解
│
├── bus/
│   └── EventBusHandle.java     # 事件总线接口
│
├── env/
│   ├── Dist.java               # CLIENT / DEDICATED_SERVER
│   └── Environment.java       # isClient() / isDedicatedServer()
│
├── event/
│   ├── Event.java              # 事件根类
│   ├── CancelableEvent.java    # 可取消事件基类
│   ├── ModConstructEvent.java  # 主类已实例化（裸类，历史遗留）
│   ├── ModPreInitEvent.java    # 注册 DeferredRegister 的时机
│   ├── ModInitEvent.java       # 真正写游戏对象的时机
│   ├── ModPostInitEvent.java   # 跨 Mod 交互
│   ├── ClientStartingEvent.java
│   ├── ClientStartedEvent.java
│   ├── ServerStartingEvent.java
│   ├── ServerStartedEvent.java
│   └── ServerStoppingEvent.java
│
├── log/
│   ├── ModLogger.java          # info/warn/error/debug
│   └── ModLogManager.java      # ModLogManager.get("modid")
│
├── mod/
│   └── ModMetadata.java        # Mod 元数据只读视图
│
└── registry/
    ├── DeferredRegister.java   # 延迟注册器
    ├── RegistryKey.java        # 类型安全的注册键
    └── RegistryObject.java     # 注册结果的 lazy holder
```

---

## 2. 最小 Mod

```java
package com.example.hellomod;

import io.mirage.api.*;
import io.mirage.api.event.*;
import io.mirage.api.log.ModLogger;
import io.mirage.api.log.ModLogManager;

@Mod("hellomod")
public class HelloMod {

    private static final ModLogger LOG = ModLogManager.get("hellomod");

    @SubscribeEvent
    public void onConstruct(ModConstructEvent e) {
        LOG.info("Hello from {}!", e.modId);
        Mirage.getEventBus().register(this);   // 订阅后续生命周期
    }

    @SubscribeEvent
    public void onInit(ModInitEvent e) {
        LOG.info("Loading on {}", Mirage.getDist());
        if (Mirage.isModLoaded("jei")) {
            LOG.info("JEI detected, hooking...");
        }
    }
}
```

---

## 3. 速查表

### 3.1 运行时门面 `Mirage`

| 方法 | 作用 |
|---|---|
| `Mirage.getLoaderVersion()` | 加载器自身版本 |
| `Mirage.getDist()` | 当前物理端 |
| `Mirage.isModLoaded(id)` | 探测依赖 Mod |
| `Mirage.getMod(id)` | 拿到 `ModMetadata` |
| `Mirage.getMods()` | 全部已加载 Mod |
| `Mirage.getGameDir()` / `getModsDir()` | 路径 |
| `Mirage.getEventBus()` | 全局事件总线 |
| `Mirage.getLogger(modId)` | 日志门面 |

> 在脱离加载器（IDE 直接 `main()`）的环境下调用以上任何方法，
> 会抛 `IllegalStateException` 并提示"未找到 MirageProvider 实现"。
> 这是设计如此：Mod 不应在没有加载器的情况下运行。

### 3.2 生命周期事件时序

```
[发现 Mod] → ModConstructEvent (每个 Mod)
          → ModPreInitEvent   (每个 Mod)   ← 注册 DeferredRegister
          → ModInitEvent      (每个 Mod)   ← 真正写游戏对象
          → ModPostInitEvent  (每个 Mod)   ← 跨 Mod 交互
          → ClientStartingEvent / ServerStartingEvent
          → ClientStartedEvent  / ServerStartedEvent
...
          → ServerStoppingEvent
```

### 3.3 延迟注册 `DeferredRegister`

```java
public static final DeferredRegister<Item> ITEMS =
    DeferredRegister.create(Item.class, "mymod");

public static final RegistryObject<Item> RUBY =
    ITEMS.register("ruby", () -> new Item(new Item.Properties()));

// Mod 主类构造时：
ITEMS.register();   // 挂到事件总线，等 ModInitEvent 触发
```

> **注意**：当前 M5 尚未把 `DeferredRegister` 真正桥接到 MC 原生 `Registries`。
> API 契约已冻结，core 模块在 bootstrap 时会调用
> `DeferredRegister.setBackend(...)` 注入实际写入逻辑。

### 3.4 日志

```java
private static final ModLogger LOG = ModLogManager.get("mymod");

LOG.info("loaded {}", version);
LOG.warn("missing optional compat: {}", modId);
LOG.error("network failure", exception);
```

---

## 4. 与 core 模块的对接约定

`io.mirage.api.MirageProvider` 是 SPI：Launcher 在 `discoverMods()` 之后、
实例化 Mod 主类之前，构造一个 `MirageProviderImpl` 并调用
`Mirage.bootstrap(provider)` 注入运行时实现。

core 模块落地清单：

- [x] `core/mirage-loader/runtime/MirageProviderImpl` —— Provider 默认实现
- [x] `core/mirage-loader/runtime/ModMetadataAdapter` —— `ModContainer` → `ModMetadata`
- [x] `core/mirage-loader/runtime/ModLoggerImpl` —— stdout/stderr 日志门面
- [x] `core/mirage-eventbus/EventBusHandleImpl` —— `EventBus` → `EventBusHandle`
- [x] `Launcher.main()` 在 Mod 主类实例化后 `register(instance)`，
      并按 `ModConstructEvent → ModPreInitEvent → onInitialize → ModInitEvent → (全部完成后) ModPostInitEvent` 派发
- [ ] bootstrap 时调用 `DeferredRegister.setBackend(...)` 把 MC `Registries` 接上（M5 待办）
- [ ] `ClientStartingEvent / ClientStartedEvent / Server*` 等 MC 层事件
      需要在 MC 主类入口处 hook（M5 待办）

---

## 5. 当前状态

- **API 契约**：已冻结（本文件所列类与方法签名视为公开承诺，
  后续只能加方法、不能改/删现有签名）。
- **core 实现**：M0–M7 已完成基础管线；公共 API 门面已在 Launcher 里
  bootstrap，Mod 可直接使用 `Mirage.*` 与全部生命周期事件。
  剩余待办：`DeferredRegister` 桥接 MC 原生 `Registries`、MC 层事件 hook。
- **兼容层**：`net.fabricmc.api.ModInitializer` / `ClientModInitializer`
  已在 API 包里占位，Fabric 兼容模块在 M7 桥接。
