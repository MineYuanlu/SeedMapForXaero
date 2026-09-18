# datapack 自定义支持

本文档调研 Minecraft 世界生成（World Gen）类模组的实现原理，评估 Seed Map 对 modded 内容的支持路径。
核心结论：**数据包（datapack）是 Minecraft 世界生成的事实标准，优先支持数据包收益最大**；纯代码驱动的模组需逐一定制，放缓。

数据基于以下仓库的实际文件分析：

- Terralith：https://github.com/Stardust-Labs-MC/Terralith （分支 `1.20`）
- Tectonic：https://github.com/Apollounknowdev/tectonic
- BigGlobe：https://github.com/Builderb0y/BigGlobe
- YUNGs-Better-Mineshafts：https://github.com/YUNG-GANG/YUNGs-Better-Mineshafts
- YUNGs-API：https://github.com/YUNG-GANG/YUNGs-API

---

## 1. 其它 World Gen 类模组调研

### 1.1 四种典型实现

| 模组                   | 类型                      | 新生物群系          | 地形修改方式                                                      | 代码量                   |
| ---------------------- | ------------------------- | ------------------- | ----------------------------------------------------------------- | ------------------------ |
| **Terralith**          | 纯数据包                  | 99 新 + 35 覆盖原版 | 重写 15 个噪声路由密度函数 + 78 个自定义密度函数 + 116 个噪声     | 0 行 Java                |
| **Tectonic**           | 数据包 + Mod              | 无（仅改地形形状）  | 替换 `final_density` 等密度函数 + 4 个自定义 DensityFunction 类型 | 13 个 Mixin              |
| **BigGlobe**           | 深度代码驱动              | 15 个自定义         | 完全自定义 `ChunkGenerator` + 自定义脚本语言编译为 JVM 字节码     | ~99 个 Mixin + 脚本引擎  |
| **Yung's Better 系列** | 混合（代码注册 + 数据包） | 无新生物群系        | 自定义 `Structure` 类 + 程序化生成（非 Jigsaw）                   | 5+ 个 Mixin + YUNG's API |

### 1.2 关键区别

1. **Terralith / Tectonic** = 地形改造器（datapack 驱动，修改密度函数 / 噪声设置）
2. **BigGlobe** = 完全替代品（自定义 `ChunkGenerator` + `BiomeSource`，cubiomes 无法理解）
3. **Yung's** = 结构替换器（自定义 `StructureType` 注册进原版 Registry，另覆盖 `structure_set`）

### 1.3 结论

- **数据包支持收益最大**：数据包是 MC 的通用定义标准。Terralith、Tectonic、以及大量纯数据包类模组共用同一套 `worldgen/` 目录规范；支持了通用解析，即可一次性适配这一类内容。
- **代码驱动的需要每个 mod 独立定制**：BigGlobe 用自定义 ChunkGenerator + 私有脚本语言，Yung's 用私有 AutoRegister API 注册自定义 StructureType。这些都需要针对单个 mod 写适配层，成本高、收益低，放缓处理。

---

## 2. 数据包（Datapack）如何工作

### 2.1 本质

数据包 = 放在世界文件夹 `datapacks/` 下的一个文件夹或 zip，内含 `pack.mcmeta` + `data/<命名空间>/...`。它通过**动态注册表（Dynamic Registry）**机制覆盖 / 新增游戏内容，无需重编译。

关键概念：Minecraft 有两类注册表：

- **静态注册表**（方块、物品、实体）：编译期固定，`BuiltInRegistries`
- **动态注册表**（生物群系、结构、结构集、噪声设置、密度函数、配置特征、放置特征、噪声参数、生物群系源参数表等）：**世界加载时才从数据包读取**，存于服务端 `RegistryAccess`

`pack_format` 决定兼容的 MC 版本。Terralith 用 `supported_formats: [15, 41]`（1.20 系列）+ overlay 机制分别适配 1.20.2 / 1.20.3 / 1.20.5。

