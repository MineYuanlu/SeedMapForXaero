# cache — CellCache, QueryPointCache, StructureCache, StrongholdCache, CacheHelper

## Tile coordinates

```java
int cellX = Math.floorDiv(worldX, 64 * scale);
int cellZ = Math.floorDiv(worldZ, 64 * scale);
```

`floorDiv` critical for negatives. Each `CellKey(scale, cellX, cellZ)` = 64×64 pixel texture = `64*scale` blocks.

## Gotchas

- `Xsm.setWorld(seed, dim)` is dedup-cached; world change calls `CacheHelper.setWorld` → clears `CellCache` + `QueryPointCache` + `StructureCache` + `StrongholdCache`
- `CellData.pixels` is `volatile`
- Structure queries are async via `CacheHelper.CACHE_WORKER`; results read from `StructureCache.REGIONS` each frame
- `StructureCache.updateStructuresInArea` uses diff-based logic — only queries newly visible regions (normal types via `TileCache`, region-count gate `MAX_REGION_HIDE`(16384))
- **Sparse structures** (regionSize=1: Treasure/Mineshaft/Desert_Well/Geode/End_Gateway/End_Island) go through `TileCache2` + C `querySparseStructures`: per-chunk low-probability scan, only hit positions stored (`blockX<<32|blockZ` in `LongOpenHashSet`, copy-on-write snapshot), cap = `MAX_SPARSE_HITS`(8192) with linear-index continuation (`*outNext`, same rect+excl, no rescan); per-frame `covered`/`pending` state machine scans only `V \ covered`. Worker completion **must not** filter hits by the current view — `covered` claims the enqueued rect regardless (viewport shrink cleanup is `retainIn`'s job); filtering there caused blank bands after zoom-out (80d091e). Gate: `ceil(regionCount × type.prob) > MAX_SPARSE_HITS` skips the type entirely (prob = placement rate = raw RNG prob × measured biome-pass rate from `tmp/struct-prob-test`; biome filtering happens in the C scan, so the gate estimates stored hits). End types only queryable in their own dim; `End_Island` bypasses `isViableStructurePos` (cubiomes always returns 0 — "no constraint" semantics)
- **Strongholds** have no region config — `StructureType.config == null` only for id 25 (warn suppressed). Exact positions come from `StrongholdCache` + C `queryStrongholdsRange(from, to)`: positions follow a strict sequential RNG chain (cannot jump-skip), so the C side replays from index 0 each call (1.19.3+ passes `NULL` generator to skip biome search for out-of-range indices); 128 total (1.9+), computed ring-batched on `CACHE_WORKER` (RING_ENDS {3,9,19,34,55,83,119,128}, snapshot published per ring, `generation` counter invalidates in-flight jobs on world/dim switch via `clear()`); ~7.8ms/stronghold per sandbox benchmark. Overworld-only, drawing gated on STRONGHOLD toggle; requires cubiomes `initFirstStronghold`/`nextStronghold`
