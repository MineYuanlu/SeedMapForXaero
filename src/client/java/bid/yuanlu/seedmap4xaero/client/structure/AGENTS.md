# structure — 结构运行时（渲染 / 交互 / 访问检测）

地图上结构图标的渲染、点击交互、访问检测与战利品预览。
持久化数据与读写入口在 `configs/structure/StructureDataConfig`——本包只调它的 `getMark`/`markVisited`/`setGroup`，不感知磁盘格式。

## Classes

- `StructureInfo` — 结构类型统一接口：原版枚举与数据包动态类型的公共面
  （id/key/config/getSpriteIndex/localizedName/isCustom/isHidden）。
  **id 约定：[0, FEATURE_NUM) 原版（= cubiomes 枚举）；[100, 1000) 数据包自定义**
  （= C 侧 `XSM_CUSTOM_STRUCT_MIN/MAX_ID`）。
- `StructureType` — 26 种原版结构枚举（`implements StructureInfo`），配置来自 C（稀疏类型自带 prob）。
- `StructureTypes` — 注册表：三个枚举闭合点（byId 解析、集合遍历 `all()`、位集容量 `capacity()`）全部收敛到此。自定义表由 datapack 包世界切换时整体替换（volatile 快照）；`all()` 过滤 `c:hide_from_map` 隐藏项。
- `CustomStructureType` — 数据包注入的动态类型（不可变；含 iconItem/hidden/weight 与运行时 iconSlot）。**预测无 biome 校验（假阳性）**，见 `doc/datapak.md`。
- `CustomStructureIcons` — 自定义结构运行时图标 sheet：`c:structure_icons` 物品 → 客户端 `textures/{item,block}/<path>.png` → 两张 DynamicTexture（20px 地图格 + 16px 面板格）。**仅渲染线程** `ensureLoaded()`（惰性，注入表引用变化时重建）；slot 0 = 琥珀菱形回退图标。渲染端按 `type.isCustom()` 分流纹理与 UV 宽度。
- `StructureVisitTracker` — 访问检测：玩家接近结构时自动记录访问（历史最小距离）。
- `StructureIcons` — 图标枚举：渲染与右键命中共用同一套过滤（disabled flags + 组隐藏）+ 坐标变换。
  `VisibleIconSink` 末参带出标记表中取出的 `StructureMark` 共享引用（零分配），供渲染端做组色解析，免二次 map get。
  消费 `StructureCache.REGIONS`（**Int2ObjectMap，key = 结构 id**）。
- `StructureRightClick` — 结构图标的右键菜单：分组选项 = 内置组 + 用户组（用户组显示原名）。
  注意 Xaero `GuiRightClickMenu` 不支持滚动（11px/项，仅上下/左右翻转定位），组数极大时可能超屏——暂接受。
- `StructureBitFlag` / `StructureBitFlagView` — 结构可见性位标志（整类 + 变种两级）及只读视图；`ensureCapacity` 天然支持 id > FEATURE_NUM。
- `ChestLootWidget` / `LootPreviewState` — 战利品预览悬浮框及其共享状态（自定义结构不进 LOOT_SUPPORTED）。
- `HighlightedStructures` — 会话级结构高亮（Key 持 `StructureInfo`；数据包重注入时由 datapack 包 clear）。

## 组色渲染（三阶段）

- 图标遮罩（`StructureOverlayMixin`）：组色 ≠ 0 且 alpha ≠ 0 时，在原图标 blit 后追加同 pose/UV 的第二个 `BlitRenderState`，顶点色乘法混合 → 只染色非透明像素；alpha 即遮罩不透明度（透明度滑条 0=纯色剪影, 100=无遮罩）。**无 mark 记录（=未分组）的图标按 DEFAULT 组解析组色**——与 `StructureIcons.forEachVisible` 把 `mark==null` 归为 DEFAULT 的隐藏过滤一致；未分组色覆盖即可罩住所有未分组图标（含未访问无记录者）。
- hover tooltip 分组行：文字色 = `StructureGroups.opaque(组色)`（无色回退灰），用户组显示原名（不走翻译 key）。未分组图标不补 tooltip 分组行。

## 消费方

- `StructureOverlayMixin` / `StructureClickMixin`（client/）— 图标渲染与右键点击的入口。
- `SeedMapPanel`（gui/）— 结构区 Tab UI（类型/分组/图标），分组 tab 含用户组增删改查。