### 2.2 世界生成管线（1.18+，数据驱动）

```
数据包 JSON → 动态注册表 → WorldGenSettings → ChunkGenerator
                                                          │
    Biomes:      BiomeSource(MultiNoise)  ← noise_router 的气候噪声
    Terrain:     NoiseBasedChunkGenerator ← noise_settings + density_function
    Surface:     surface_rule（方块放置规则）
    Features:    placed_feature → configured_feature（矿石/树/湖）
    Structures:  structure_set → structure（jigsaw 拼接）
```

#### (a) 生物群系放置 —— `BiomeSource`

主世界用 `MultiNoiseBiomeSource`。它在每个位置采样 **6 维气候噪声**，在**参数表**里找参数范围匹配的生物群系：

| 参数            | 对应噪声                 |
| --------------- | ------------------------ |
| temperature     | noise_router/temperature |
| humidity        | noise_router/vegetation  |
| continentalness | noise_router/continents  |
| erosion         | noise_router/erosion     |
| depth           | noise_router/depth       |
| weirdness       | noise_router/ridges      |

参数表有两种来源：

1. **预设**：`"preset": "minecraft:overworld"`（指向内置硬编码参数表，原版）
2. **内联列表**：`"biomes": [{biome, parameters{温度区间, 湿度区间, ... offset}}]`（Terralith 用）

#### (b) 地形 —— `noise_settings/*.json`

`worldgen/noise_settings/overworld.json` 是主世界一切生成的入口，含：

- `noise.min_y / height`：高度范围
- `noise_router.*`：**15 个密度函数引用**（`final_density` 决定地形实体/空气；温度/湿度/大陆/侵蚀/脊线喂给生物群系源）
- `surface_rule`：决定地表方块（草/沙/石头）的嵌套规则树
- `spawn_target`：出生点气候偏好

#### (c) 密度函数 —— `worldgen/density_function/*.json`

纯函数式语言，操作符：`add/mul/min/max/abs/square/cube/range_choice/y_clamped_gradient/noise/spline/clamp/interpolated/cache_2d/flat_cache/beardifier...`。`final_density > 0` = 实体方块。

#### (d) 特征 —— `configured_feature` + `placed_feature`

- `configured_feature`：**生成什么**（树/矿脉/湖的具体逻辑）
- `placed_feature`：**在哪生成**（placement 修饰符链：count → in_square → height_range → biome）
- 生物群系 JSON 的 `features[]` 数组（11 个生成步骤）引用 placed_feature

#### (e) 结构 —— `structure` + `structure_set`

- `structure_set`：**放置逻辑**。`type: minecraft:random_spread` + `salt/spacing/separation`（确定性网格 + 种子哈希偏移）；`structures[]` 列表支持权重随机挑选
- `structure`：**结构本身**。`type: minecraft:jigsaw` + `start_pool`（模板池）+ `size`/`max_distance_from_center`；`biomes` 标签决定能生成在哪
- `template_pool`：jigsaw 模板池（单个/空池元素，带权重和 processor）
- `processor_list`：放置后处理（随机方块替换等）

#### (f) 标签（Tag）—— 数据包间的通用语言

`tags/worldgen/biome/has_structure/xxx.json` 用 `"replace": false` 追加，使结构能兼容其他模组生物群系。

---

## 3. 以 Terralith 为例，数据包到底做了什么

Terralith 本质是**纯数据包**（GitHub `1.20` 分支 0 行 Java），依赖 Fabric API 的内置资源包机制自动加载 `data/`。它做了四层改造：

### 3.1 新增 + 覆盖生物群系（99 新 + 35 覆盖）

