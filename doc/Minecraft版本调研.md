# Minecraft 版本调研：版本线 / cubiomes 划分 / Fabric 下限

> 调研日期：**2026-11-22**（正式版最新 = 26.3，26.4 处于快照期）。  
> 回答三个问题：① 1.7.10 起主线版本全表；② cubiomes 如何划分版本、哪些版本
> 生成一致（含本地编译实测）；③ Fabric 生态最低支持线与本 mod 下探空间。  

> 数据源：Mojang 官方 `version_manifest_v2.json`（type=release）+ minecraft.wiki
> 交叉核对（版本线）；cubiomes 子模块源码（xpple fork，pinned `10afeba`，
> 2026-08-07）+ 本地编译实测（划分）；Fabric Meta / Modrinth API 实测（生态）。  

> 相关文档：`xaero-version-compat.md`（Xaero 兼容记录）、`compat-layer.md`；
> 版本参数真值源 = 根 `versions.json`。

## 一、MC 主线版本线（1.7.10 起，共 76 个正式版）

| 版本线 | 正式版（小版本全列） | 数量 |
| --- | --- | --- |
| 1.7.x | 1.7.10 | 1 |
| 1.8.x | 1.8、1.8.1、1.8.2、1.8.3、1.8.4、1.8.5、1.8.6、1.8.7、1.8.8、1.8.9 | 10 |
| 1.9.x | 1.9、1.9.1、1.9.2、1.9.3、1.9.4 | 5 |
| 1.10.x | 1.10、1.10.1、1.10.2 | 3 |
| 1.11.x | 1.11、1.11.1、1.11.2 | 3 |
| 1.12.x | 1.12、1.12.1、1.12.2 | 3 |
| 1.13.x | 1.13、1.13.1、1.13.2 | 3 |
| 1.14.x | 1.14、1.14.1、1.14.2、1.14.3、1.14.4 | 5 |
| 1.15.x | 1.15、1.15.1、1.15.2 | 3 |
| 1.16.x | 1.16、1.16.1、1.16.2、1.16.3、1.16.4、1.16.5 | 6 |
| 1.17.x | 1.17、1.17.1 | 2 |
| 1.18.x | 1.18、1.18.1、1.18.2 | 3 |
| 1.19.x | 1.19、1.19.1、1.19.2、1.19.3、1.19.4 | 5 |
| 1.20.x | 1.20、1.20.1、1.20.2、1.20.3、1.20.4、1.20.5、1.20.6 | 7 |
| 1.21.x | 1.21、1.21.1、1.21.2、1.21.3、1.21.4、1.21.5、1.21.6、1.21.7、1.21.8、1.21.9、1.21.10、1.21.11 | 12 |
| 26.x | 26.1（2026-03-24）、26.1.1、26.1.2、26.2（2026-06-16）、26.3（2026-09-15） | 5 |

### 26.x 年份版本号方案

- 2025-12-02 官方公告：版本号改为 `年.当年第n个 game drop.热修`；**1.22 被跳过**
  （26.1 即"原本的 1.22"），`1.x` 前缀永久退役。
- 26.x 已发布 5 个正式版，**无 26.2.1 / 26.3.1 类热修**；26.4（"Deep Freeze"）
  预计 2026-12 发布，26.5 尚未开始。
- 1.21.11 是 1.21 线终点（2025-12-09），其后直接进入 26.1 —— 与本仓库
  legacy（1.21.11）/ modern（26.1+）双线划分正好吻合。

## 二、cubiomes 的版本划分

### 划分规则

`src/main/c/cubiomes/biomes.h` 的 `MCVersion` 枚举：**每个成员 = 一个世界生成
断点**；`MC_1_X` 指"该线最新补丁"——线内小版本一律不区分（1.8–1.8.9 全在
`MC_1_8` 一桶）。1.16 线与 1.19 线存在**线内劈半**；1.21 线劈得最细（6 桶）。

```
B1.7 → B1.8 → 1.0 → 1.1 → 1.2.5 → 1.3.2 → 1.4.7 → 1.5.2 → 1.6.4
→ 1.7.10 → 1.8.9 → 1.9.4 → 1.10.2 → 1.11.2 → 1.12.2 → 1.13.2
→ 1.14.4 → 1.15.2 → 1.16.1 → 1.16.5 → 1.17.1 → 1.18.2
→ 1.19.2 → 1.19.4 → 1.20.6 → 1.21.1 → 1.21.3 → 1.21.4(WD) → 1.21.5
→ 1.21.6 → 1.21.9 → 1.21.11 → 26.1 → 26.2 (=MC_NEWEST，子模块 10afeba)
```

（上游 xpple fork 自 `8c37ab2`（2026-11-06）起增加 `MC_26_3`，见行动项。）

### 哪些版本"生成一致"（本地实测）

**方法**：临时 C 程序对每个断点版本 `setupGenerator` → `applySeed` →
`genBiomes`，对输出群系 ID 数组做 FNV 哈希对比。参数：scale=4，区域 128×128 @
(-512,-512)，3 种子（123 / 31337 / -123456789）。scale-4 只覆盖 2D 群系布局
（洞穴群系在 scale 1，未采样；1.20–26.1 间无洞穴群系变化，结论稳健）。
**升级 cubiomes 子模块后建议重跑此验证。**

