# configs/structure — structure_data.sm4x (结构持久标记)

配置文档：`gameDir/xaero/seed-map-for-xaero/<mainId>/structure_data.sm4x`，与 `basic/server_config.sm4x` 同目录、同生命周期钩子（`WorldSwitchMixin` / `GuiMapSwitchingMixin` / `SeedMapMixin` / DISCONNECT），互不干扰。
标记与 **(seed, mwId, 结构类型, key)** 绑定——种子按节分别保存，换种子旧标记保留（`/sm4x history structure remove` 做清理）。

本包只负责**持久化与查询**：读/写文件 + `StructureData` 的增删改。
访问检测、图标渲染、tooltip、右键菜单等运行时职能在 `client/structure/`（见其 AGENTS.md）。

## Classes

- `StructureDataConfig` — 门面：activate/deactivate/save + 脏标志 CAS；磁盘 IO 全部走 `core/Sm4xFile`（含 `saveLoadForTest`/`targetPathForTest` 单测辅助）。
  便捷查询 `getMark`/`markVisited`/`setGroup` 基于当前激活的 seed+mwId。
  `flush()` — 用户设置分组等显式操作后立即落盘，跳过 `.old` 轮替（右键菜单分组项调用），`.old` 始终保留上次世界切换时的完整备份。
  `previewGroupColor()` — 组颜色拖拽实时预览：只改内存标脏不落盘，由调用方在编辑器 `完成` 提交或离开编辑器（收起/切换组）时 `flush()`，避免拖拽逐帧写盘。
  用户组管理 `addGroup`/`setGroupColor`/`clearGroupColor`/`renameGroup`/`removeGroup`/`userGroups()` — 操作成功即 `flush()`。
  命令入口 `removeSeedForCommand(seed)` — 当前使用中的种子返回 -1 拒绝；否则删除 + flush。
- `StructureData` — 数据体 + `CODEC`（version 1，读兼容 version 0）：`hiddenGroups`（文件级组可见性） + `userGroups`（用户组 + 内置组颜色覆盖条目，`UserGroup(name, color)`） + `seeds: { seed → SeedData → DimData → { key → StructureMark } }`。
  种子级操作：`stats(seed)`（组数/结构数）、`removeSeed(seed)`、`seedsSnapshot()`（升序快照，/sm4x 命令用）。
  组名规则 `validGroupName`：非空白、≤32 字符、不与内置组重名；内置组条目 = 颜色覆盖，禁止改名/删除（`clearGroupColor` 恢复默认色）。
  `renameGroup`/`removeGroup` 经 `DimData.reassignGroup` 重写全部标记引用：改名同步替换组名；删组保留访问记录、组清默认（无访问的纯分组记录整条删除），并同步 hiddenGroups。
- `StructureMark` — record `(minDist, group)`；`minDist=-1` = 未访问仅分组；`withVisit` 取历史最小距离。默认组（空串）= 删除组：`setGroup(…, null)` 清除整条记录。
- `StructureGroups` — 内置组字符串常量（默认/完成/隐藏/特殊）+ 组色解析；纯 Java（无 MC 依赖，可 JVM 单测）。
  内置默认色 alpha=0（默认无遮罩，仅文字/色块着色）；`colorOf(doc, group)`：文档覆盖条目优先 → 内置默认色 → 0（无色）。
  color 的 alpha 通道即图标遮罩不透明度（0=无遮罩, FF=纯色剪影）；文字用途经 `opaque()` 强制不透明。
  面板允许为内置组（含**未分组 DEFAULT 空串**）调色：覆盖条目经 `setGroupColor("", c)` 写入；DEFAULT 覆盖色同时罩住无 mark 记录的未分组图标（见 client/structure AGENTS）。

## 结构 key

全类型统一：key = `(blockX<<32)|blockZ`（`StructureDataConfig.keyOf`）。结构为2D（无 Y），且同类型下方块坐标唯一。
