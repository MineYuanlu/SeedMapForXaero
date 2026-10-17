# basic — server_config.sm4x

配置文档：`gameDir/xaero/seed-map-for-xaero/<mainId>/server_config.sm4x`，
每个多人服务器一个文件，按 Xaero 世界根 `mainId`（如 `Multiplayer_192.168.1.1`）隔离。
配置文件是自定义二进制，**不是 JSON**。

## Classes

- `ServerConfig` — 门面：生命周期 + 便捷读写。外部统一从这里访问，公开 API 稳定。
- `ConfigData` — 数据体：服务器级设置 + `worlds` 表 + 种子历史；`CODEC` 是其序列化实现。
- `WorldConfig` — 单个 (mainId, mwId) 的世界配置，构造函数包私有，经 `ConfigData.getOrCreateWorld(mwId)` 创建。
- `LootDisplayMode` — loot 预览显示模式枚举（交互 × 布局两个正交维度）。

序列化帧、原子保存、损坏回退全在 `core/Sm4xFile`；
数据体字节布局与版本号只存在于本包 `ConfigData`/`WorldConfig` 的 write/read 方法里。
改格式时只改代码 + `ServerConfigTest`，不要在文档里描述二进制结构。

## Persisted state

- Seed per (mainId, mwId) — `WorldConfig.seed`（null = 未设置）
- World-gen MC version — `WorldConfig.mcVersion`（null = 跟随客户端；ViaVersion/Fabric 跨版本场景；每帧在 `SeedMapMixin.tickWorldInfo` 经 `Xsm.applyGameVersion` 应用）
- Color theme — `ConfigData.theme`（经 `BiomeColorTable.resolveProvider()` 恢复）
- Invisible biomes / structures — `ConfigData.invisibleBiomes` / `invisibleStructures`（两个独立开关，`SeedMapToggleMixin` 读写）
- Structure icon size — `ConfigData.structureIconSize`（setter clamp 到 0.05~2.0）
- Loot preview — `ConfigData.lootPreview` + `lootDisplayMode`
- Seed history — `ConfigData.allSeeds`（MRU 排序，上限 1000）
- Two-level structure filter — `WorldConfig.disabledStructure`（`StructureBitFlag`；bit0 = 整类禁用，bit1+ = 变种禁用，默认全 0 = 全部可见；变种过滤只影响渲染，生成/缓存不变；可见性 = `!isStructureSet(id) && !isVariantSet(id, variant)` 由调用方组合）
- Disabled biomes — `WorldConfig.disabledBiomes`（`BitSet`；null = 全部启用）

## Lifecycle & API 要点

- `activate(MapProcessor)` / `deactivate()` 由 `WorldSwitchMixin` 在世界切换时调用（Xaero 处理线程），内部先 `save()` 旧配置再加载新 mainId。
- `save()` 仅当 `ConfigData.dirty` CAS 成功才落盘。
- `resolveSeed()`：单机返回服务端种子；多人查当前配置。
- `resolveDimId()`：主世界 0 / 下界 -1 / 末地 1 / 未知 `Integer.MIN_VALUE`。
- `saveConfig(base, mainId, cfg)` / `loadConfig(base, mainId)` 包私有，base 独立注入以便单测（不依赖 Minecraft 客户端）。

## Thread safety

`activate`/`deactivate`/`save` 为 `synchronized`；`activeMainId`/`activeMapProcessor`/`activeConfig` 是 `volatile`；`ConfigData` 用 `ConcurrentHashMap` + `synchronized` 保护种子历史与序列化。