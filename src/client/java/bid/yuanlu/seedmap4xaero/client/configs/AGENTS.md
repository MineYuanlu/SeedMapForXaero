# configs — persistence & multiplayer

## Seed resolution

`ServerConfig.resolveSeed()` — singleplayer → `Minecraft.server.getWorldGenSettings().options().seed()` directly. Multiplayer → lookup by `mwId` in config.

## Config storage

Path: `gameDir/xaero/seed-map-for-xaero/<mainId>/server_config.sm4x`  
`mainId` = Xaero world root (e.g. `Multiplayer_192.168.1.1`). Each multiplayer server gets its own file.

Format: custom binary (`ConfigData.write`/`read`, magic word + version 0). **Not JSON.**

## Activation flow

- **World switch** → `WorldSwitchMixin` on `MapProcessor.checkForWorldUpdate` detects `getCurrentWorldId()` change → `ServerConfig.activate(mp)` → saves old, loads new config, re-applies biome color table.
- **GuiMap init** → `SeedMapMixin.xsm$onGuiMapInit` also calls `ServerConfig.activate`.
- **Switching GUI** → `GuiMapSwitchingMixin` adds seed `EditBox` + confirm button, persists via `cfg.getOrCreateWorld(mwId).seed(seed)`.
- **Disconnect** → `XaeroSeedMapClient` registers `DISCONNECT` handler clears caches + deactivates.

## Persisted state

- Seed per (mainId, mwId) — `WorldConfig`
- Color theme name — `ConfigData.theme` (restored via `BiomeColorTable.resolveProvider()`)
- Toggle invisible biomes — `ConfigData.invisibleBiomes` (`SeedMapToggleMixin` reads/writes config)
- Toggle invisible structures — `ConfigData.invisibleStructures` (separate from biomes)
- Structure icon size — `ConfigData.structureIconSize` (float 0.05~2.0, persisted)
- Seed history — `ConfigData.allSeeds` (capped 1000, MRU-ordered)
- Enabled structure types — `WorldConfig.enabledStructures` (`BitSet`, persisted per mwId)
- Disabled biome types — `WorldConfig.disabledBiomes` (`BitSet`, persisted per mwId)

## Atomic save

`ServerConfig.save()`: write `.tmp` → rename existing → `.old` → `ATOMIC_MOVE` `.tmp` → target. Load falls back to `.old` if main corrupt.

## Thread safety

`ServerConfig.activate`/`deactivate`/`save` are `synchronized`. `activeMainId`, `activeMapProcessor`, `activeConfig` are `volatile`. `ConfigData` uses `ConcurrentHashMap` + `synchronized` blocks for seed history.