- `data/terralith/worldgen/biome/`：**85 个新地表生物群系**（如 `volcanic_peaks.json`：`temperature: 1.0, downfall: 0.3`，自定义草/叶/水/雾颜色，features 里挂 `terralith:volcano/spring_lava`）
- `data/terralith/worldgen/biome/cave/`：14 个洞穴生物群系（共 99 个新）
- `data/minecraft/worldgen/biome/`：**覆盖 35 个原版生物群系**（同命名空间 `minecraft:` 同名文件实现替换）

### 3.2 重写生物群系放置 —— 最关键的一步

原版用 `"preset": "minecraft:overworld"` 参数表，**新生物群系根本不会出现**。Terralith 直接覆盖 `data/minecraft/dimension/overworld.json`，把 `biome_source` 改为**内联 1705 条参数表**，每个条目把气候区间映射到具体生物群系：

```json
{
  "biome": "terralith:brushland",
  "parameters": {
    "temperature": [0.2, 0.55],
    "humidity": [-0.1, 0.1],
    "continentalness": [0.03, 0.62],
    "erosion": [-0.78, 0.05],
    "depth": [-0.005, 0],
    "weirdness": [0.93, 1],
    "offset": 0
  }
}
```

同时重写 `offset.json` / `factor.json` 密度函数（气候坐标系变形），把原版气候空间"挤"出空间给新生物群系。

### 3.3 重写地形 —— 覆盖 7,418 行 `noise_settings/overworld.json`

核心在 `noise_router/final_density.json`，把 Terralith 自定义地形函数**缝合进原版地形**：

```json
"when_in_range": {
  "type": "minecraft:min",
  "argument1": {
    "type": "minecraft:max",
    "argument1": {
      "type": "minecraft:add",
      "argument1": {
        "type": "minecraft:add",
        "argument1": "minecraft:overworld/sloped_cheese",
        "argument2": { "type": "minecraft:beardifier" }
      },
      "argument2": "terralith:overworld/extra_terrain_sum"   // 拱门/沙丘/尖塔
    },
    "argument2": {
      "type": "minecraft:mul",
      "argument1": 5,
      "argument2": {
        "type": "minecraft:min",
        "argument1": "minecraft:overworld/caves/entrances",
        "argument2": "terralith:overworld/subtract_terrain_sum"  // 悬崖刻蚀
      }
    }
  }
}
```

- `data/terralith/worldgen/density_function/overworld/`：14 个子目录（arch/cliff/dune/spike/special...）共 **78 个自定义密度函数**。例 `arch/total.json` = `range_choice(height_spline, when_in_range: arch/base, 否则 -64)`；`arch/base.json` 用噪声 `terralith:math/arch/shape` + 高度样条合成拱门几何
- `data/terralith/worldgen/noise/`：**116 个自定义噪声定义**（如 `noise/math/arch/shape.json` = `{amplitudes: [1,0,0,0.4,0.225], firstOctave: -8}`）

### 3.4 重写地表方块 —— `surface_rule`（3700+ 行）

按 `minecraft:biome` 条件分派 + `noise_threshold` 细化。例如 savanna 分支：查 `terralith:savanna/dripstone` 噪声，超过阈值放 dripstone_block，否则 coarse_dirt。

### 3.5 结构（28 个）

- `structure/`：21 个地表 jigsaw 结构（fortified*village、mage_tower、spire、rubble*\* 系列）+ `structure/underground/`：7 个地下结构
- `structure_set/`：7 个集合（regular/rare_village/underground/mage/rubble...）。例 `regular.json`：`random_spread + salt: 2358902 + spacing: 27 + separation: 15`，5 个结构等权重
- `template_pool/` + `processor_list/` + `terralith/structures/*.nbt`：jigsaw 拼接模板
- `tags/worldgen/biome/has_structure/`：22 个生物群系标签，全部 `"replace": false` 且引用**原版 + Terralith 混合生物群系**

### 3.6 专为地图模组设计的 `c:` 命名空间（重要！）

Terralith **主动提供了地图模组兼容约定**：

