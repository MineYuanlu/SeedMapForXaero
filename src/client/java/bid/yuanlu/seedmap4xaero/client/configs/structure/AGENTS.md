# configs/structure — structure_data.sm4x (结构持久标记)

配置文档：`gameDir/xaero/seed-map-for-xaero/<mainId>/structure_data.sm4x`，与 `basic/server_config.sm4x` 同目录、同生命周期钩子（`WorldSwitchMixin` / `GuiMapSwitchingMixin` / `SeedMapMixin` / DISCONNECT），互不干扰。
标记与 **(seed, mwId, 结构类型, key)** 绑定——种子按节分别保存，换种子旧标记保留（二阶段做清理命令）。

本包只负责**持久化与查询**：读/写文件 + `StructureData` 的增删改。
访问检测、图标渲染、tooltip、右键菜单等运行时职能在 `client/structure/`（见其 AGENTS.md）。

## Classes

- `StructureDataConfig` — 门面：activate/deactivate/save + 脏标志 CAS；磁盘 IO 全部走 `core/Sm4xFile`（含 `saveLoadForTest`/`targetPathForTest` 单测辅助）。
  便捷查询 `getMark`/`markVisited`/`setGroup` 基于当前激活的 seed+mwId。
  `flush()` — 用户设置分组等显式操作后立即落盘，跳过 `.old` 轮替（右键菜单分组项调用），`.old` 始终保留上次世界切换时的完整备份。
- `StructureData` — 数据体 + `CODEC`（version 1）：`hiddenGroups`（文件级组可见性） + `seeds: { seed → SeedData → DimData → { key → StructureMark } }`。
- `StructureMark` — record `(minDist, group)`；`minDist=-1` = 未访问仅分组；`withVisit` 取历史最小距离。默认组（空串）= 删除组：`setGroup(…, null)` 清除整条记录。
- `StructureGroups` — 内置组字符串常量（默认/完成/隐藏/特殊）；组是字符串。

## 结构 key

全类型统一：key = `(blockX<<32)|blockZ`（`StructureDataConfig.keyOf`）。结构为2D（无 Y），且同类型下方块坐标唯一。
