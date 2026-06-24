# Seed Map For Xaero — AGENTS.md

## Build & Run

```bash
git submodule update --init              # cubiomes submodule
./gradlew build -x compileNativeWindows  # Linux .so + Java JAR
./gradlew runClient                      # launch Minecraft
```

C unit tests:
```bash
cmake -S src/main/c -B build-test -DCMAKE_BUILD_TYPE=Release
cmake --build build-test --target xsmtest
./build-test/xsmtest
```

jextract auto-downloaded from jdk.java.net on first `./gradlew build`. To use a local copy: `./gradlew build -PjextractPath=/path/to/jextract`.

To skip native compilation: `./gradlew build -PskipNativeBuild`.

**Java 25 required.** No obfuscation maps needed (MC 26.1+ ships unobfuscated).

## Native build pipeline

`compileNative` (CMake) → `libxsmcore.so` → bundled in JAR.  
`generateNativeBindings` (jextract) → reads `src/main/c/xsm/apis/all.h` → `XsmNative.java` (FFM).  
Generated `XsmNative.java` is gitignored; `clean` deletes it.

Cross-compile for Windows: `compileNativeWindows` uses MinGW via `mingw-toolchain.cmake`.

## Architecture

### Source layout

```
src/
├── main/
│   ├── java/bid/yuanlu/seedmap4xaero/
│   │   └── XaeroSeedMap.java                   # ModInitializer (empty LOGGER + id())
│   ├── resources/fabric.mod.json
│   └── c/                                       # Native C (cubiomes submodule + xsm/)
│       ├── xsm/apis/render.cpp                  # C entry: genCellImg, queryPoint, queryExactChunkHeight
│       ├── xsm/apis/all.h                       # jextract input header
│       ├── xsm/test/unit_tests.cpp              # C unit tests (doctest)
│       └── CMakeLists.txt
└── client/
    └── java/bid/yuanlu/seedmap4xaero/client/
        ├── XaeroSeedMapClient.java              # Client entry: setBiomeColorTable, setGameVersion
        ├── nativeapi/Xsm.java                   # System.load + thin FFM wrappers
        ├── cache/
        │   ├── CellCache.java                   # Per-scale(1,4,16,64,256) GPU texture cache, TTL 100 ticks
        │   ├── CacheHelper.java                 # Tick counter + CACHE_WORKER pool (n/2 threads)
        │   └── QueryPointCache.java             # LRU cache for queryPoint, chunk-height batched
        ├── mixin/
        │   ├── SeedMapMixin.java                # Main render: inject after Xaero's 2nd draw()
        │   ├── SeedMapCursorMixin.java          # Replace coords/biome text for unexplored areas
        │   ├── SeedMapToggleMixin.java          # "S" toggle button on GuiMap
        │   └── BiomeColorSchemeMixin.java       # Color scheme cycle button
        ├── render/
        │   ├── BiomeColorTable.java             # Registry of 3 color providers
        │   ├── BiomeColorProvider.java          # Interface
        │   ├── NativeBiomeColor.java            # C-side color (native table)
        │   ├── VanillaBiomeColor.java           # Minecraft vanilla colors
        │   └── LegacyBiomeColor.java            # Pre-1.21 color scheme
        └── accessor/SeedMapToggleAccessor.java  # Interface for toggle state
```

### Rendering flow

1. `GuiMap.extractRenderState` → `SeedMapMixin.renderSeedMapTiles` (after Xaero's own 2nd draw)
2. Determine `curScale` from `userScale`: ≥0.5→1, ≥0.125→4, ≥0.03125→16, ≥0.0078125→64, else 256 (overworld) / 64
3. Iterate visible `LeveledRegion`s in camera viewport
4. For each region:
   - If region has no textures: full-cell fill via `CellCache.getOrRequest`
   - If region has textures: 3-tier exploration detection (`xsm$fillCellGaps`) + scanline merge
5. SuperScale (×4) fallback: peek coarser scale when cur-scale cell not ready
6. SubScale (÷4) overlay: peek finer scale for detail where available

### Tile coordinates

```java
int cellX = Math.floorDiv(worldX, 64 * scale);
int cellZ = Math.floorDiv(worldZ, 64 * scale);
```

`floorDiv` is critical for negative coordinates. Each `CellKey(scale, cellX, cellZ)` maps to a 64×64 pixel texture covering `64*scale` blocks.

### Exploration detection (3-tier)

Sub-tiles (16×16 blocks within a cell) are tested:

1. **L1**: Leaf `MapRegion` (512-block) — `hasHadTerrain()` false → confirmed unexplored
2. **L2**: `MapTileChunk` (64-block) within region — `hasHadTerrain()` false → confirmed unexplored
3. **L3**: `RegionTexture.getHeight()` → `!= 32767` means explored

`region == null` and `chunk == null` are **not** unexplored — branch textures may still have cached data. Always fall through to L3.

### Color schemes

3 providers cycled via the color button (right side of GuiMap):
- **Native** — C-side biome color table (default)
- **Vanilla** — Java-side MC vanilla biome colors
- **Legacy** — Pre-1.21 color scheme

`Xsm.setBiomeColorTable(BiomeColorTable.getProvider())` must be called in `onInitializeClient` before any gen — C-side defaults to black image.

## Key gotchas

- `Xsm.setWorld(seed, dim)` is dedup-cached; world changes call `CellCache.clear()` + `QueryPointCache.clear()`
- `genCellImg` C output is 64×64 **RGB** (3 bytes/pixel); Java converts to ABGR `int[]`
- `queryPoint` height uses `NP_DEPTH / 76.0` approximation — not actual surface Y. Use `queryExactChunkHeight` for exact heights
- Terrain lighting only applies in Overworld (C-side hardcoded)
- MC version string map (`render.cpp` lines 22–60) must be updated for new releases
- `CellCache` has 5 independent per-scale caches (`LinkedHashMap` with TTL=100 ticks)
- Async gen runs on `CacheHelper.CACHE_WORKER` (fixed pool, `max(1, n/2)` threads)
- GPU texture upload happens on render thread (in `getGpuTex` when pixels present)
- `empty` regions (no Xaero textures) are filled without any sub-tile exploration check
- Debug HUD (scale + fillGaps stats) is always drawn at screen top center

## Dependencies

| Dependency | Source |
|---|---|
| Xaero World Map | `xaero.map:xaeroworldmap-fabric-26.1.2:1.41.0` — compile + runtime |
| cubiomes | `src/main/c/cubiomes/` git submodule → `libxsmcore.so` |
| jextract | Pre-built from jdk.java.net, auto-downloaded at build time |

## LSP tip

LSP may show false errors for mixin targets and generated `XsmNative.java`. Ignore them — only `./gradlew build` output is authoritative.