- `data/c/worldgen/biome_colors.json`：每个 Terralith 生物群系一个**官方 RGB 颜色 + 显示名**（94 条）——**这正好是 SeedMap 渲染需要的**
- `data/c/worldgen/structure_icons.json`：每个结构映射一个**物品图标**（26 条，如 `"terralith:spire": {"item": "minecraft:blue_ice"}`）
- `data/c/tags/worldgen/biome/`：通用分类标签（`c:is_forest`、`c:is_mountain` 等，供地图配色 fallback）

这是 **C 约定（Common 惯例）**：多个地图模组（Xaero's、JourneyMap 等）约定从数据包读取这些文件来渲染模组生物群系/结构。

---

## 4. 参考实现调研：map.jacobsjo.eu

调研对象：https://map.jacobsjo.eu/（"Minecraft Datapack Map"，jacobsjo 出品），一个**能加载世界生成数据包并离线渲染**的在线地图。它是行业里与 SeedMap 目标最接近的现成实现，本报告用它当"规格参考"来量化实现成本。源码已 clone 到 `tmp/`：

- `tmp/mc-datapack-map`（jacobsjo/mc-datapack-map，MIT，TS/Vue3 前端）
- `tmp/mc-datapack-loader`（jacobsjo/mc-datapack-loader，npm 包：数据包读取/合并）
- `tmp/deepslate`（misode/deepslate，TS 重写版 MC worldgen 计算引擎）

### 4.1 结论：中高参考价值，但不能照抄

**架构上它完全不是 wasm/cubiomes**，而是纯 TS：`mc-datapack-loader` 做文件读取/合并（约 1000 行，只做文件不碰语义）→ `deepslate` 做全部 worldgen JSON 语义解析 → `mc-datapack-map` 做应用层组装 + 版本适配 + 元数据。三块职责边界干净，适合当协议规格。

值得借鉴与**必须警惕**的部分如下。

### 4.2 可借鉴项（对 SeedMap 有直接价值）

1. **`c:` 命名空间元数据约定 —— 建议直接兼容**（`data/c/worldgen/biome_colors.json`、`structure_icons.json`、`c:hide_from_map` tag）
   - Terralith 已自带 94 条配色 + 26 条图标；World Preview mod 也已采用此标准，是生态级约定。
   - 未知生物群系 fallback：用 id hashCode 的 RGB 位生成确定性伪随机色（不崩、可复用）。
   - 若 SeedMap 支持数据包，读这些文件即可零成本获得配色/图标/显示名。
2. **dimension → noise_settings → biome_source 的解析路线**（`useLoadedDimensionStore.reload`）
   - dimension id → `worldgen/world_preset` 反查 → `dimension_type`（取 min_y/height）→ `minecraft:noise` 类型校验 → `noise_settings`（内联或引用）→ `biome_source`。
   - **`preset` 展开**：1.19+ 的 `multi_noise` 用 `preset` 字段指向参数表；网站内置 `public/biome_parameters/minecraft_<版本>/<preset>.json`（构建时从 Ersatz77/mcdata 的官方 report 下载，1.21.1 实测 3.4MB / 7593 条）把 `biomes` 展开内联。**对 C 实现意味着：要么随版本备份这些展开表，要么按 vanilla 算法离线展开。**
3. **random_spread 网格算法**——与 cubiomes 字段**一一对应**
   - `spacing→regionSize`、`spacing-separation→chunkRange`、`salt→salt`、`spread_type` linear/triangular↔`getFeaturePos`/`getLargeStructurePos`。
   - 两阶段结论：**"网格候选 + biome tag 校验"已是完整闭环**（jigsaw 不展开、不做地形形状判定）；`tryGenerate` 的阶段二只有 biome tag 检查有实际过滤价值。这印证路线 A 的核心是"网格 + biome 判定"，不需要 NBT。
