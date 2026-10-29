# cache — CellCache, GenScheduler, CacheHelper, BackgroundPregen, QueryPointCache, StructureCache, StrongholdCache

## Tile coordinates

```java
int cellX = Math.floorDiv(worldX, 64 * scale);
int cellZ = Math.floorDiv(worldZ, 64 * scale);
```

`floorDiv` critical for negatives. Each `CellKey(scale, cellX, cellZ)` = 64×64 pixel texture = `64*scale` blocks.

## CellCache（全局预算 LRU，非 TTL）

- 5 层 LOD（scale 1/4/16/64/256）各自 access-order LRU，**全局条目预算**共享
  （`PerfConfig.cacheCapacityEntries()`，1 条 ≈16KB CPU 像素 ≈16KB GPU 纹理，
  默认 128MB=8192 条、最小 64MB）。超限跨层驱逐 accessSeq 最小者。
- 同 seed+dim 会话内缓存常驻（关地图不过期，`CacheHelper.currentTick` 冻结不再
  影响瓦片）；世界/维度/版本/设置变更仍 `clear()`。
- **驱逐延迟关纹理**：被驱逐的 GPU 纹理进 `deferredClose`，`endFrame()`（draw 之后）
  才 `close()`——同帧 blit 可能仍引用它。
- **pending backpressure**：`MAX_PENDING`(512) 满时 `getOrRequest`/`prefetch` 不入队
  （画占位灰格）；CPU 像素尖峰 ≤8MB。
- **上传限流**：`MAX_UPLOADS_PER_FRAME`(8)/帧；`beginFrame(cameraX, cameraZ)` 清零并
  把相机喂给调度器（驱动优先级）。渲染帧顺序：`beginFrame` → fill/prefetch →
  `rendererProvider.draw` → `endFrame`。
- prefetch：`CellCache.prefetch(key, kind)`（`KIND_BACKGROUND` 视口粗层背景链 / `KIND_PREFETCH` 外圈环）入队（命中/pending 满即忽略）。
- C `genCellImg` 输出 RGBA 4B/px（LE int32 = ABGR），Java `Xsm.genCellImg` 整块
  `MemorySegment.copy` 进 `int[]`——无逐像素转换；`CellData.pixels` 是 `volatile`。
- 统计：`STAT_GEN_COUNT`/`STAT_GEN_NANOS`（debug HUD 消费），`entries()`/`pending()`。

## GenScheduler + CacheHelper（生成线程池）

- 单一调度器：即时 job（`CacheHelper.worker()` 提交——结构查询/要塞/chunk 高度/
  访问检测）恒优先于瓦片；瓦片按 **kind 升序（`CellCache.KIND_VISIBLE` <
  `KIND_BACKGROUND` < `KIND_PREFETCH`）> scale 粗 > 近相机 > FIFO** 取件
  （O(n) 扫描，n≤512；相机每帧经 `beginFrame` 更新 → 优先级实时重排）。
  kind 主键 = 预生成积压不饿死可见请求；BACKGROUND = 视口粗层背景链
  （prefetch 从 ×4 铺到维度最大 scale）。排序语义由 `GenSchedulerPriorityTest` 守护。
- 线程数 = `PerfConfig.effectiveGenerationThreads()`（自动 `max(1,min(4,cores/4))`），
  优先级 NORM-2；配置变更 → `rebuildPoolIfResized`（代数 +1，旧线程优雅退出）。
- **BackgroundPregen**（默认关）：游玩中（GuiMap 未打开）每 40 tick 以圆形半径
  `pregenRadiusBlocks` prefetch coarse 3 层 {dim 最大档, /4, /16}；细层永不预生成
  （论证见 doc/perf.md）。重复扫描天然回填 LRU 驱逐。

## Structure queries

- Structure queries are async via `CacheHelper.worker()`; results read from `StructureCache.REGIONS` each frame
- `StructureCache.updateStructuresInArea` uses diff-based logic — only queries newly visible regions (normal types via `TileCache`, region-count gate `MAX_REGION_HIDE`(16384))
- **Sparse structures** (regionSize=1: Treasure/Mineshaft/Desert_Well/Geode/End_Gateway/End_Island) go through `TileCache2` + C `querySparseStructures`: per-chunk low-probability scan, only hit positions stored (`blockX<<32|blockZ` in `LongOpenHashSet`, copy-on-write snapshot), cap = `MAX_SPARSE_HITS`(8192) with linear-index continuation (`*outNext`, same rect+excl, no rescan); per-frame `covered`/`pending` state machine scans only `V \ covered`. Worker completion **must not** filter hits by the current view — `covered` claims the enqueued rect regardless (viewport shrink cleanup is `retainIn`'s job); filtering there caused blank bands after zoom-out (80d091e). Gate: `ceil(regionCount × type.prob) > MAX_SPARSE_HITS` skips the type entirely (prob = placement rate = raw RNG prob × measured biome-pass rate from `tmp/struct-prob-test`; biome filtering happens in the C scan, so the gate estimates stored hits). End types only queryable in their own dim; `End_Island` skips `isViableStructurePos` (cubiomes always returns 0 — "no constraint") but 1.18+ markers are C-side filtered to `small_end_islands` biome chunks (`xsmEndIslandViable`), so `prob` overestimates hits outside that biome
- **Strongholds** have no region config — `StructureType.config == null` only for id 25 (warn suppressed). **FEATURE (id 0) is also config-less on MC > 1.12** (cubiomes `getStructureConfig` returns s_feature only for ≤ 1.12), so modern versions hit `config()==null` every frame; missing-config warn is now once-per-type and skipped for 25/0. Exact positions come from `StrongholdCache` + C `queryStrongholdsRange(from, to)`: positions follow a strict sequential RNG chain (cannot jump-skip), so the C side replays from index 0 each call (1.19.3+ passes `NULL` generator to skip biome search for out-of-range indices); 128 total (1.9+), computed ring-batched on the scheduler jobs (RING_ENDS {3,9,19,34,55,83,119,128}, snapshot published per ring, `generation` counter invalidates in-flight jobs on world/dim switch via `clear()`); ~7.8ms/stronghold per sandbox benchmark. Overworld-only, drawing gated on STRONGHOLD toggle; requires cubiomes `initFirstStronghold`/`nextStronghold`

## World switch

- `Xsm.setWorld(seed, dim)` is dedup-cached; world change calls `CacheHelper.setWorld` → clears `CellCache` + `QueryPointCache` + `StructureCache` + `StrongholdCache`（并 `SCHEDULER.clearTiles()`）
