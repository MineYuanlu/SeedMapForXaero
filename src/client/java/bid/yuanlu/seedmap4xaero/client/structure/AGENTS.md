# structure — 结构运行时（渲染 / 交互 / 访问检测）

地图上结构图标的渲染、点击交互、访问检测与战利品预览。
持久化数据与读写入口在 `configs/structure/StructureDataConfig`——本包只调它的 `getMark`/`markVisited`/`setGroup`，不感知磁盘格式。

## Classes

- `StructureType` — 26 种结构类型枚举，配置来自 C（稀疏类型自带 prob）。
- `StructureVisitTracker` — 访问检测：玩家接近结构时自动记录访问（历史最小距离）。
- `StructureIcons` — 图标绘制：渲染与右键命中共用同一套过滤 + 坐标变换。
- `StructureRightClick` — 结构图标的右键菜单。
- `StructureBitFlag` / `StructureBitFlagView` — 结构可见性位标志（整类 + 变种两级）及只读视图。
- `ChestLootWidget` / `LootPreviewState` — 战利品预览悬浮框及其共享状态。
- `HighlightedStructures` — 会话级结构高亮。

## 消费方

- `StructureOverlayMixin` / `StructureClickMixin`（client/）— 图标渲染与右键点击的入口。
- `SeedMapPanel`（gui/）— 结构分组管理 UI（隐藏组勾选）。
