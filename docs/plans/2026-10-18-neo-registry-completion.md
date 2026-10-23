# 补全 NeoForge 注册表桥接（注册体系补齐）

- **日期**：2026-10-18
- **关联**：上游 `docs/plans/2026-10-11-neoforge-compat.md`（模块立项）；运行时接线落地点 `docs/runtime-wiring-changelog.md`；本模块 README「当前局限 / TODO」第 4、5、6 项（非 item/block 注册表跳过、`RegistryObject` 字段未登记、TOML 解析健壮性——本计划只做注册体系那部分）。
- **一句话**：让 NeoForge mod 通过 `DeferredRegister` 注册 `item`/`block` 之外的注册表（entity_type / enchantment / biome / mob_effect / sound_event / block_entity_type / fluid / potion / particle_type / attribute / menu / villager_* / point_of_interest_type / game_event / data_component_type / decorated_pot_pattern / map_decoration_type …）时，真正写入对应的 MC 注册表，而不是「跳过未知注册表类型」。

## 1. 目标（可验证标准）

1. NeoForge mod 用 `DeferredRegister.create(Registries.ENCHANTMENT, MODID)` / `create(Registries.ENTITY_TYPE, ...)` 等方式声明的条目，`register(bus)` 后能正确桥接到 MG `DeferredRegister`，并在 `flush()` 阶段真正 `Registry.register(...)` 进对应的 `BuiltInRegistries` 字段；
2. 不再出现「跳过未知注册表类型」或「跳过未知注册表 xxx（n 个条目未桥接）」这类静默丢弃；只保留对**确实不存在**的注册表路径的告警（如路径拼错）；
3. `gradle build` 全绿；现有 4 个旧 mod（examplemod / testtab / addonmod / mdk-example）+ ExpOre 仍正常被发现、无崩溃栈；
4. 不引入对 NeoForge 源码的复制（clean-room，仅用 `refs/neoforge` 对照行为/字段名）；不猜 MC 内部类——所有 `net.minecraft.*` 类名均来自本计划 `javap` 实证（见 §3）。

## 2. 前置条件与阻塞项

- 已确认根因：现 `net.neoforged.neoforge.registries.DeferredRegister.mapElementType()` 只映射 `item`/`block`/`creative_mode_tab`，其余返回 `null` → `register(bus)` 直接 `return` 不桥接；`Launcher` 的 `nativeBackend` switch 也只处理 `item`/`block`/`creativemodetab`，其余打印「跳过未知注册表类型」且 `registry.register(...)` 永不调用。
- 更大的隐藏根因：**Bootstrap 只解冻了 `BLOCK`/`ITEM`/`CREATIVE_MODE_TAB` 三个注册表**。即使补上桥接，往 `ENTITY_TYPE`/`ENCHANTMENT` 等冻结中的注册表 `register`，MC 会抛 `IllegalStateException: Registry is frozen`。所以本计划必须一并把「解冻」改成**遍历 `BuiltInRegistries.REGISTRY` 对所有 `MappedRegistry` 解冻、flush 后统一再冻结**。
- 工作区现状：外层 `.git` 已删除（上轮），内层仓库有 2 个未推送 commit 待联网 push；本计划只动 `modules/mirage-neoforge-compat` 与 `launcher/mirage-launch`，与未推送改动不重叠。
- MC 26.2 运行在虚拟桌面；验证只靠 `gradle build` + 部署 fat jar + 用户双击 bat 读 `logs/latest.log`，AI 不直接拉 java（AGENTS.md 虚拟桌面规则）。
- 编译环境：`.gradle-dist/gradle-9.1.0/bin/gradle.bat`，`JAVA_HOME=C:\Program Files\Java\jdk-25.0.4`，`GRADLE_USER_HOME` 指向工作区临时目录（本机默认 `~/.gradle` 的 native 缓存已损坏，见上轮记录）。

## 3. 现状分析（javap 实证，全部来自 `mc-shili/versions/26.2/26.2.jar`）

