# structure — 结构运行时（渲染 / 交互 / 访问检测）

地图上结构图标的渲染、点击交互、访问检测与战利品预览。
持久化数据与读写入口在 `configs/structure/StructureDataConfig`——本包只调它的 `getMark`/`markVisited`/`setGroup`，不感知磁盘格式。

## Classes

- `StructureType` — 26 种结构类型枚举，配置来自 C（稀疏类型自带 prob）。
- `StructureVisitTracker` — 访问检测：玩家接近结构时自动记录访问（历史最小距离）。
- `StructureIcons` — 图标枚举：渲染与右键命中共用同一套过滤（disabled flags + 组隐藏）+ 坐标变换。
  `VisibleIconSink` 末参带出标记表中取出的 `StructureMark` 共享引用（零分配），供渲染端做组色解析，免二次 map get。
- `StructureRightClick` — 结构图标的右键菜单：分组选项 = 内置组 + 用户组（用户组显示原名）。
  注意 Xaero `GuiRightClickMenu` 不支持滚动（11px/项，仅上下/左右翻转定位），组数极大时可能超屏——暂接受。
- `StructureBitFlag` / `StructureBitFlagView` — 结构可见性位标志（整类 + 变种两级）及只读视图。
- `ChestLootWidget` / `LootPreviewState` — 战利品预览悬浮框及其共享状态。
- `HighlightedStructures` — 会话级结构高亮。

## 组色渲染（三阶段）

- 图标遮罩（`StructureOverlayMixin`）：组色 ≠ 0 且 alpha ≠ 0 时，在原图标 blit 后追加同 pose/UV 的第二个 `BlitRenderState`，顶点色乘法混合 → 只染色非透明像素；alpha 即遮罩不透明度（透明度滑条 0=纯色剪影, 100=无遮罩）。
- hover tooltip 分组行：文字色 = `StructureGroups.opaque(组色)`（无色回退灰），用户组显示原名（不走翻译 key）。

## 消费方

- `StructureOverlayMixin` / `StructureClickMixin`（client/）— 图标渲染与右键点击的入口。
- `SeedMapPanel`（gui/）— 结构区 Tab UI（类型/分组/图标），分组 tab 含用户组增删改查。