```c
// 核心（约 60 行 harness，未入库）：
Generator g; setupGenerator(&g, mc, 0); applySeed(&g, DIM_OVERWORLD, seed);
Range r = {.scale=4, .x=-512, .z=-512, .sx=128, .sz=128, .y=1, .sy=1};
int cs = getMinCacheSize(&g, 4, r.sx, r.sy, r.sz);   // layer 管线必须按此分配，直接 malloc 会段错误
genBiomes(&g, cache, r); /* FNV-hash(cache[0 .. r.sx*r.sz]) */
```

| 相同组 | 版本范围 | 备注 |
| --- | --- | --- |
| G1 | 1.2.5 – 1.6.4 | |
| **G2** | **1.7 – 1.12.2** | 同一 layer 管线 |
| **G3** | **1.13 – 1.17.1** | 含 1.16.1 与 1.16.2+：布局无差 |
| G4 | 1.18 – 1.18.2 | noise 管线起点 |
| G5 | 1.19 – 1.19.2 | |
| **G6** | **1.19.3 – 26.1** | 1.19.4 / 1.20 / 1.21 全系 / 26.1 布局全部一致 |
| G7 | 26.2 | sulfur_caves 等变化，单独一桶 |

### 断点处实际变了什么

枚举里的细桶（1.20、1.21.1/3/4/5/6/9/11、26.1）**不改变 scale-4 群系布局**，
差异在其他层面：

- **群系 ID 集合**（`biomes.c biomeExists`）：bamboo≥1.14、下界新群系≥1.16.1、
  dripstone/lush≥1.17、deep_dark/mangrove≥1.19.2、cherry_grove≥1.20、
  pale_garden≥1.21.4、sulfur_caves≥26.2。
- **结构出现版本**（`finders.c getStructureConfig`）：igloo≥1.9、mansion≥1.11、
  buried treasure / ocean ruin / shipwreck≥1.13、outpost≥1.14、
  bastion / ruined portal≥1.16.1、ancient city≥1.19.2、trail ruins≥1.20、
  trial chambers≥1.21.1；参数变体按 ≤1.12 / ≤1.15 / ≤1.17 切换
  （desert pyramid、ocean ruin、shipwreck、fortress、village、end gateway 等）。
- **结构生成差异**：`calcFitness` 的距原点距离权重仅用于 ≤MC_1_21_1
  （`finders.c:1198`）→ MC_1_21_1 与 MC_1_21_3 桶的要塞定位不同；
  end gateway 1.11/1.12 不可预测（`finders.c:207` 注释）。
- **树放置表**（`biomenoise.c:1484-1496`）：btree18 / 192 / 19 / 20 / 21wd /
  215 / 262 各一段。
- **群系参数限制 diff 表**（`finders.c getBiomeParaLimits`）：26.2 / 1.21.5 /
  1.21WD / 1.20 各有 diff。
- **其他**：Voronoi ≤1.14 为平面版（`biomenoise.c:764`）；1.16.2 引入
  ocean cave carver（`carver.c:103`，即 MC_1_16_1 与 MC_1_16 分桶的原因）；
  已修复 bug 的行为切换如 MC-199298（bamboo_jungle_hills 判定，≤1.15 或 ≥1.18）、
  MC-278965（cherry_grove 结构有效性，≥1.21.9）。

### 本 mod 的版本映射（`src/main/c/xsm/apis/render.cpp:32-70`）

C 侧 `mcVersionMap` 覆盖 **b1.7 → 26.3**；Java UI 下拉只暴露本 mod 支持的发布线
（每条线一个 worldgen 家族代表，`Xsm.SUPPORTED_VERSIONS`），旧版本字符串经
`WorldConfig.mcVersion` 配置仍可生效。
与上游 `str2mc` 对照，有三处偏差（均为"版本字符串 → 桶"选择）：

| 版本字符串 | 本 mod | 上游分桶 | 影响 |
| --- | --- | --- | --- |
| 26.3 | MC_26_2（近似） | MC_26_3（上游 `8c37ab2` 新增） | 26.3 世界当前按 26.2 近似生成；升级子模块修复 |
| 1.21.2 | MC_1_21_1 | MC_1_21_3 | **要塞距离权重不同**（`finders.c:1198`），1.21.2 世界要塞定位可能偏差 |
| 1.16.2–1.16.4 | MC_1_16_1 | MC_1_16 | 仅洞穴雕刻器差异，对 mod 渲染输出无实际影响 |

其余合并项（1.21.6–1.21.8→MC_1_21_5、1.21.9–1.21.10→MC_1_21_9、
1.19.3→MC_1_19_2 等）安全：上游对应枚举成员当前无代码分支，行为等同。
注：本 mod 把裸字符串 "1.21" 映到 MC_1_21_1（历史正确）；上游 `str2mc` 把它
映到 `MC_1_21` 别名（= MC_1_21_11，最新桶），系上游怪癖，勿照抄。