- `BuiltInRegistries.REGISTRY` 是 `Registry<? extends Registry<?>>`，可 `stream()` 遍历所有注册表实例；其 `key()` 即 `minecraft:<registryPath>`，`getKey()`/路径即注册表 ID（如 `minecraft:enchantment`）。
- 各注册表在 `BuiltInRegistries` 上是 `public static final` 字段，字段名 = 路径全大写（如 `ENCHANTMENT` ← `minecraft:enchantment`）。故「路径 → 注册表对象」用 `getClass().getField(path.toUpperCase(Locale.ENGLISH)).get(null)` 即可，**无需逐类型 switch**。
- `MappedRegistry` 字段（沿用现有 Launcher 反射键名，已实证存在）：`frozen`(boolean)、`unregisteredIntrusiveHolders`(Map)、`allTags`(TagSet)。`DefaultedRegistry` 等也继承自 `MappedRegistry`。
- `Registry` 提供静态 `register(Registry<? super T>, String, T)`、`register(Registry, Identifier, T)`、`registerForHolder(...)`，无 `minecraft:` 前缀时自动按注册表 namespace 拼（与现行 `Registry.register(reg, Identifier.fromNamespaceAndPath(modId, path), value)` 等价）。
- 元素类型（来自各字段声明泛型参数，已实证）：
  - `enchantment` → `net.minecraft.world.item.enchantment.Enchantment`
  - `biome` → `net.minecraft.world.level.biome.Biome`
  - `mob_effect` → `net.minecraft.world.effect.MobEffect`
  - `sound_event` → `net.minecraft.sounds.SoundEvent`
  - `block_entity_type` → `net.minecraft.world.level.block.entity.BlockEntityType`
  - `fluid` → `net.minecraft.world.level.material.Fluid`
  - `potion` → `net.minecraft.world.item.alchemy.Potion`
  - `particle_type` → `net.minecraft.core.particles.ParticleType`
  - `attribute` → `net.minecraft.world.entity.ai.attributes.Attribute`
  - `menu` → `net.minecraft.world.inventory.MenuType`
  - `villager_profession` → `net.minecraft.world.entity.npc.villager.VillagerProfession`
  - `villager_type` → `net.minecraft.world.entity.npc.villager.VillagerType`
  - `point_of_interest_type` → `net.minecraft.world.entity.ai.village.poi.PoiType`
  - `game_event` → `net.minecraft.world.level.gameevent.GameEvent`
  - `data_component_type` → `net.minecraft.core.component.DataComponentType`
  - `decorated_pot_pattern` → `net.minecraft.world.level.block.entity.DecoratedPotPattern`
  - `map_decoration_type` → `net.minecraft.world.level.saveddata.maps.MapDecorationType`
  - `number_format_type` → `net.minecraft.network.chat.numbers.NumberFormatType`
  - `carver` → `net.minecraft.world.level.levelgen.carver.WorldCarver`
  - `feature` → `net.minecraft.world.level.levelgen.feature.Feature`
  - `chunk_status` → `net.minecraft.world.level.chunk.status.ChunkStatus`
  - `recipe_type` → `net.minecraft.world.item.crafting.RecipeType`
  - `recipe_serializer` → `net.minecraft.world.item.crafting.RecipeSerializer`
  - `stat_type` → `net.minecraft.stats.StatType`
  - `activity` → `net.minecraft.world.entity.schedule.Activity`
  - `memory_module_type` → `net.minecraft.world.entity.ai.memory.MemoryModuleType`
  - `sensor_type` → `net.minecraft.world.entity.ai.sensing.SensorType`
  - `structure_type` → `net.minecraft.world.level.levelgen.structure.StructureType`
  - `structure_placement` → `net.minecraft.world.level.levelgen.structure.placement.StructurePlacementType`
  - `structure_piece` → `net.minecraft.world.level.levelgen.structure.pieces.StructurePieceType`
  - `placement_modifier_type` → `net.minecraft.world.level.levelgen.placement.PlacementModifierType`
  - `blockstate_provider_type` → `net.minecraft.world.level.levelgen.feature.stateproviders.BlockStateProviderType`
  - `foliage_placer_type` → `net.minecraft.world.level.levelgen.feature.foliageplacers.FoliagePlacerType`
  - `trunk_placer_type` → `net.minecraft.world.level.levelgen.feature.trunkplacers.TrunkPlacerType`
  - `root_placer_type` → `net.minecraft.world.level.levelgen.feature.rootplacers.RootPlacerType`
  - `tree_decorator_type` → `net.minecraft.world.level.levelgen.feature.treedecorators.TreeDecoratorType`
  - `feature_size_type` → `net.minecraft.world.level.levelgen.feature.featuresize.FeatureSizeType`
  - `biome_source` → `net.minecraft.world.level.biome.BiomeSource`
  - `chunk_generator` → `net.minecraft.world.level.chunk.ChunkGenerator`
  - `material_condition` → `net.minecraft.world.level.levelgen.SurfaceRules$ConditionSource`
  - `material_rule` → `net.minecraft.world.level.levelgen.SurfaceRules$RuleSource`
  - `density_function_type` → `net.minecraft.world.level.levelgen.DensityFunction`
  - `structure_processor` → `net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessor`
  - `structure_pool_element` → `net.minecraft.world.level.levelgen.structure.pools.StructurePoolElementType`
  - `float_provider_type` → `com.mojang.serialization.MapCodec<? extends FloatProvider>`（codec 注册表，mod 以 `() -> SomeMapCodec` 注册）
  - `int_provider_type` / `height_provider_type` / `block_predicate_type` / `rule_test` / `pos_rule_test` / `command_argument_type` / `position_source_type` / `trigger_types` / `loot_*` 系列 / `dialog_*` / `permission_*` / `test_*` / `slot_*` / `consume_effect_type` / `recipe_display` / `slot_display` / `entity_sub_predicate_type` / `data_component_predicate_type` / `enchantment_*_type` / `environment_attribute` / `attribute_type` 等：均为标准注册表，路径=字段小写，元素类型取自字段声明。
  - 注：Codec 类注册表（元素类型是 `com.mojang.serialization.MapCodec<? extends X>`，桥接时 `bridge.create` 拿到的元素是 `MapCodec` 子类的原始类，运行时 supplier 返回的就是该实例；`Registry.register` 接受 `T` 即可）。

