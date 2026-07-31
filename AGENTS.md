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
Skip native entirely: `-PskipNativeBuild`. Skip Windows only: `-PskipNativeWindows`.
Local overrides via `gradle.local.properties` (gitignored, same key format).

## Native build pipeline

`compileNative` (CMake) → `libxsmcore.so` → bundled in JAR.  
`generateNativeBindings` (jextract) → `all.h` → `XsmNative.java` (FFM, gitignored).  
`clean` deletes generated bindings + `src/main/c/build*`.  
Windows cross-compile: `compileNativeWindows` via MinGW (`mingw-toolchain.cmake`).

## Release

`workflow_dispatch` in `.github/workflows/release.yml` with patch/minor/major choice. Auto-bumps `gradle.properties`, commits, tags (vX.Y.Z), builds native matrix, creates GitHub Release, publishes to Modrinth (projectId `UoJSF4vW`).

## Architecture

### Source layout

Folder-scoped docs (see the `AGENTS.md` in each tree — loaded automatically when reading files there):

```
src/client/java/bid/yuanlu/seedmap4xaero/client/
├── configs/          # AGENTS.md: config persistence & multiplayer
├── nativeapi/        # Xsm.java (System.load + FFM wrappers), XsmNative.java (generated)
├── cache/            # AGENTS.md: caches, sparse structures, strongholds, tile coords
├── mixin/            # 7 mixins — table in client/AGENTS.md
├── render/           # BiomeColorTable + 3 providers (Native/Vanilla/Legacy)
├── structure/        # StructureType enum (26 types, config from C, 稀疏类型自带 prob)
├── biome/            # BiomeType (sprite index, loaded from biomes.ini)
├── gui/              # AGENTS.md: SeedMapPanel side panel, XsmIconButton
├── utils/            # BitSetView (immutable BitSet wrapper)
└── accessor/         # SeedMapToggleAccessor interface
src/main/
├── java/…/XaeroSeedMap.java   # ModInitializer (empty)
├── resources/assets/seed-map-for-xaero/
│   ├── lang/en_us.json, zh_cn.json   # i18n
│   └── textures/icons/               # biomes.png, structures.png, biomes.ini
└── c/                # cubiomes submodule + xsm/ (AGENTS.md: C API & gotchas)
tools/                # gen_biomes_icon.py, gen_structures_icon.py, generate_lib_src.py
```

### Cross-cutting rules

- All UI strings go through i18n (`Component.translatable` / `I18n.get`) — add both `en_us.json` and `zh_cn.json`
- LSP maybe shows false errors when edit java files — only `./gradlew build` is authoritative
- Config file is **not JSON** — binary format with magic word; corrupt file silently falls back to `.old` then fresh config (details in `configs/AGENTS.md`)

## Dependencies

| Dependency      | Source                                                 |
| --------------- | ------------------------------------------------------ |
| Xaero World Map | `xaero.map:xaeroworldmap-fabric-26.1.2:1.41.0`         |
| cubiomes        | `src/main/c/cubiomes/` git submodule → `libxsmcore.so` |
| jextract        | Pre-built from jdk.java.net, auto-downloaded           |

### Dependencies src

- `refs/lib_src/xaeroworldmap` Xaero World Map source code (decompiled)
- `refs/lib_src/xaerolib` XaeroLib source code (decompiled)
