# client — mixins & render pipeline

## 7 client mixins

| Mixin                   | Targets                              | Role                                            |
| ----------------------- | ------------------------------------ | ----------------------------------------------- |
| `SeedMapMixin`          | `GuiMap.extractRenderState` + `init` | Main tile overlay render after Xaero's 2nd draw |
| `SeedMapCursorMixin`    | `GuiMap.extractRenderState`          | Replace coords/biome text for unexplored areas  |
| `SeedMapToggleMixin`    | `GuiMap.init`                        | "S" toggle button, state from config            |
| `XsmMainPanelMixin`     | `GuiMap.init`                        | Settings panel button + mouse routing           |
| `WorldSwitchMixin`      | `MapProcessor.checkForWorldUpdate`   | Detect world change → reload config             |
| `GuiMapSwitchingMixin`  | `GuiMapSwitching.init`               | Seed input UI on world-switching panel          |
| `StructureOverlayMixin` | `GuiMap.extractRenderState`          | Structure icon overlay + hover tooltip          |

`BiomeColorSchemeMixin` was removed — color scheme switching is now handled through the `SeedMapPanel` side panel.

## Rendering flow

1. `GuiMap.extractRenderState` HEAD → `tickWorldInfo`: resolve seed/dim, call `Xsm.setWorld(seed, dim)` + `CacheHelper.setWorld` (clears all caches on change), apply biome disabled bitset, `CacheHelper.tick()`
2. Seed map tiles rendered after Xaero's 2nd draw via `renderSeedMapTiles` (injected at `INVOKE ordinal=1`)
3. `curScale` from `userScale`: ≥0.5→1, ≥0.125→4, ≥0.03125→16, ≥0.0078125→64, else 256 (overworld) / 64
4. Iterate visible `LeveledRegion`s; each cell: `CellCache.getOrRequest` → GPU texture or async gen on `CacheHelper.CACHE_WORKER` thread pool
5. For regions with Xaero textures: 3-tier exploration detection + scanline merge
6. SuperScale (×4) fallback + SubScale (÷4) overlay when cur-scale not ready
7. `CellCache.cancelStalePending` + `CellCache.cleanByTTL` called each frame
8. Structure overlay icons rendered after default framebuffer bind (from `StructureCache.REGIONS`, async via `CacheHelper.CACHE_WORKER`); STRONGHOLD enabled → also draw exact positions from `StructureCache.strongholds()` (`StrongholdCache`, ring-batched background)
9. Debug HUD drawn at screen top center — gated by `DEBUG = false` constant in `SeedMapMixin` (set to `true` to enable)

## Exploration (3-tier)

For each 16×16 sub-tile:

1. **L1**: leaf `MapRegion` — `hasHadTerrain()` false → unexplored
2. **L2**: `MapTileChunk` within region — `hasHadTerrain()` false → unexplored
3. **L3**: `RegionTexture.getHeight()` — `!= 32767` means explored

`region == null` / `chunk == null` are **not** unexplored — always fall through to L3.
