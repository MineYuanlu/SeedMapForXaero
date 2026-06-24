# Seed Map For Xaero

在 Xaero's World Map 上显示基于种子的生物群系预览和结构数据。

> **Not affiliated with or endorsed by xaero96. Requires Xaero's World Map.**

## 架构概述

Fabric 模组，通过 Mixin 注入 Xaero World Map 的渲染管线，利用 cubiomes 原生库快速生成生物群系和地形高度数据，并以叠加纹理方式渲染到地图上。

### 核心组件

- **C 原生层** (`src/main/c/xsm/apis/render.cpp`) — 基于 cubiomes 的生物群系生成与地形光照，编译为 `libxsmcore.so`（Linux）/ `xsmcore.dll`（Windows）/ `libxsmcore.dylib`（macOS）
- **Java FFM 绑定** (`src/client/.../nativeapi/Xsm.java`) — 通过 jextract 生成的 FFM 接口调用 C 侧函数
- **缓存系统** (`CellCache` + `QueryPointCache`) — 多级缩放（1, 4, 16, 64, 256）的 GPU 纹理缓存，异步生成
- **Mixin 注入** — `SeedMapMixin`（渲染）、`SeedMapCursorMixin`（光标信息）、`SeedMapToggleMixin`（开关）、`BiomeColorSchemeMixin`（配色切换）

### 渲染流程

1. 在 Xaero 的 `GuiMap.extractRenderState` 第二次 draw 后注入
2. 根据 `userScale` 选择 cell 缩放层级
3. 遍历视口内可见的 Xaero `LeveledRegion`
4. 对无 Xaero 数据的区域：直接全 cell 填充种子数据
5. 对有 Xaero 数据的区域：三级决策树检测探索状态，仅填缝未探索子块
6. SuperScale/subScale 降级策略：粗粒度覆盖 + 细粒度叠加

### 探索检测（三级决策树）

对每个 16×16 子块依次判断：

1. **L1**: 叶子级 `MapRegion` — `hasHadTerrain() == false` 确认未探索
2. **L2**: `MapTileChunk` — `hasHadTerrain() == false` 确认未探索
3. **L3**: `RegionTexture.getHeight() != 32767` 表示已探索

`region == null` 或 `chunk == null` 不代表未探索，必须降级到 L3 通过 `getHeight()` 终审。

## 构建与运行

### 前置要求

- **JDK 25**（必须）
- **git submodule**（cubiomes）

### 命令

```bash
# 克隆子模块（首次）
git submodule update --init

# 构建（Linux .so + Java JAR）
./gradlew build -x compileNativeWindows

# 运行 Minecraft
./gradlew runClient
```

jextract 在首次构建时自动从 jdk.java.net 下载。如需使用本地拷贝：

```bash
./gradlew build -PjextractPath=/path/to/jextract
```

跳过原生编译（仅构建 Java）：

```bash
./gradlew build -PskipNativeBuild
```

### C 单元测试

```bash
cmake -S src/main/c -B build-test -DCMAKE_BUILD_TYPE=Release
cmake --build build-test --target xsmtest
./build-test/xsmtest
```

开启性能计时：

```bash
cmake -S src/main/c -B build-test -DCMAKE_BUILD_TYPE=Release -DDEBUG_TIMINGS=ON
```

## CI/CD

GitHub Actions 在每次推送和 PR 时自动运行。详见 `.github/workflows/build.yml`。

| 事件                  | 行为                                            |
| --------------------- | ----------------------------------------------- |
| 非 master 分支推送/PR | Linux 构建 + C 测试 + JAR                       |
| master 分支推送/PR    | 三平台原生编译（Linux/Windows/macOS）+ 打包 JAR |
| Tag 推送 (`v*.*.*`)   | master 构建 + 标签校验 + GitHub Release         |

## 技术栈

| 组件                | 说明                                                                                                            |
| ------------------- | --------------------------------------------------------------------------------------------------------------- |
| **Minecraft**       | 26.1.2（无混淆映射）                                                                                            |
| **Fabric Loom**     | 1.17                                                                                                            |
| **Fabric API**      | 0.153.0+26.1.2                                                                                                  |
| **Xaero World Map** | 1.41.0                                                                                                          |
| **cubiomes**        | Git submodule（C 原生库），[Cubitect](https://github.com/Cubitect) 原作，[xpple](https://github.com/xpple) 维护 |
| **Java**            | 25（FFM API）                                                                                                   |
| **构建工具**        | Gradle + CMake                                                                                                  |

## 配色方案

内置三种配色方案，可通过地图右侧按钮切换：

- **Native**（默认） — C 侧原生生物群系颜色表
- **Vanilla** — Minecraft 原版颜色
- **Legacy** — 1.21 前的旧版配色

## 致谢

- **[cubiomes](https://github.com/xpple/cubiomes)** — 核心生物群系/结构/地形生成引擎。感谢原作者 [Cubitect](https://github.com/Cubitect) 创建这一出色的库，以及 [xpple](https://github.com/xpple) 的持续维护与增强。
- **[Xaero's World Map](https://modrinth.com/mod/xaeros-world-map)** — 本模组作为其附属运行。感谢 xaero96 开发了极佳的地图模组。

## 许可证

MIT License © 2026 yuanlu (yuanlu.bid)
