# Mirage Loader MDK (Mod Development Kit)

Mirage Loader 示例 Mod 开发模板。

## 快速开始

### 1. 修改 Mod 信息

编辑 `src/main/resources/mirage.mod.json`：
```json
{
  "id": "your-mod-id",
  "version": "1.0.0",
  "name": "Your Mod Name",
  "entrypoint": "com.example.mdk.MdkExampleMod"
}
```

编辑 `src/main/java/com/example/mdk/MdkExampleMod.java`：
- 改 `MOD_ID` 常量
- 改包名和类名

### 2. 构建

```bash
build.bat
```

输出：`dist/mdk-example-1.0.0.jar`

### 3. 安装

把 jar 复制到：
```
mc-shili/versions/26.2-mirage/mods/
```

启动游戏（PCL 选 26.2-mirage 版本）。

## 目录结构

```
mdk-example/
├── build.bat                  # 构建脚本
├── src/
│   ├── main/
│   │   ├── java/
│   │   │   └── com/example/mdk/
│   │   │       └── MdkExampleMod.java    # Mod 主类
│   │   └── resources/
│   │       └── mirage.mod.json           # Mod 描述符
└── README.md
```

## API 速查

### 注解
- `@Mod("modid")` — 标记 Mod 主类
- `@SubscribeEvent` — 标记事件监听方法

### 事件
- `ModConstructEvent` — Mod 构造阶段（注册方块/物品）

### 注册
```java
// 方块 ResourceKey
public static final ResourceKey<Block> EXAMPLE_BLOCK_KEY = ResourceKey.create(
    BuiltInRegistries.BLOCK.key(),
    Identifier.fromNamespaceAndPath(MOD_ID, "example_block"));

// 自定义方块
public static final Block EXAMPLE_BLOCK = new Block(
    BlockBehaviour.Properties.of()
        .setId(EXAMPLE_BLOCK_KEY)
        .mapColor(MapColor.STONE)
        .strength(3.0f, 3.0f)
);

// 注册
Registry.register(BuiltInRegistries.BLOCK,
    EXAMPLE_BLOCK_KEY.identifier(),
    EXAMPLE_BLOCK);
```

## 调试

启动游戏后，日志里看：
```
[Mirage] Discovered mod: mdk-example v1.0.0
[MdkExample] Mod constructing...
[MdkExample] Registered example_block
```

进游戏拿物品：
```
/give @s mdk-example:example_block
```
