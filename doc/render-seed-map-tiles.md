# SeedMapMixin — 探索检测与优化指南

## 概述

`xsm$fillGaps` 遍历种子地图 tile 内的 16×16 子tile，判断每个子tile 是否已被 Xaero World Map 探索。若已探索则保留 Xaero 画面，若未探索则覆盖种子地图纹理。

## Xaero 数据结构

| 层级                | 覆盖            | 对应类                                   | 快速检测方法              |
| ------------------- | --------------- | ---------------------------------------- | ------------------------- |
| MapRegion (leaf L0) | 512×512 方块    | `xaero.map.region.MapRegion`             | `hasHadTerrain()`         |
| MapTileChunk        | 64×64 方块      | `xaero.map.region.MapTileChunk`          | `hasHadTerrain()`         |
| RegionTexture pixel | 2^texLevel 方块 | `xaero.map.region.texture.RegionTexture` | `getHeight(x,z) != 32767` |

### 获取方式

- **MapRegion**: `mapProcessor.getLeafMapRegion(caveLayer, wx>>9, wz>>9, false)` —— 仅查叶子级
- **MapTileChunk**: `region.getChunk((wx>>6)&7, (wz>>6)&7)` —— 叶子级 region 的 8×8 子块
- **LeveledRegion + RegionTexture**: `getLeveledRegion(caveLayer, wx>>(9+texLevel), wz>>(9+texLevel), texLevel)` → `getTexture(ltX, ltZ)` → `getHeight(lpX, lpZ)`

其中 texLevel（0-3）由 `xsm$textureLevelForScale(userScale)` 根据当前缩放比计
算。

### 高度哨兵值

`RegionTexture.getHeight()` 返回 `32767` 表示该像素未被探索（`MapWriter.NO_Y_VALUE`）。
任何其他值（±4096 范围）表示该位置有地形数据。

## 三级决策树（xsm$fillGaps 核心逻辑）

```
for each 16×16 sub-tile:

  Level 1: 叶子级 MapRegion 预检（512×512 粒度）
  ┌──────────── region == null? ────yes──→ 降级到 Level 3（可能通过 branch 纹理有数据）
  │                      no
  │                      ↓
  │         !hasHadTerrain()? ──yes──→ 确认未探索（skipCheck=true）
  │                      no
  │                      ↓
  │
  ├── Level 2: MapTileChunk 预检（64×64 粒度）
  │         chunk == null? ────yes──→ 降级到 Level 3（chunk 被卸载但 branch 纹理可能还有数据）
  │                      no
  │         !hasHadTerrain()? ──yes──→ 确认未探索（skipCheck=true）
  │                      no
  │                      ↓
  │
  └── Level 3: texLevel 级 region 完整检查（缓存 + getHeight）
            getLeveledRegion(tx, tz, texLevel)
              → getTexture(ltX, ltZ)
                → getHeight(lpX, lpZ) != 32767?
                  → YES = 已探索
```

### 关键注意事项

| 情况                                | 之前（有 bug）     | 修复后                            |
| ----------------------------------- | ------------------ | --------------------------------- |
| `region == null`                    | 直接 unexplored ❌ | 降级到 Level 3，查 branch 纹理 ✅ |
| `chunk == null`（被 Xaero 卸载）    | 直接 unexplored ❌ | 降级到 Level 3，查 branch 纹理 ✅ |
| `chunk != null && !hasHadTerrain()` | skipCheck=true ✅  | skipCheck=true ✅                 |

**chunk == null 不等于未探索**。Xaero 因内存压力会卸载 `MapTileChunk`，但
`LeveledRegion`（尤其是 LOD 层级的 `BranchLeveledRegion`）的纹理里还缓存着合成时
的高度数据。必须通过 Level 3 的 `getHeight()` 判断。

**region == null 同理**。叶级 `MapRegion` 可能未被加载到内存（远离玩家或刚启动），
但 `BranchLeveledRegion` 的 LOD 纹理可能仍有数据。

## 性能优化

### 两级缓存避免 per-sub-tile HashMap 查询

```
Level 1 缓存:
  (leafRegX, leafRegZ) → MapRegion
  每 32 子tile 失效（512/16 = 32）

Level 3 缓存:
  (brX, brZ, texLevel) → LeveledRegion<?>          ← 每种子地图 tile 常见仅 1 次查询
  (ltX, ltZ)           → RegionTexture<?>           ← 纹理边界每 (2^(9+texLevel)/8/16) 子tile 失效
```

在 texLevel=3 时，`LeveledRegion` 覆盖 4096×4096 方块，一个种子地图 tile 完全落
在 1 个 branch region 内。`getLeveledRegion` 在一个 tile 内只查一次 HashMap，
不同于原始的逐子tile 查询。

### 扫描线合并

连续未探索子tile 水平合并为一个大 `drawQuad`：

- 完全未探索 region：4096 drawQuad → 每行 1 个 = 64 drawQuad
- 缓解顶点提交瓶颈

### 典型性能数据（0.063x zoom, scale=16）

| 优化阶段                                     | fillGaps 耗时 | drawQuad 调用 |
| -------------------------------------------- | ------------- | ------------- |
| 原始（逐子tile getLeveledRegion + drawQuad） | ~184ms        | ~10M          |
| + region/chunk 预检                          | ~60-80ms      | ~10M          |
| + 扫描线合并                                 | ~8ms          | ~160K         |
| + branch 降级修复                            | ~8ms          | ~160K         |

## 常见错误

1. **`MapRegion` 和 `LeveledRegion` 坐标不同**：
   - `MapRegion` (leaf L0): `wx >> 9`（512 方块粒度）
   - `LeveledRegion` (L=texLevel): `wx >> (9 + texLevel)`（2^(9+texLevel) 粒度）
   - 不要混用两者坐标作缓存 key。它们用于不同目的。

2. **`getLeafMapRegion(create=false)` 可能返回 null**：
   - region 未加载时不创建对象，直接返回 null
   - 这是正常情况，不代表该 region 无数据（branch 纹理可能有）
   - 见三级决策树中 region==null 的降级处理

3. **`hasHadTerrain()` 只增不减**（正常操作下）：
   - 一旦设为 true 就不变，除非 `unsetHasHadTerrain()` 被调用
   - 所以 `!hasHadTerrain()` 可安全表示"从未有任何地形数据"

4. **`MapProcessor.getMapChunk()` 内部调 `getLeafMapRegion`**：
   - 若已缓存 leaf region，直接 `region.getChunk()` 避免重复查询
   - 不要用 `getMapChunk()` 做缓存命中查询——它每次重新走一遍查找

5. **`texLevel = xsm$textureLevelForScale(userScale)` 是帧级常量**：
   - 不要每子tile 重新计算
   - 在 `xsm$fillGaps` 入口计算一次即可

## 缩放映射

```java
userScale ≥ 2.0     → scale=1
userScale ≥ 1.0     → scale=4
userScale ≥ 0.5     → scale=16
userScale ≥ 0.25    → scale=64
else & dim==0       → scale=256
else                → scale=64
```

textureLevel 映射：

```java
userScale ≥ 1.0 → texLevel=0
userScale ≥ 0.5 → texLevel=1   // 注：此时 curScale=16，texLevel=1
userScale ≥ 0.25 → texLevel=2
else             → texLevel=3
```

**`curScale` 和 `texLevel` 无关**：`curScale` 控制种子地图 tile 大小（每 tile 覆
盖 64×scale 方块），`texLevel` 控制读取 Xaero texture 的 LOD 层级。两者由
`userScale` 独立推导。