## 4. 改动文件清单

| 文件 | 改什么 |
|---|---|
| `modules/mirage-neoforge-compat/src/main/java/net/neoforged/neoforge/registries/DeferredRegister.java` | `mapElementType(path)` 扩成覆盖上面全部标准注册表路径（返回元素类）；未知路径仍返回 `null`（路径拼错才告警）。`resolveRegistryPath` 保持不变（已能解析 `ResourceKey.toString` 与 `createBlocks/createItems` 的 fixedPath）。 |
| `launcher/mirage-launch/src/main/java/io/mirage/launch/Launcher.java` | ① 新增 `lookupRegistry(String path)`：用 `builtinReg.getField(path.toUpperCase(Locale.ENGLISH)).get(null)` 反射取注册表对象，找不到返回 `null`；② `nativeBackend` 用 `lookupRegistry` 替掉原 `switch`，只保留 `creativemodetab`（intrusive holder）/ `item`（BlockItem 之外的纯 item）两个特例分支，其余一律 `Registry.register(reg, new Identifier(modId, path), value)`；③「解冻」改为遍历 `BuiltInRegistries.REGISTRY.stream()`，对 `MappedRegistry` 实例 `frozen=false` + `unregisteredIntrusiveHolders.clear()`；④ flush 后统一对遍历到的 `MappedRegistry` 重新冻结（重建 IntrusiveHolder 的兜底已由现成的 `rebind` 逻辑处理，若没有则用 `bindAllTagsToEmpty` 防护）。 |

**明确不做**：
- 不改 `core/mirage-loader`（`ModDiscoverer`/生命周期）、不改 `api/mirage-api` 的 `DeferredRegister`/`RegistryObject`（它们已支持任意 `Class<T>`，无需改）；
- 不做 `@SubscribeEvent` 的 `priority`/`receiveCancelled` 解析（属事件体系，下一轮）；
- 不补 NeoForge 业务事件 stub（下一轮）；
- 不动 TOML 解析器健壮性（属另一 TODO）；
- 不改 `net.neoforged.neoforge.registries` 包名/结构——clean-room stub 保持原状，只扩元素类型映射。

## 5. 每处改动要点

- **neo `DeferredRegister.mapElementType`**：保留 `item`/`block`/`creative_mode_tab` 三行，新增约 30+ 行的 `case` 覆盖 §3 列出的注册表路径，`default -> null`。注意：路径是 NeoForge `RegistryKey` 的 location（`minecraft:<path>`），与 `BuiltInRegistries` 字段名小写一一对应，来源已实证。
- **neo `register(IEventBus)`**：`elementType == null` 分支文案保留（仅在路径真为 `null`/空或映射缺失时告警），其余路径都会拿到元素类并桥接。
- **Launcher `lookupRegistry`**：`try { Field f = BuiltInRegistries.class.getField(path.toUpperCase(Locale.ENGLISH)); return (Registry<?>) f.get(null); } catch (NoSuchFieldException | IllegalAccessException e) { return null; }`。保持对「路径拼错」的告警语义。
- **Launcher `nativeBackend`**：`Object reg = lookupRegistry(regName); if (reg == null) { System.out.println("跳过未知注册表类型: " + regName); return; }`；再 `if ("creativemodetab".equals(regName)) { ...intrusive... } else if ("item".equals(regName) && value instanceof Item) {...} else { Registry.register((Registry<Object>) reg, new net.minecraft.resources.Identifier(modId, path), value); }`。
- **Launcher 解冻/再冻结**：抽 `unfreezeAllRegistries()` / `refreezeAllRegistries()` 两个 private 方法，遍历 `BuiltInRegistries.REGISTRY.stream()`，对 `getClass().getSimpleName().equals("MappedRegistry")` 的实例做反射 `frozen=false` + `unregisteredIntrusiveHolders.clear()`；再冻结时 `frozen=true`。（沿用现成的字段键名，已实证 `MappedRegistry` 含这些字段。）对 `BlockItem` 绑定等需要「item 注册表可读」的逻辑不受影响——item 注册表只是也一起解冻了，更宽松。
- 原 `BLOCK`/`ITEM`/`CREATIVE_MODE_TAB` 三个显式 `getField` 解冻调用删除，统一走 `unfreezeAllRegistries()`。