4. **`snowcapped_surface` 2D 地表高度约定**（wiki《Surface Height Calculation》）
   - 官方认证"全自动地表高度不可行"（final_density 太慢、depth=0 不可靠），因此规定一个 2D density function 约定名（x,z→y）供数据包主动提供；原版基座内注入 `minecraft:overworld/snowcapped_surface`。
   - **对 SeedMap 的意义**：渲染只需要地表高（坡面光照），不需要完整 3D 地形 → 可用同款"约定 2D 函数"思路大幅降级路线 C（Terralith 未提供该约定名，需 fallback）。
5. **多数据包合并优先级 + `pack.mcmeta` overlay**（mc-datapack-loader）：后加载覆盖先加载；`assign`/`tags`/`override` 三档合并语义；overlay 按 pack_format 匹配子目录。

### 4.3 保真缺口（不能照抄 / 说明参考实现自身也不准）

1. **`DensityFunction.fromJson` 不认识 5 个 Terralith 高频 type** → 静默变 `Constant.ZERO`：

   | type                   | Terralith 出现次数 | deepslate 支持 |
   | ---------------------- | ------------------ | -------------- |
   | `flat_cache`           | 54                 | ❌ → ZERO      |
   | `cache_once`           | 36                 | ❌ → ZERO      |
   | `y_clamped_gradient`   | 20                 | ❌ → ZERO      |
   | `shifted_noise`        | 5                  | ❌ → ZERO      |
   | `weird_scaled_sampler` | 2                  | ❌ → ZERO      |

   **致命点**：Terralith 直接替换 `minecraft:overworld/temperature` 等气候函数，内容正是 `flat_cache → shifted_noise`。缺了它们，燕麦 Terraclimate 采样全错、地图上的生物群系就是乱的。

2. **`worldgen/noise` 字段不匹配**：deepslate 0.27 `NormalNoise.fromJson` 读 `base_amplitude/base_octave/...`，与 MC JSON 实际字段 `firstOctave/amplitudes` 对不上 → 退化成单八度噪声。
3. 其它近似：`offset` 恒 0、`beardifier`/`blend_*` 常量化、`cache_2d` 无缓存、jigsaw 不展开。
4. **正确姿态**：把它的源码当"可读的规格注释本"（RTree、StructurePlacement、DensityFunction 三件套），C 侧仍需自建求值器。

---

## 5. 路径验证：上游 cubiomes、现有管线与成本评估

### 5.1 cubiomes 上游 diff（10afeba → 5815e4f）：无数据包能力

中间 11 个提交逐一核查，**没有任何"从数据包/JSON 加载世界生成定义"的新增能力**：

| 类        | 提交                                              | 说明                                                                                             |
| --------- | ------------------------------------------------- | ------------------------------------------------------------------------------------------------ |
| 版本支持  | `5815e4f`                                         | 26.3：新增硬编码 `btree263.h` 参数表、`s_abandoned_camp` salt 表、`ss_*_263` 盐表、loot 格式适配 |
| 性能优化  | `0a2cdd4/7a373e4/04c233b/453fa2f/34732dd/0a3db3a` | restrict/LTO/循环展开/多态策略/uint8 blocks/lerp3 修复                                           |
| 工程化    | `8d98ca3/3adb731`                                 | C++ 兼容、stronghold 坐标对齐 /locate、ctest 框架                                                |
| loot 增强 | `57b9b11`                                         | 战利品表（非世界生成定义）                                                                       |

- 结构 salt/spacing 表（`getStructureConfig_default`）、biome 参数表（`btree*.h`/`climateToBiome`）、地形（`terrainnoise.c`）**依然全部硬编码**。
- 仓库唯一 JSON 加载器是 `loot/loot_table_parser.c`（战利品预测，10afeba 已有并含 cJSON）—— **与世界生成无关**。
- **结论：上游 cubiomes 不提供数据包能力，必须在 xsm 层自建。**

### 5.2 SeedMap 现有能力快照

