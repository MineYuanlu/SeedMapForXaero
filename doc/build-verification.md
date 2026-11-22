# Build Provenance & Verification

This document explains how the published mod artifacts (including the bundled
native binaries) are built, and how anyone — including the Modrinth moderation
team — can independently verify that a published file matches the public
source code.

## What ships

One **universal JAR** (`seed-map-for-xaero-<version>.jar`) supports all
declared Minecraft versions. It bundles six native libraries built from the
C sources in this repository:

| Path inside JAR | Target |
| --------------- | ------ |
| `native/linux/x86_64/libxsmcore.so` | Linux x86_64 (glibc) |
| `native/linux/aarch64/libxsmcore.so` | Linux aarch64 (glibc) |
| `native/android/arm64/libxsmcore.so` | Android arm64 (bionic, NDK) |
| `native/android/x86_64/libxsmcore.so` | Android x86_64 (bionic, NDK) |
| `native/macos/universal/libxsmcore.dylib` | macOS arm64 + x86_64 |
| `native/windows/x86_64/xsmcore.dll` | Windows x86_64 (MinGW) |

Sources:

- `src/main/c/xsm/` — this project's native layer (API wrappers around cubiomes)
- `src/main/c/cubiomes/` — [cubiomes](https://github.com/xpple/cubiomes) as a git submodule (pinned commit)

All C sources are public in this repository. The Java side is compiled from
`src/main/java` and `src/client/java` by Gradle inside the same CI job.

## Automated build pipeline

Every release is built end-to-end by GitHub Actions. **No binary file ever
passes through human hands.** The maintainer only writes release-note text
(metadata); the exact JAR bytes uploaded to Modrinth and to GitHub Releases
are produced by the CI job and uploaded by the CI job itself.

Release flow (`.github/workflows/release.yml`, manual trigger only):

```
release.yml (workflow_dispatch)
├── bump job          — computes next semver from git tags, pushes tag vX.Y.Z
├── natives job       — calls reusable-native-build.yml
│                       (5 parallel compile jobs, one per native target)
├── notes job         — AI release notes (manual input → AI → git log fallback)
└── release job       — packaging steps inlined (same steps as reusable-package.yml, not via uses:)
    ├── gradle jar    — bundles the CI-built natives into the universal JAR
    ├── SHA256SUMS    — sha256 of the JAR, attached to the GitHub Release
    ├── attest        — SLSA build provenance (actions/attest-build-provenance)
    │                   for the JAR and SHA256SUMS
    ├── publishMods   — uploads the very same JAR file to Modrinth (Gradle
    │                   `publishMods`, project UoJSF4vW)
    └── GitHub Release— attaches the same JAR + SHA256SUMS to tag vX.Y.Z
```

The Modrinth upload and the GitHub Release therefore reference the **same
file produced by the same workflow run**, from the tag that names the release
(`vX.Y.Z`). Each GitHub Release page links to its workflow run; every run is
public and re-runnable from the repository.

## Toolchain pinning

Compiler toolchains are not left to chance (`.github/toolchain-versions.json`):

- **Windows**: each msys2 package is installed at the exact recorded version
  on every build (`pacman -U` from repo.msys2.org archives).
- **Android**: NDK is downloaded at the recorded exact version.
- **Linux/macOS**: `gcc` (ubuntu-24.04 runner image) / `brew cmake` (macos-15)
  float with the runner image, deliberately (see `refresh-toolchain.yml`):
  a weekly job computes the latest candidates, runs the *full* build + test
  gate on a candidate branch, and only fast-forwards master if everything is
  green. The recorded state is always "last verified working".

## Reproduce a build locally

Requirements: JDK 25, CMake, a C/C++ toolchain per target, and
`git submodule update --init`.

```bash
# Linux x86_64 / aarch64 (on the matching host)
cmake -S src/main/c -B build -DCMAKE_BUILD_TYPE=Release
cmake --build build --target xsmcore

# macOS universal (arm64 + x86_64 in one dylib)
cmake -S src/main/c -B build -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_OSX_ARCHITECTURES="arm64;x86_64"
cmake --build build --target xsmcore

# Windows x86_64 (msys2/mingw64 shell)
cmake -S src/main/c -B build -DCMAKE_BUILD_TYPE=Release
cmake --build build --target xsmcore --config Release

# Android (NDK; two builds, one per ABI)
cmake -S src/main/c -B build-android-arm64 -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_TOOLCHAIN_FILE=$ANDROID_NDK/build/cmake/android.toolchain.cmake \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-24 -DANDROID_STL=c++_static
cmake --build build-android-arm64 --target xsmcore
```

Then bundle into a JAR the same way CI does (native libs under
`src/main/c/build*/` are placed into `native/<os>/<arch>/` by Gradle):

```bash
./gradlew build -PskipNativeBuild -x compileNative -x compileNativeWindows
```

Note that locally-built native binaries will not be byte-identical to CI
builds (compiler build dates, image versions); byte-level reproducibility of
the C artifacts is not claimed. What **is** guaranteed is the provenance
chain below: the published JAR provably comes from this repository's
workflows at the tagged commit.

## Verify a published file

### 1. Checksums

Each release ships a `SHA256SUMS` file (next to the JAR on the GitHub
Release page). Compare the file you downloaded — e.g. from Modrinth —
against it:

```bash
sha256sum seed-map-for-xaero-<version>.jar
# must equal the line in SHA256SUMS, which is the GitHub Release JAR
```

A match proves the Modrinth file is the same bytes as the GitHub Release
artifact of tag `vX.Y.Z`.

### 2. Build provenance (SLSA attestation)

The JAR and `SHA256SUMS` carry GitHub-generated SLSA build provenance,
which cryptographically binds the file to a specific repository, workflow
run, and commit:

```bash
gh attestation verify seed-map-for-xaero-<version>.jar --repo MineYuanlu/SeedMapForXaero
```

The attestation lists the exact commit SHA, workflow name, and run URL —
a reviewer can open that run and inspect every build step, then diff the
sources at the recorded commit against what was compiled.
