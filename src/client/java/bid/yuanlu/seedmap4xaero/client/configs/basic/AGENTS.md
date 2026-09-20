# basic — server_config.json

配置文档：`gameDir/xaero/seed-map-for-xaero/<mainId>/server_config.json`，
每个多人服务器/单机世界根一个文件，按 Xaero 世界根 `mainId`（如 `Multiplayer_192.168.1.1`）隔离。
现行格式为**纯 JSON**（pretty）；旧版 `server_config.sm4x` 在加载链自动向上迁移（改名 `.sm4x.legacy`）。

## Classes

- `ServerConfig` — 门面：生命周期 + 便捷读写。外部统一从这里访问，公开 API 稳定。
  `save()`（rotate=true，仅世界切换等生命周期场景）/ `flush()`（rotate=false，会话内刷写）
  ——轮替契约见 `core/AGENTS.md`。脏标志 CAS-claim，**写盘失败恢复脏标志**（本轮变更不丢）。
- `ConfigData` — 数据体：服务器级设置 + `worlds` 表 + 种子历史；`JSON_CODEC`（现行）+
  `LEGACY_CODEC`（frozen 二进制，仅迁移/fixture）。
- `WorldConfig` — 单个 (mainId, mwId) 的世界配置，构造函数包私有，经 `ConfigData.getOrCreateWorld(mwId)` 创建。
  **禁用结构/群系持久化为稳定 key**（`minecraft:village` / `minecraft:plains`，转换边界
  `StructureKeys`/`BiomeKeys`）；运行时仍为 id 索引（`StructureBitFlag`/`BitSet`）。
  无法识别的 key 存 orphan 字段原样并回（数据不丢）。
- `LootDisplayMode` — loot 预览显示模式枚举（交互 × 布局两个正交维度）。

## JSON 文档结构（v1）

```
{ "version": 1, "theme", "invisibleBiomes", "invisibleStructures", "structureIconSize",
  "lootPreview", "lootDisplayMode",
  "worlds": { mwId: { "seed", "mcVersion",
      "disabledStructures": { "minecraft:village": {"whole": true, "variants": [8]} },
      "disabledBiomes": ["minecraft:plains", "id:177"] } },
  "seedHistory": [{ "seed", "lastUsed" }] }
```
- 只存**被禁用**的条目（缺省 = 全可见，天然前向兼容）。
- `disabledStructures`：bit0=整类（`whole`），bit(1+v)=变种码 v（0..30）。
- `disabledBiomes`：注册表未覆盖的 id 用 `id:N` 数字占位（往返无损）。
- null 值字段省略（seed/mcVersion/theme）。

## Persisted state

- Seed per (mainId, mwId) — `WorldConfig.seed`（缺省 = 未设置）
- World-gen MC version — `WorldConfig.mcVersion`（缺省 = 跟随客户端；每帧 `tickWorldInfo` 经 `Xsm.applyGameVersion` 应用）
- Color theme / invisible 开关 ×2 / icon size / loot 设置 — `ConfigData` 顶层
- Seed history — `ConfigData.seedHistory`（MRU，上限 1000）
- Two-level structure filter — `WorldConfig.disabledStructure`（bit0=整类，bit1+=变种；可见性由调用方组合）
- Disabled biomes — `WorldConfig.disabledBiomes`（缺省 = 全部启用）

## Lifecycle & API 要点

- `activate(MapProcessor)` / `deactivate()` 由 `WorldSwitchMixin` 在世界切换时调用（Xaero 处理线程），内部先 `save()` 旧配置再加载新 mainId。
- `VersionDropdown`（MC 版本选择）与 `GuiMapSwitchingMixin`（种子输入）是**会话内**变更 → 必须 `flush()`。
- 周期刷盘（60s，`XaeroSeedMapClient`）→ `flush()`。
- `resolveSeed()`：单机返回服务端种子；多人查当前配置。
- `resolveDimId()`：主世界 0 / 下界 -1 / 末地 1 / 未知 `Integer.MIN_VALUE`。
- `saveConfig(base, mainId, cfg, rotate)` / `loadConfig(base, mainId)` 包私有，base 独立注入以便单测。

## Thread safety

`activate`/`deactivate`/`save`/`flush` 为 `synchronized`；`activeMainId`/`activeMapProcessor`/`activeConfig` 是 `volatile`；`ConfigData` 用 `ConcurrentHashMap` + `synchronized` 保护种子历史与序列化。
