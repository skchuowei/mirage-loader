# Mirage Loader M7/M8 计划

## M7 · Fabric 兼容层原型

### 目标
让简单 Fabric Mod 能在 Mirage 上运行。

### 范围
1. **fabric.mod.json 解析器**
   - 读取 mods 目录下的 fabric.mod.json
   - 映射到 Mirage 的 ModManifest

2. **Fabric API 兼容接口**
   - `net.fabricmc.api.ModInitializer`
   - `net.fabricmc.api.ClientModInitializer`
   - `onInitialize()` / `onInitializeClient()`

3. **Mod 发现扩展**
   - Loader.discoverMods() 同时识别：
     - Mirage 格式：`mirage.mod.json`
     - Fabric 格式：`fabric.mod.json`

### 验收标准
- 一个简单 Fabric Mod（只有 ModInitializer）能在 Mirage 上加载
- 打印 "Hello from Fabric mod on Mirage"

---

## M8 · 文档与 0.1.0 发布

### 目标
发布 Mirage Loader 0.1.0。

### 范围
1. **README.md**
   - 项目介绍
   - 快速开始
   - 构建说明

2. **开发文档**
   - Mod 开发指南
   - API 概览
   - 架构说明

3. **示例 Mod**
   - mdk-example 完善
   - README

4. **0.1.0 发布**
   - tag v0.1.0
   - release note

### 验收标准
- README.md 完整
- 能从零构建并运行示例 Mod
- 版本号 0.1.0