## 三、Fabric 生态下限与下探空间

### 组件下限（实测）

| 组件 | 最低支持 | 说明 |
| --- | --- | --- |
| 官方 Fabric Loader | **MC 1.14**（2019） | Meta API 实测：1.13.2 及以下 400，1.14 起 200；Loader 与 MC 解耦，一次安装全兼容。注意 Modrinth 上**没有** fabric-loader 项目，仅经官方 Meta 分发 |
| 官方 Fabric API | **MC 1.14** | Modrinth game_versions 联合集覆盖 **1.14 起全部 48 个正式版、无缺口**（含 1.21.11 与 26.1–26.3） |
| Legacy Fabric | 1.3 – 1.13.2 | **非官方社区分叉**（legacyfabric.net），覆盖含 1.7.10，补官方 1.14 以下缺口 |
| Xaero's World Map | **MC 1.7.10**（Forge） | Fabric 老构建 1.14.4 / 1.15.2 起；当前两端最新（1.21.11 与 26.3 均有构建）。1.9–1.11、1.13.x、1.16.1、1.18(.1)、1.19.0、1.21.2 等无构建 |
| cubiomes（本 mod C 侧） | b1.7 | `mcVersionMap` 已含 1.7.10 及全部后续版本 |

### Java 字节码档位

| MC 版本段 | 运行时 Java | 本仓库现状 |
| --- | --- | --- |
| 1.14 – 1.16.5 | 8 | 代码用了 `var`(10+)/`List.of`(9+)，需降档 |
| 1.17 | 16 | 同上 |
| 1.18 – 1.20.4 | 17 | 语法保守（无 record/sealed/21 特性，已 grep 验证），`--release 17` 大概率直接可用 |
| 1.20.5 – 1.21.x | 21 | legacy 节点已是 `--release 21`，**零降档** |
| 26.x | 25（`versions.json` java 字段） | modern 节点现状 |

### 下探结论

**硬约束链**：Fabric API 官方 ≥1.14 → Xaero Fabric 构建 ≥1.14 → Java 档位 →
Xaero compat 工作量。

| 下探目标 | Fabric API | Java | 可行性 |
| --- | --- | --- | --- |
| **1.21.0 – 1.21.10** | ✅ | 21（零降档） | ⭐ 最现实：同 legacy 线编译档，FAPI/Xaero/cubiomes 全支持；成本 = 每版建 stonecutter 节点 + 验证对应 Xaero 老构建（1.40.4 之前）的 mixin 兼容 |
| 1.18 – 1.20.6 | ✅ | 17 / 21 | ✅ 可行：Java 17 降档审查 + 节点 + Xaero compat |
| 1.14 – 1.17.1 | ✅ | 16 / 8 | ⚠️ 理论可行成本陡增：Java 8 需降档 `var`/`List.of`；老渲染 API 大改，compat 重写量最大 |
| ≤ 1.13.2 | ❌ 官方无 | 8 | ❌ 不建议：只能绑定非官方 Legacy Fabric 生态（loader+API+loom 全套分叉），超出"官方生态内 mod"定位 |

**边际成本**主要在 Xaero compat（mixins 依赖 Xaero 内部结构，老版本差异大，
排查手法见 `xaero-version-compat.md`）与 CI 矩阵膨胀（每 MC 版本一个
`versions/<节点>/`，机制现成，按行扩展即可）。

## 行动项

1. **升级 cubiomes 子模块**至 xpple fork `8c37ab2`（+MC_26_3）：26.3 不再按
   26.2 近似；同步 `render.cpp` 的 `mcVersionMap`（26.3→MC_26_3）与
   `Xsm.SUPPORTED_VERSIONS`；升级后重跑第二节的一致性验证。
2. **修正 1.21.2 映射**：`render.cpp` 中 `{"1.21.2", MC_1_21_1}` 应为
   MC_1_21_3（对齐上游；影响要塞定位）。顺带核对 1.16.2–1.16.4 是否对齐
   MC_1_16（无实际渲染影响，可仅加注释）。
3. 若启动下探：第一梯队 1.21.0–1.21.10（Java 21 零降档）。

## 来源

- Mojang 官方版本清单：<https://piston-meta.mojang.com/mc/game/version_manifest_v2.json>
- 年份版本号方案公告：<https://www.minecraft.net/en-us/article/minecraft-new-version-numbering-system>
- minecraft.wiki 版本史：<https://minecraft.wiki/w/Java_Edition_version_history>
- Fabric Meta：<https://meta.fabricmc.net/v2/versions/game>
- Fabric API（Modrinth）：<https://modrinth.com/mod/fabric-api/versions>
- Xaero's World Map（Modrinth）：<https://modrinth.com/mod/xaeros-world-map/versions>
- Legacy Fabric：<https://legacyfabric.net>
- cubiomes 原上游（停滞）：<https://github.com/Cubitect/cubiomes>；
  活跃 fork（本子模块源）：<https://github.com/xpple/cubiomes>
