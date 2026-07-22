# Seed Map For Xaero — AGENTS.md

## Build & Run

```bash
git submodule update --init              # cubiomes submodule
./gradlew build -x compileNativeWindows  # Linux .so + Java JAR
./gradlew runClient                      # launch Minecraft
```

**Java 25 required.** MC 26.1+ ships unobfuscated — no mapping needed.

C unit tests:

```bash
cmake -S src/main/c -B build-test -DCMAKE_BUILD_TYPE=Release
cmake --build build-test --target xsmtest
./build-test/xsmtest
```

Benchmark: add `-DDEBUG_TIMINGS=ON` to cmake configure.

jextract auto-downloaded on first build. Local copy: `-PjextractPath=/path/to/jextract`.
Skip native entirely: `-PskipNativeBuild`.

## Native build pipeline

`compileNative` (CMake) → `libxsmcore.so` → bundled in JAR.  
`generateNativeBindings` (jextract) → `all.h` → `XsmNative.java` (FFM, gitignored).  
`clean` deletes generated bindings.  
Windows cross-compile: `compileNativeWindows` via MinGW (`mingw-toolchain.cmake`).

## Release

`workflow_dispatch` in `.github/workflows/release.yml` with patch/minor/major choice. Auto-bumps `gradle.properties`, commits, tags (vX.Y.Z), builds native matrix, creates GitHub Release, publishes to Modrinth (projectId `UoJSF4vW`).

## Multiplayer & Config Persistence

### Seed resolution

`ServerConfig.resolveSeed()` — singleplayer → `Minecraft.server.getWorldGenSettings().options().seed()` directly. Multiplayer → lookup by `mwId` in config.

### Config storage

Path: `gameDir/xaero/seed-map-for-xaero/<mainId>/server_config.sm4x`  
`mainId` = Xaero world root (e.g. `Multiplayer_192.168.1.1`). Each multiplayer server gets its own file.

Format: custom binary (`ConfigData.write`/`read`, magic word + version 0). Not JSON.

### Activation flow

- **World switch** → `WorldSwitchMixin` on `MapProcessor.checkForWorldUpdate` detects `getCurrentWorldId()` change → `ServerConfig.activate(mp)` → saves old, loads new config, re-applies biome color table.
- **GuiMap init** → `SeedMapMixin.xsm$onGuiMapInit` also calls `ServerConfig.activate`.
- **Switching GUI** → `GuiMapSwitchingMixin` adds seed `EditBox` + confirm button, persists via `cfg.getOrCreateWorld(mwId).seed(seed)`.
- **Disconnect** → `XaeroSeedMapClient` registers `DISCONNECT` handler clears caches + deactivates.

### Persisted state

- Seed per (mainId, mwId) — `WorldConfig`
- Color theme name — `ConfigData.theme` (restored via `BiomeColorTable.resolveProvider()`)
- Toggle invisible — `ConfigData.invisible` (`SeedMapToggleMixin` reads/writes config)
- Seed history — `ConfigData.allSeeds` (capped 1000, MRU-ordered)

### Atomic save

`ServerConfig.save()`: write `.tmp` → rename existing → `.old` → `ATOMIC_MOVE` `.tmp` → target. Load falls back to `.old` if main corrupt.

### Thread safety

`ServerConfig.activate`/`deactivate`/`save` are `synchronized`. `activeMainId`, `activeMapProcessor`, `activeConfig` are `volatile`. `ConfigData` uses `ConcurrentHashMap` + `synchronized` blocks for seed history. `CellData.pixels` is `volatile`.

## Architecture

### Source layout (key packages)

```
src/client/java/bid/yuanlu/seedmap4xaero/client/
├── configs/          # ServerConfig, ConfigData, WorldConfig
├── nativeapi/        # Xsm.java (System.load + FFM wrappers)
├── cache/            # CellCache (per-scale GPU texture, TTL 100 ticks), QueryPointCache (LRU)
├── mixin/            # 6 client mixins (config in client .mixins.json)
├── render/           # BiomeColorTable + 3 providers (Native/Vanilla/Legacy)
└── accessor/         # SeedMapToggleAccessor interface
src/main/
├── java/…/XaeroSeedMap.java   # ModInitializer (empty)
├── resources/
└── c/                # cubiomes submodule + xsm/apis/render.cpp + unit_tests.cpp
```