- 分工：**`src/main/c/xsm/apis/render.cpp` 是唯一扩展点**；cubiomes submodule 零本地改动。
- 现有运行时 setter 全部是**"参数注入"而非"算法注入"**：`setBiomeColorTable` / `setBiomeColorTableNative` / `setBiomeDisabled` / `setGameVersion` / `setWorld`。
- **预置扩展点（未接线）**：cubiomes fork 的 `setStructureConfigProvider`（finders.h:248-256）——函数指针可替换 `salt/regionSize/chunkRange`，但 `xsm` 层既未导出也未调用。这是与数据包结构集支持**同构**的现成入口。
- 结构查询链：`queryRegionStructuresGrid → getStructureConfig → getStructurePos → isViableStructurePos`；`isViableFeatureBiome`（finders.c:1496）是**硬编码 biome ID 集合**，不认数据包 `#tag`。
- 容量限制：biome ID 空间 **256 双锁**（C `MAX_BIOMES` + Java `BiomeType.MAX_ID`）；`StructureType` 26 枚举 ↔ cubiomes 枚举硬对齐。

### 5.3 三条路线成本评估（只量化，不含实现方案）

| 路线               | 成本量级                                        | 核心内容 / 障碍                                                                                                                                                                                                                                                                                                                       |
| ------------------ | ----------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **A 结构位置预测** | **~0.2–0.5k 行**                                | 复用 `getFeaturePos`(linear)/`getLargeStructurePos`(triangular) 网格算法，字段与 random_spread 一一对应；借 `setStructureConfigProvider` 接线。**障碍**：`getStructurePos` 内部是 `switch(structureType)` 枚举路由（数据包结构无分支，需通用 jigsaw 路由）；`isViableFeatureBiome` 不认 biome tag（跳过=假阳性，做对=需要气候采样器） |
| **B 生物群系**     | **~1.5–2k 行**                                  | 解析 dimension JSON → 1705 条参数表 + RTree/7 维 box 匹配。**前置依赖致命**：6 维气候噪声来自 noise_router 的 temperature/vegetation/continents/erosion/depth/ridges，**它们本身就是 density function**，Terralith 直接替换了它们 → 不做求值器气候值全错、地图全乱。B 与 C 成本在求值器上重叠                                         |
| **C 地形**         | 求值器 **~1.2–1.5k 行核心** + 每新 type 5–60 行 | 完整 44 type 太重；但 SeedMap 只渲染颜色+坡面光照、不需要真实 3D 方块分布 → 可借 `snowcapped_surface` 2D 约定只求地表高（`2D 求值器 ≈ 需求值器的子集`），Terralith 需 fallback                                                                                                                                                        |
| **D `c:` 元数据**  | **最低**                                        | 读 `c:worldgen/biome_colors.json` + `structure_icons.json` 得 94 色 + 28 图标，Terralith 已自带                                                                                                                                                                                                                                       |

> 量级依据 = deepslate 达成同等语义所需的 TS 代码（参数表解析含 RTree ≈ 400 行、DensityFunction 求值器 ≈ 1200–1400 行核心、structure_set 裁剪后 ≈ 350–450 行），作为 C 侧实现的规格基准。

---

## 6. 总结

### 6.1 对 SeedMap 的启示

Terralith 是完全数据驱动的，MC 客户端运行时会话里**动态注册表里确实有全部 99 个新生物群系 + 28 个结构**，Xaero 本体就能显示它们（走运行时 registry）。

但 SeedMap 是**离线种子预测**：不读世界、只靠 seed 在 C 侧 cubiomes 离线重算地形。cubiomes **硬编码原版生成算法**，完全不读数据包 —— 这就是核心矛盾：

