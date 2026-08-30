# gui — SeedMapPanel side panel & icon textures

## SeedMapPanel

- Toggle via gear button (bottom-left of map screen, `XsmIconButton`)
- Sections: biomes (collapsible with search), structures (collapsible with search)
- Per-biome toggle → updates `WorldConfig.disabledBiomes` → C-side `setBiomeDisabled`
- Per-structure toggle → updates `WorldConfig.disabledStructure` (bit0 = type)
- Structure list is a **tree, always expanded**: variant-bearing types (`getVariants()` non-empty) show each variant code indented below; per-variant toggle → `WorldConfig.disabledStructure` variant bit (render-only, no cache clear). **结构整类禁用时不显示变种行**
- Search also matches variant names (`variantTranslationKey`) — auto-shows only matching variant rows
- Structure icon size slider (0.05~2.0, polynomial mapping: 0.5@t=0.25)
- All text via `Component.translatable()` — see `lang/*.json`

### 结构区 Tab 布局

- Header (总开关 + 折叠) 下为 Tab 按钮行：`类型` / `分组` / `图标`，`structTab` 会话级记忆
- **`structLayout(headerY)` 是结构区几何的单一来源**——render / mouseClicked / mouseScrolled 三处共用，改布局只改它（禁止回退到各处手推 y）
- 类型 tab：搜索框 + 类型/变种列表（`structScrollOff`）
- 分组 tab（`groupsScrollOff`）：内置组 + 用户组（色块 + 组色文字 + 计数），点击色块/组名展开内嵌编辑器：
  - 名称 EditBox（非法名红字，`完成` 提交 `renameGroup`；内置组只读）
  - HSV+透明度 4 滑条（`colorSliderDrag` 拖拽中经 `previewGroupColor` 只改内存，mouseReleased 才 `flush()`，避免逐帧写盘）
  - 透明度语义：滑条 0 → 遮罩 alpha 255（纯色剪影）；100 → alpha 0（无遮罩）。即 alpha = 255×(1−滑条值)
  - `删除` 二次点击确认（`deleteArmed`，编辑器外任意点击解除）；内置组显示 `默认色`（清除覆盖条目）
  - `+ 新建组` → 调色板轮转默认色（alpha=0 无遮罩）+ 立即展开编辑器
- 图标 tab：图标大小滑条 + 战利品预览开关 + 显示模式按钮（自旧版底部迁入）
- 分组计数 `GROUP_COUNTS`（HashMap，组名 → 数量）：命名组 = `countGroup` 全量，未分组 = 视口遍历；20 帧节流仅分组 tab 刷新
- 无 scissor 裁剪：越界行不渲染；编辑器展开高度按 `EDITOR_ROWS` 折算进滚动钳制

## Icon textures

- `biomes.png` sprite sheet + `biomes.ini` (sprite index); `BiomeType` loads from `biomes.ini` at init — must regenerate `biomes.png` if biome list changes (`tools/gen_biomes_icon.py`)
- Structure icons: `structures.png` (20×20 outlined) + `structures_plain.png` (16×16); regenerate with `tools/gen_structures_icon.py`