### 6 client mixins

| Mixin                   | Targets                              | Role                                            |
| ----------------------- | ------------------------------------ | ----------------------------------------------- |
| `SeedMapMixin`          | `GuiMap.extractRenderState` + `init` | Main tile overlay render after Xaero's 2nd draw |
| `SeedMapCursorMixin`    | `GuiMap.extractRenderState`          | Replace coords/biome text for unexplored areas  |
| `SeedMapToggleMixin`    | `GuiMap.init`                        | "S" toggle button, state from config            |
| `BiomeColorSchemeMixin` | `GuiMap.init`                        | Color scheme cycle button, persists to config   |
| `WorldSwitchMixin`      | `MapProcessor.checkForWorldUpdate`   | Detect world change → reload config             |
| `GuiMapSwitchingMixin`  | `GuiMapSwitching.init`               | Seed input UI on world-switching panel          |

### Rendering flow

1. `GuiMap.extractRenderState` → `SeedMapMixin.renderSeedMapTiles` (after Xaero's 2nd draw)
2. `curScale` from `userScale`: ≥0.5→1, ≥0.125→4, ≥0.03125→16, ≥0.0078125→64, else 256 (overworld) / 64
3. Iterate visible `LeveledRegion`s; each cell: `CellCache.getOrRequest` → GPU texture or async gen
4. For regions with Xaero textures: 3-tier exploration detection + scanline merge
5. SuperScale (×4) fallback + SubScale (÷4) overlay when cur-scale not ready

### Tile coordinates

```java
int cellX = Math.floorDiv(worldX, 64 * scale);
int cellZ = Math.floorDiv(worldZ, 64 * scale);
```

`floorDiv` critical for negatives. Each `CellKey(scale, cellX, cellZ)` = 64×64 pixel texture = `64*scale` blocks.

### Exploration (3-tier)

For each 16×16 sub-tile:

1. **L1**: leaf `MapRegion` — `hasHadTerrain()` false → unexplored
2. **L2**: `MapTileChunk` within region — `hasHadTerrain()` false → unexplored
3. **L3**: `RegionTexture.getHeight()` — `!= 32767` means explored

`region == null` / `chunk == null` are **not** unexplored — always fall through to L3.

## Gotchas

- `genCellImg` C output is 64×64 **RGB** (3 bytes/pixel); Java converts to ABGR `int[]`
- `queryPoint` height uses `NP_DEPTH / 76.0` — not surface Y. Use `queryExactChunkHeight` for exact heights
- Terrain lighting: Overworld only (C-side hardcoded)
- MC version string map in `render.cpp` lines 22–60 must be updated for new MC releases
- Empty regions (no Xaero textures) filled without sub-tile exploration check
- Debug HUD always drawn at screen top center
- `Xsm.setBiomeColorTable` must be called before any gen — C-side defaults to black image
- `Xsm.setWorld(seed, dim)` is dedup-cached; world change calls `CellCache.clear()` + `QueryPointCache.clear()`
- Config file is **not JSON** — binary format with magic word. Corrupt file silently falls back to `.old` then fresh config
- LSP shows false errors for mixin targets and generated `XsmNative.java` — only `./gradlew build` is authoritative

## Dependencies

| Dependency      | Source                                                 |
| --------------- | ------------------------------------------------------ |
| Xaero World Map | `xaero.map:xaeroworldmap-fabric-26.1.2:1.41.0`         |
| cubiomes        | `src/main/c/cubiomes/` git submodule → `libxsmcore.so` |
| jextract        | Pre-built from jdk.java.net, auto-downloaded           |

## LSP tip

LSP may show false errors for mixin targets and generated `XsmNative.java`. Ignore them — only `./gradlew build` output is authoritative.
