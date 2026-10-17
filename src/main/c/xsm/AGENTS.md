# xsm — C core (cubiomes wrapper)

- `./apis/` — C API surface for Java FFM (Xsm.java / generated XsmNative.java)
- `./pretools/` — dev tooling (tool.cpp — biome id→name dump)
- `./sandbox/` — dev test sandbox
- `./test/` — unit tests (unit_tests.cpp, target `xsmtest`)
- `./utils/` — shared C utilities

## API gotchas

- `genCellImg` C output is 64×64 **RGB** (3 bytes/pixel); Java converts to ABGR `int[]`
- `queryPoint` height uses `NP_DEPTH / 76.0` — not surface Y. Use `queryExactChunkHeight` for exact heights
- Terrain lighting: Overworld only (C-side hardcoded)
- MC version string map in `render.cpp` (`mcVersionMap`, ~line 26–64) must be updated for new MC releases; sync the UI picker list in Java `Xsm.SUPPORTED_VERSIONS` (descending, 26.1+ only — C supports more legacy versions than the UI exposes)
- `Xsm.setBiomeColorTable` must be called before any gen — C-side defaults to black image
- `queryRegionStructuresGrid`: dim filter (sconf.dim != current dim → return 0); needed because same-salt sibling types (Ruined_Portal/Ruined_Portal_N) both pass `isViableStructurePos` in the wrong dim → overlapping positions
- `querySparseStructures`: dim filter (wrong-dim `isViableStructurePos` prints stderr + meaningless positions); `End_Island` skips `isViableStructurePos` (cubiomes always returns 0 — "no constraint") but 1.18+ requires the chunk biome to be `small_end_islands` (real floating islands only generate there, per cubiomes `mapEndIslandHeight`/`isEndChunkEmpty`); cap → `*outNext` linear index, `-1` = scan complete
- `queryStrongholdsRange`: replays RNG chain from index 0 (positions are sequential — cannot jump-skip); `mc > 1.19.2` passes `NULL` generator to skip biome search for out-of-range indices; overworld only; returns count of positions in `[from, to)`
- New C API functions must be added to `includes.txt` for jextract to pick them up
- Benchmark with `-DDEBUG_TIMINGS=ON`; tests via `./build-test/xsmtest`
