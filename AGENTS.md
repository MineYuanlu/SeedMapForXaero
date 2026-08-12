# Seed Map For Xaero — AGENTS.md

## Build & Run

```bash
git submodule update --init              # cubiomes submodule
./gradlew build -x compileNativeWindows  # Linux .so + Java JAR
./gradlew runClient                      # launch Minecraft
```

**Java 25 required.** MC 26.1+ ships unobfuscated — no mapping needed.

Benchmark: add `-DDEBUG_TIMINGS=ON` to cmake configure.

jextract auto-downloaded on first build. Local copy: `-PjextractPath=/path/to/jextract`.
Skip native entirely: `-PskipNativeBuild`. Skip Windows only: `-PskipNativeWindows`.
Local overrides via `gradle.local.properties` (gitignored, same key format).

## Testing

三层测试（详见 `doc/testing.md`）：JVM 单测 → native 集成 → E2E 客户端 GameTest。

```bash
./gradlew build -x compileNativeWindows          # JVM 单测 + native 集成
cmake -S src/main/c -B build-test -DCMAKE_BUILD_TYPE=Release
cmake --build build-test --target xsmtest && ./build-test/xsmtest   # C 单测
./gradlew runProductionClientGameTest -PskipNativeWindows=true      # E2E（真实启动 MC）
./gradlew runProductionClientGameTestUniversal -PskipNativeWindows=true \
  -PuniversalJar="$(find build/libs -name 'seed-map-for-xaero-*.jar' ! -name '*-sources.jar' | head -1)" \
  # E2E（复用预构建 universal jar；排除 -sources.jar，其 fabric.mod.json 是未展开的 ${version} 模板）
```

**Single universal jar**: `fabric.mod.json` hardcodes `"minecraft": ">=26.1"` (not templated). The published jar is compiled against the oldest Xaero line (1.40.14) so referenced symbols are a subset of all supported versions. CI (`matrix-test.yml`): `test` = 8-combo compile+JUnit (source-compat early warning, not published), `build-universal` = build the one jar, `universal-e2e` = run that same jar on all 4 MC × newest Xaero. Future breaking MC versions are caught by universal-e2e as `versions.json` grows.

版本参数：`gradle.properties` 的 key 即 CI `-P` 覆盖的 key（`fabricApiVersion`/`xaeroMapLine`/`xaeroMapVersion`）。
CI 矩阵 + E2E 定义在 `.github/workflows/matrix-test.yml`。

### CI 触发矩阵（单人开发流程）

开发流程：feature 分支只跑快速检查；**develop 是合入 master 前的完整门禁**（版本矩阵 + E2E + 全平台 native + 打包全绿才合）；master 再次全量确认；tag 触发的 build 供发版引用。

| 触发 | `pr-check.yml` | `matrix-test.yml` | `build.yml` |
| ---- | -------------- | ----------------- | ----------- |
| `dev/xxx`、`fix/xxx` 推送 | ✅ 快速检查（C 单测 + JUnit + JAR） | — | — |
| develop 推送 | — | ✅ 版本矩阵 + E2E | ✅ 全平台 native + 打包 |
| master 推送 | — | ✅ 版本矩阵 + E2E | ✅ 全平台 native + 打包 |
| tag `v*` 推送 | — | — | ✅ 全平台 native + 打包 |
| pull_request | ✅ 快速检查 | — | — |
| 手动 | — | ✅ | build-test-jar / release |

## Native build pipeline

Single **universal JAR** bundles all native libs under `/native/<os>/<arch>/`; `Xsm.loadNativeLibrary` picks by `os.name` × `os.arch` × Android:

```
native/linux/x86_64/libxsmcore.so    glibc（桌面/服务器，Linux x64）
native/linux/aarch64/libxsmcore.so   glibc（树莓派/ARM 云等）
native/android/arm64/libxsmcore.so   bionic/NDK —— FCL/Pojav 真机（★关键）
native/android/x86_64/libxsmcore.so  bionic/NDK（FCL 模拟器/罕见 x86 Android）
native/macos/universal/libxsmcore.dylib   universal，arm64+x86_64 一个文件
native/windows/x86_64/xsmcore.dll
```