## 6. 回归测试影响面

- 风险点：全量解冻所有 `MappedRegistry` 比只解冻 3 个激进。但 MC 的 `Bootstrap` 本就是在所有注册表解冻态下完成注册后再整体冻结，等效行为，符合 vanilla 时序；现成 `c0a3035` 已对 `CREATIVE_MODE_TAB` 等验证过「先解冻→flush→再冻结」闭环，本计划只是把「3 个」扩成「全部」。
- 对于 `item`：若某 mod 同时注册 Block + 其 BlockItem，且 BlockItem 的 supplier 依赖 Block 已注册——当前是按条目顺序 `Registry.register`，而 `register` 时 Block 可能尚未注册（同一次 flush 内）。现行实现已接受此风险（mod 通常用 `DeferredBlock` 拿 `RegistryObject` 间接引用，延迟取值），本计划不改变该语义。
- `mdk-example` 演示的是 item，路径 `item` 仍走原分支，零行为变化。
- 旧 mod（examplemod/testtab/addonmod/ExpOre）大多只注册 item/block，路径映射不变，行为不变。

## 7. 验证步骤

1. `gradle build`（`GRADLE_USER_HOME` 指向工作区临时目录）全绿，无新增编译错误。
2. 重新打包 fat jar（`build\mirage-launch-0.4.0.jar` 覆盖），用 `jar uf` 只更新变了的 `io/mirage/loader/Launcher.class` 与 `net/neoforged/neoforge/registries/DeferredRegister.class` 及其内部类，保证 `jar tf` 内类齐、不丢 `META-INF/services`。
3. 部署到 `mc-shili/libraries/io/mirage/mirage-launch/0.1.0/`，请用户双击 `..\启动 26.2-mirage.bat`。
4. 读 `logs/latest.log`：grep `[Mirage:NeoDR]` 与「跳过未知注册表」——预期不再出现对 `entity_type`/`enchantment` 等的跳过；现有 4 个旧 mod 仍正常 discovered；无 `IllegalStateException: Registry is frozen`、无 `MixinTransformerError`、无崩溃栈。
5. **强验证（可选）**：若仓库外 `mods-library/` 有注册非 item/block 的 NeoForge mod，按 AGENTS.md 测试 mod 规则部署一个做正向验证；无则不强行造 mod。

## 8. 合规边界

- 本模块 `modules/mirage-neoforge-compat` 是 **LGPL 兼容层**，本计划只做**行为观察 + clean-room 重实现**：元素类型字符串、`BuiltInRegistries` 字段名、元素类全名均来自对 `26.2.jar` 的 `javap` 实证（§3 列明），**不复制** NeoForge 任何实现代码；`deferredregister` 包名与 NeoForge 相同是兼容层刻意对齐，实现是独立的 MG 桥接逻辑。
- 不读取、不反编译 `mc-shili` 内其他文件；只按 §2 规则用 `jar tf`/`javap` 读取 MC 真 jar 的公开 API 形状。

## 9. 回滚方案

- 源码：`git checkout -- modules/mirage-neoforge-compat/.../DeferredRegister.java launcher/mirage-launch/.../Launcher.java`；
- fat jar：用 `mirage-launch-0.1.0.jar.bak-20261018-pagingfix`（或最新 `*.bak`）还原，再请用户双击验证旧行为；
- 若线上仍崩（如全量解冻引发某注册表二次冻结异常）：把 `unfreezeAllRegistries` 退化为只解冻 `BLOCK`/`ITEM`/`CREATIVE_MODE_TAB`，其余注册表恢复「跳过未知注册表类型」的旧行为——游戏其余功能不受影响。