| 内容         | SeedMap 现状                            | 需要支持 Terralith 则                                           |
| ------------ | --------------------------------------- | --------------------------------------------------------------- |
| 生物群系放置 | cubiomes 硬编码 `MultiNoiseBiomeSource` | 需解析 dimension JSON 参数表                                    |
| 气候噪声     | cubiomes 硬编码 6 维噪声                | 需解析 noise_router 密度函数                                    |
| 地形         | cubiomes 硬编码                         | 需实现一整套密度函数求值器                                      |
| 结构         | cubiomes 硬编码 salt/spacing            | 结构集可读（random_spread 通用），相对可行                      |
| 配色/图标    | 硬编码 biomes.png                       | 读 `c:worldgen/biome_colors.json` + `structure_icons.json` 即可 |

### 6.2 支持路径可行性排序

1. **结构（最可行）**：读 `structure_set` 的 `salt/spacing/separation` 用 cubiomes 现有网格算法重算位置 —— 与 Yung's Better Mineshaft 同理（Yung's 也是 `random_spread` + 自定义 salt）
2. **生物群系（中等难度）**：解析 `MultiNoiseBiomeSource` 参数表需先求出 6 维气候噪声值；而气候噪声本身就是密度函数（offset/factor 是样条），绕不开密度函数求值
3. **完整地形（最高难度）**：相当于把 MC 的 `DensityFunctions` 求值器移植进 C

#### 6.3 验证结论（基于 §4/§5 三个调研）

1. **结构路线（A）是唯一可低成本独立落地**的路径：借助 cubiomes fork 自带的 `setStructureConfigProvider`（finders.h:248-256）+ 复用它已验证的 `random_spread` 网格算法（`salt/regionSize=spacing/chunkRange=separation`），约 0.2–0.5k 行；唯一精度缺口是 biome tag 校验（硬编码 `isViableFeatureBiome` 不认 `#tag`，跳过会产生假阳性）。
2. **生物群系/地形（B/C）共享同一个不可规避的核心成本：DensityFunction 求值器**——6 维气候噪声本身就是被 Terralith 替换的 density function，不做求值器则地图无意义。jacobsjo/deepslate 只是 TS 侧"规格注释本"（且自身有 5 个 type 缺失、`noise` 字段不匹配的保真 bug），C 侧必须自建。
3. **`c:` 元数据（biome_colors / structure_icons）是零边际成本的高收益入口**：Terralith 已自带 94 色 + 28 图标，且是生态级约定（World Preview 已采用）。
4. **上游 cubiomes（10afeba → 5815e4f）不提供任何数据包能力**，全部硬编码，扩展只能落在 `xsm/render.cpp` 层。

#### 6.4 下一步

- ✅ **cubiomes 可复用作结构位置预测器（已验证，§5.2/§5.3）**：`setStructureConfigProvider`（finders.h:248-256）现成可用，`random_spread` 网格算法与 cubiomes `salt/regionSize/chunkRange` 一一对应 → 路线 A 是唯一可低成本独立落地的路径。
- ⬜ **Xaero 本体是否已消费 `c:` 约定文件**：决定我们是自行解析 `biome_colors.json` / `structure_icons.json`，还是复用 Xaero 的运行结果（查 `refs/lib_src/xaeroworldmap` 中是否读取 `c:worldgen/biome_colors` / `c:worldgen/structure_icons`）。

路线 A 落地前的两个前置技术决策（待做最小 prototype 验证）：

- **`getStructurePos` 的 `switch(structureType)` 枚举路由如何扩展**：数据包结构没有 cubiomes 枚举分支，需新增通用 jigsaw 路由或虚拟 ID（决定 A 的实际改动面）。
- **biome tag 校验取舍**：`isViableFeatureBiome` 硬编码 biome ID 集合、不认 `#terralith:has_structure/*` 标签——跳过会假阳性（struct possibly 标在不该出现处），做对要先有气候采样器（把 A 拖向 B 的成本）。

若 Xaero 未消费 `c:`，建议先做 D（读 `c:` 元数据渲染配色/图标）作为零依赖的独立里程碑。
