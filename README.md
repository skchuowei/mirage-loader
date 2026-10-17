# Mirage Loader (幻像加载器)

**轻量、现代、独立的 Minecraft Mod 加载器**

介于 Fabric 与 NeoForge 之间 —— 轻量如 Fabric，开箱友好如 Forge。独立加载器，不依赖任何其他加载器。

## 特性

- **独立零依赖**：不依赖 Fabric/NeoForge，纯 ASM 织入
- **模块懒加载**：Mod 调用时才加载，不调用不加载
- **Fabric 兼容**：支持 fabric.mod.json 和 ModInitializer
- **类加载隔离**：每个 Mod 独立 ClassLoader
- **MIT 开源**：完全开放

## 状态

**M0-M7 完成**：
- M0 架构冻结
- M1 启动握手
- M2 Mod 发现与类加载隔离
- M3 Mixin 管线
- M4 生命周期事件总线
- M5 注册中心
- M6 MDK 模板
- M7 Fabric 兼容层

详见 [PLAN.md](./PLAN.md)。

## 快速开始

### 构建

```bash
# 需要 JDK 25
./build.bat
```

### 运行

```bash
# 启动脚本会自动加载 Mirage
启动 26.2-mirage.bat
```

### 开发 Mod

1. 复制 `mdk/mdk-example/`
2. 修改 `mirage.mod.json`
3. 编写 Mod 主类
4. 运行 `build.bat`

## 架构

```
Launcher (入口)
  ↓
Loader (Mod 发现)
  ↓
MirageClassLoader (类加载隔离)
  ↓
MirageTransformer (ASM 织入)
  ↓
Minecraft Main
```

## 版本

- 当前：0.4.0 (开发版)
- 目标：0.1.0 (首个正式版)

## 许可

MIT
