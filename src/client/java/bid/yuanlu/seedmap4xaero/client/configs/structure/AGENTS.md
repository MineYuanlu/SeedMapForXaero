# configs/structure — structure_settings.json + marks/ 分片

结构持久化拆为两份（v2）：

```
<gameDir>/xaero/seed-map-for-xaero/<mainId>/
├── structure_settings.json              # 组可见性 + 用户组（文件级，小）
└── marks/<seedHex>/<mwId>/r.<x>.<z>.json # 结构标记 region 分片（64×64 区块/文件）
```

旧版单文件 `structure_data.sm4x` 在加载链发现时自动向上迁移
（`StructureDataLegacy` 解码 → 设置落盘 + 标记经 `MarksStore.importSnapshot` 分片 → 改名 `.sm4x.legacy`）。
只支持自动向上升级。

标记与 **(seed, mwId, 结构类型, key)** 绑定；key = (blockX<<32)|blockZ（结构为 2D）。
**标记持久化为稳定结构 key**（`minecraft:village`；自定义结构为完整 id 如
`terralith:spire`；转换边界 `StructureKeys`），未注册 key 以 orphan 原样保留
（数据包结构移除后数据不丢，恢复后自动重挂）。

## Classes

- `StructureDataConfig` — 门面：activate/deactivate/save/flush + 便捷查询。
  `getMark`/`markVisited`/`setGroup` 基于当前激活 seed+mwId（marks 惰性加载）。
  用户组管理操作成功即 `flush()`；`previewGroupColor` 只改内存标脏（拖拽防逐帧写盘）。
  组改名/删组经 `MarksStore.rewriteGroupRefs` **跨全部分片**重写标记引用（缓存文档改内存标脏 + 未缓存分片直改磁盘）。
  命令入口 `seedsSnapshot()`/`stats(seed)`/`removeSeedForCommand(seed)`（当前使用中种子返回 -1 拒绝）。
- `StructureData` — 设置文档（hiddenGroups + userGroups + 组色覆盖；`JSON_CODEC`）。
  组改名/删组只改组表+隐藏表，标记引用重写在 MarksStore（门面编排）。
- `MarksStore` — 标记分片存储：
  - region = 64×64 区块（1024 方块），文件 `r.<regionX>.<regionZ>.json`；
    文档按 (seed, mwId) **惰性加载**后驻留（region 文件小，无淘汰）。
  - 内存 `DimData.types` 为 **int 索引 map**（非定长数组）——typeId 可为数据包
    自定义结构 id（[100,1000)，`CustomStructureType`），注册表未注入时按 orphan 处理。
  - **脏粒度 = region**：`markVisited`/`setGroup` 标脏所在 region；`flush(rotate)` 只写脏 region
    （写放大从全量文档降到单 region）；region 变空 → 删除文件。
  - 损坏隔离：单个 region 文件损坏仅跳过该 region，其余照常加载。
  - `doc(seed, mwId)` 惰性加载；`seedsSnapshot`/`stats`/`removeSeed` 走目录扫描/删除
    （removeSeed = 先刷脏再删目录）。
  - orphan：region 文件中未注册结构 key 的标记按坐标保留，写出时并回原 region。
- `StructureDataLegacy` — 旧 `.sm4x` 二进制格式的**冻结**编解码器（v0/v1 → 中性 `Snapshot`）；
  写出仅用于测试 fixture。

## marks region 文档结构（v1）

```json
{ "version": 1,
  "marks": { "minecraft:village": [[blockX, blockZ, minDist, "group"]] } }
```
- `minDist = -1` = 未访问仅分组；`group = ""` = 默认组；记录全空（默认组+无访问）即删除。
- 文档带 `version` 字段；未知字段忽略、缺失落默认（前向兼容）。

## 生命周期与线程

- activate/deactivate 由 `WorldSwitchMixin` / `GuiMapSwitchingMixin` / `SeedMapMixin` / DISCONNECT 钩子调用（与 ServerConfig 并排）。
- 轮替契约同 core：deactivate `save()` rotate=true；会话 `flush()` 与 60s 周期刷盘 rotate=false。
- marks 惰性加载发生在首次 `activeDimData()`（渲染线程）——每维度一次性读入该维度全部 region 文件。
- `markVisited` 来自 END_CLIENT_TICK；周期刷盘同线程。文档级 `synchronized` 保护。

## 单测

`MarksStore`/`StructureData`/`StructureDataLegacy` 均不依赖 MC：`@TempDir` 直接测
（分片 roundtrip、region 边界、轮替契约、损坏隔离、orphan、组重写跨分片、legacy 迁移拆分）。