- **Android ≠ Linux-aarch64**：FCL/Pojav 的 JVM 链接 bionic（无版本化 SONAME），glibc 交叉编译的 `.so` 加载不了。必须用 Android NDK（`compileNativeAndroid`，需 `-PndkPath=<ndk>` 或 `ANDROID_NDK_HOME`）。FCL 用 `checkElfIsAndroid()`（DT_NEEDED 无 `libc.so.6`）判断。
- macOS universal：`compileNative` 在 mac 上自动加 `-DCMAKE_OSX_ARCHITECTURES=arm64;x86_64`，一个 dylib 覆盖 Intel + Apple Silicon。
- Linux aarch64：CI 用原生 arm64 runner 构建；本地可用 `compileNativeLinuxArm`（`aarch64-linux-gnu-gcc` 交叉，glibc，见 `linux-arm-toolchain.cmake`）。
- `generateNativeBindings` (jextract) → `all.h` → `XsmNative.java` (FFM, gitignored)。绑定硬编码 LP64，跨架构复用一份。
- `clean` deletes generated bindings + `src/main/c/build*`.
- Windows cross-compile: `compileNativeWindows` via MinGW (`mingw-toolchain.cmake`).
- CI 编译 job 各自产出 `src/main/c/build/<target>/`，汇总进 JAR。全平台 native 矩阵（5 个 compile job）统一在 `reusable-native-build.yml`；下载 natives + 打包 universal JAR 统一在 `reusable-package.yml`（含 JAR 内置校验）。`build.yml`（develop/master/tag push）、`release.yml`、`build-test-jar.yml` 均通过 `uses:` 调用这两个可复用 workflow；feature 分支/PR 的快速检查在 `pr-check.yml`。

## Release

`workflow_dispatch` in `.github/workflows/release.yml` with patch/minor/major choice. Auto-bumps `gradle.properties`, commits, tags (vX.Y.Z), builds native matrix, creates GitHub Release, publishes to Modrinth (projectId `UoJSF4vW`).

`build-test-jar.yml`（workflow_dispatch）：手动产一个 universal JAR 供人工测试，无 bump/tag/发布（调用 `reusable-native-build` + `reusable-package`）。`ref` input 指定分支/tag/SHA（默认 `master`），`runTests` 开关打包时的 C 单测。产物含内置校验：打包后逐项断言 JAR 内含全平台 6 个 native 文件。

## Architecture

### Source layout

Folder-scoped docs (see the `AGENTS.md` in each tree — loaded automatically when reading files there):

```
src/client/java/bid/yuanlu/seedmap4xaero/
├── client/            # mixin + render pipeline (client/AGENTS.md)
│   ├── configs/       # AGENTS.md: config persistence & multiplayer
│   ├── nativeapi/     # Xsm.java (System.load + FFM wrappers), XsmNative.java (generated)
│   ├── cache/         # AGENTS.md: caches, sparse structures, strongholds, tile coords
│   ├── mixin/         # 7 mixins — table in client/AGENTS.md
│   ├── render/        # BiomeColorTable + 3 providers (Native/Vanilla/Legacy)
│   ├── structure/     # StructureType enum (26 types, config from C, 稀疏类型自带 prob)
│   ├── biome/         # BiomeType (sprite index, loaded from biomes.ini)
│   ├── gui/           # AGENTS.md: SeedMapPanel side panel, XsmIconButton
│   └── accessor/      # SeedMapToggleAccessor interface
└── utils/             # BitSetView (immutable BitSet wrapper)
src/client/resources/assets/seed-map-for-xaero/textures/icons/   # biomes.png, structures.png, biomes.ini
src/main/
├── java/…/XaeroSeedMap.java   # ModInitializer (empty)
├── resources/          # fabric.mod.json + mixins json + lang/ + icon.png
└── c/                # cubiomes submodule + xsm/ (AGENTS.md: C API & gotchas)
src/gametest/         # E2E client gametest (see doc/testing.md)
src/test/             # JUnit 单测 + native 集成测试 (see doc/testing.md)
tools/                # resolve_versions.py, gen_biomes_icon.py, gen_structures_icon.py, generate_lib_src.py
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
