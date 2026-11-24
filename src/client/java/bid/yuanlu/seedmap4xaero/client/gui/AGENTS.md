# gui — SeedMapPanel side panel & icon textures

## 拆分结构 (PanelSection 协议)

- `SeedMapPanel` = 协调器：生命周期（`panelOpen`/`toggleOpen`/`onInit` 跨 re-init 复用）、
  EditBox 焦点优先路由（先逐区块 `fieldAt` 命中 → `screen.setFocused`，否则 `setFocused(null)`）、
  事件按区块顺序分发、E2E 钩子委托。**不含任何区块内部逻辑**。
- 区块 = `PanelSection` 子类（同包）：`BiomeSection` / `StructSection` / `PerfSection`。
  协议：`render`（模板方法，回写 `bottom()`）/ `mouseClicked` / `mouseScrolled` /
  `fieldAt` / `onPanelOpened` / `onPanelClosed` / `onAnyClick`（拖拽互斥复位）/ `tickDrag` /
  `mouseReleased`。
- **`bottom()` 契约（全区块统一）**：render 每帧回写内容底界；宿主的 click/scroll y 推进 =
  `上一区块 bottom() + SECTION_GAP`，点击/滚动侧**不重算**几何（结构区原
  `structRenderedBottom` 技巧的推广）。折叠态 bottom = headerY + HEADER_H，
  天然覆盖「结构折叠 → 点击落入性能区」短路。区块未命中返回 false，宿主推进下一区块；
  面板内点击/滚动一律消费（尾部兜底 = 性能区恒消费）。
- 会话级状态（展开/滚动/搜索文本/tab）保持 static（窗口缩放 re-init 后语义不变）；
  EditBox 由各区块 `onInit` 重建并自行 `screen.addButton`（仅 panelOpen 时）。
- 共享手绘件在 `PanelUi`（checkbox/区头命中/文字按钮/截断/点击音）；
  常量单一来源在 `PanelSection`（PANEL_WIDTH/ITEM_H/HEADER_H/PADDING/SECTION_GAP 等）。

## SeedMapPanel 区块行为

- Toggle via gear button (bottom-left of map screen, `XsmIconButton`)
- Sections: biomes (collapsible with search), structures (collapsible with search), perf
- Per-biome toggle → updates `WorldConfig.disabledBiomes` → C-side `setBiomeDisabled` + `CellCache.clear()`
- Per-structure toggle → updates `WorldConfig.disabledStructure` (bit0 = type)（render-only, 无缓存同步）
- Structure list is a **tree, always expanded**: variant-bearing types (`getVariants()` non-empty) show each variant code indented below; per-variant toggle → `WorldConfig.disabledStructure` variant bit (render-only, no cache clear). **结构整类禁用时不显示变种行**
- Search also matches variant names (`variantTranslationKey`) — auto-shows only matching variant rows
- Structure icon size slider (0.05~2.0, polynomial mapping: 0.5@t=0.25)——几何/正逆映射收敛在
  `StructSection.IconSliderGeom`（render / click / 拖拽轮询共用，禁止三处复制）
- All text via `Component.translatable()` — see `lang/*.json`

### 批量动作行 (BatchActionRow)

- 生物群系区（搜索行下）与结构区类型 tab（搜索框下、`StructLayout.actionRowY`）各一行：
  `[全选] [全不选] [反选] [只显搜索]`；布局为 render/click 共用单一来源（按当前 i18n 现算）。
- 「只显搜索」在搜索框为空时置灰；`getActiveWorldConfig() == null` 时整行置灰。
  置灰钮不高亮；动作行区域内点击一律消费。
- 语义（状态层 = `WorldConfig` 批量方法）：
  - 生物群系（操作后 `Xsm.setBiomeDisabled` + `CellCache.clear()` 各一次）：
    全选 = 恢复 **null 哨兵**（JSON 省略字段）；全不选/反选/只显搜索 = 只动已知
    `BiomeType.values()` 的 id（orphan/未知 id 不动，避免 JSON 膨胀 `id:N` 占位）；
    只显搜索 = 过滤列表固化（命中启用、其余已知 id 禁用）。
  - 结构类型 tab（render-only）：全选 = 清全部类型位**与变种位**；全不选 = 置全部类型位
    （变种位不动，被禁用类型遮蔽）；反选 = 翻转全部类型位（变种位不动）；只显搜索 =
    过滤树固化——类型名/id/key 命中 → 启用整类并清其变种位；仅变种名命中 → 启用该类型、
    命中变种清位/未命中变种置位；未命中 → 禁用整类。操作后 `updateStructFilter()` 收拢树。
- E2E 钩子：`testSetBiomeSearch`/`testBiomeBatch`/`testSetStructSearch`/`testStructBatch`
  （SeedMapPanel 上，委托 section，走与真实点击同路径的 applyBatch）。

### 结构区 Tab 布局

- Header (总开关 + 折叠) 下为 Tab 按钮行：`类型` / `分组` / `图标`，`structTab` 会话级记忆
- **`structLayout(headerY)` 是结构区几何的单一来源**——render / mouseClicked / mouseScrolled 三处共用，改布局只改它（禁止回退到各处手推 y）；生物群系区同理由 `BiomeSection.layout(headerY)`（`BiomeLayout` record）承担
- 类型 tab：搜索框 + 类型/变种列表（`structScrollOff`）
- 分组 tab（`groupsScrollOff`）：内置组 + 用户组（色块 + 组色文字 + 计数），点击组行展开内嵌编辑器；**再点当前展开的组行 = 收起**（点别组 = 搬移；未分组行同样可展开）
  - 名称 EditBox（空/超长/内置保留名/与其它自定义组**重名** → 红字；内置组只读）
  - HSV+透明度 4 滑条（`colorSliderDrag` 拖拽中经 `previewGroupColor` 只改内存标脏不落盘；落盘时机 = `完成` 点击或离开编辑器（收起/切换组），`editorColorDirty` 会话级脏标志）
  - 透明度语义：滑条 0 → 遮罩 alpha 255（纯色剪影）；100 → alpha 0（无遮罩）。即 alpha = 255×(1−滑条值)
  - `删除` 二次点击确认（`deleteArmed`，编辑器外任意点击解除）；内置组显示 `默认色`（清除覆盖条目）
  - `完成` 提交待定变更（不收起）：改名待提交 → `renameGroup`；颜色 dirty → `flush()`。无待定变更（内置只读/名空/超长/撞保留名/重名/名未改动/颜色未动）时置灰不高亮且点击忽略（`canCommitRename` + `editorColorDirty`）
  - `+ 新建组` → 调色板轮转默认色（alpha=0x80 半透明，预览与遮罩均可见）+ 立即展开编辑器
  - 行色块：组非空恒画；未分组仅在存在颜色覆盖（`color != 0`）时画
- 图标 tab：图标大小滑条 + 战利品预览开关 + 显示模式按钮（自旧版底部迁入）
- 分组计数 `GROUP_COUNTS`（HashMap，组名 → 数量）：命名组 = `countGroup` 全量，未分组 = 视口遍历；20 帧节流仅分组 tab 刷新
- 无 scissor 裁剪：越界行不渲染；编辑器展开高度按 `EDITOR_ROWS` 折算进滚动钳制

## Icon textures

- `biomes.png` sprite sheet + `biomes.ini` (sprite index); `BiomeType` loads from `biomes.ini` at init — must regenerate `biomes.png` if biome list changes (`tools/gen_biomes_icon.py`)
- Structure icons: `structures.png` (20×20 outlined) + `structures_plain.png` (16×16); regenerate with `tools/gen_structures_icon.py`
