package bid.yuanlu.seedmap4xaero.client.gui;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import org.jetbrains.annotations.Nullable;

import bid.yuanlu.seedmap4xaero.client.cache.StructureCache;
import bid.yuanlu.seedmap4xaero.client.compat.CompatInput;
import bid.yuanlu.seedmap4xaero.client.configs.basic.LootDisplayMode;
import bid.yuanlu.seedmap4xaero.client.configs.basic.ServerConfig;
import bid.yuanlu.seedmap4xaero.client.configs.basic.WorldConfig;
import bid.yuanlu.seedmap4xaero.client.configs.structure.StructureData;
import bid.yuanlu.seedmap4xaero.client.configs.structure.StructureDataConfig;
import bid.yuanlu.seedmap4xaero.client.configs.structure.StructureGroups;
import bid.yuanlu.seedmap4xaero.client.structure.LootPreviewState;
import bid.yuanlu.seedmap4xaero.client.structure.StructureBitFlag;
import bid.yuanlu.seedmap4xaero.client.structure.StructureBitFlagView;
import bid.yuanlu.seedmap4xaero.client.structure.StructureType;
import bid.yuanlu.seedmap4xaero.utils.BitSetView;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import xaero.map.gui.GuiMap;

/**
 * 结构区: header (总开关+折叠) + Tab 栏 (类型/分组/图标) + 三 tab 内容 + 组编辑器。
 * <p>
 * 几何单一来源 = {@link StructLayout}（render / mouseClicked / mouseScrolled 共用,
 * 禁止回退到各处手推 y）; 内容底界 = render 每帧回写的 {@link #bottom()}。
 * 类型 tab 首行挂批量动作行 (全选/全不选/反选/只显搜索), 语义见 gui/AGENTS.md。
 */
class StructSection extends PanelSection {

    private static final int TAB_H = 14;
    private static final int TAB_GAP = 2;
    /** 组编辑器高度: 名称行 + 4 滑条 + 按钮行 + 尾距 (须与 renderGroupEditor 实际推进严格一致)。 */
    private static final int EDITOR_H = 16 + 4 * 12 + ITEM_H + 2;
    /** 编辑器高度折算的列表行数 (滚动钳制用)。 */
    private static final int EDITOR_ROWS = (EDITOR_H + ITEM_H - 1) / ITEM_H;
    /** 颜色滑条轨道起点 (标签固定宽)。 */
    private static final int COLOR_TRACK_X0 = PADDING + 34;

    /** 无配置时的回退 flags: 全 0 = 全部可见。 */
    private static final StructureBitFlagView DEFAULT_FLAGS = new StructureBitFlag();

    /** 新建组的轮转默认色 (alpha=0x80: 半透明遮罩, 编辑器预览/地图均可见)。 */
    private static final int[] NEW_GROUP_PALETTE = {
            0x80FF5555, 0x80FFAA00, 0x80FFFF55, 0x8055FF55,
            0x8000FFAA, 0x8000AAFF, 0x80AA55FF, 0x80FF55FF };
    private static int newGroupColorIdx;

    /** 分组计数缓存: 命名组 = 全量标记数, 未分组 = 当前视口可见的未标记图标数; 每 20 帧刷新。 */
    private static final HashMap<String, Integer> GROUP_COUNTS = new HashMap<>();
    private static int groupCountCooldown;

    // 会话级 UI 状态: 窗口缩放 re-init 后保持
    /** 结构区激活 Tab: 0=类型 1=分组 2=图标。 */
    private static int structTab;
    private static boolean structureExpanded;
    private static int structScrollOff;
    private static int groupsScrollOff;
    private static String structSearchText = "";
    /** 正在展开编辑器的组名; null = 关闭。 */
    private static String editorGroup;
    private static boolean deleteArmed;
    /** 编辑器颜色状态: h/s/v + 透明度 (0=纯色剪影, 1=无遮罩), 均 [0,1]。 */
    private static final float[] editorHSV = new float[4];
    /** 拖拽中的颜色滑条 id 1..4; -1 = 无 (松开仅结束拖拽; 落盘在 完成 提交或离开编辑器时)。 */
    private static int colorSliderDrag = -1;
    /** 本次编辑会话颜色已改动且未落盘 (点亮 完成; 提交/离开编辑器时清除)。 */
    private static boolean editorColorDirty;

    private EditBox structSearchField;
    /** 组编辑器的名称输入框 (随编辑器展开定位)。 */
    private EditBox groupEditField;
    private List<StructRow> filteredStructures;

    // slider (图标大小, icons tab)
    private float sliderValue = 1.0f;
    private boolean sliderDragging;

    /**
     * 结构区一行: 类型行 (variant==null) 或该类型下的变种行 (缩进)。
     * 树形结构, 默认全部展开。
     */
    private record StructRow(StructureType type, @Nullable Integer variant) {
        boolean isVariant() {
            return variant != null;
        }
    }

    /** 结构区内容区布局 (单一来源: render / mouseClicked / mouseScrolled 共用)。 */
    private record StructLayout(int tabY, int tabW, int contentY, int actionRowY,
            int typeListY, int typeVisible,
            int groupListY, int groupVisibleRows) {
    }

    private StructLayout structLayout(int headerY) {
        int tabY = headerY + HEADER_H + PADDING;
        int tabW = (PANEL_WIDTH - PADDING * 2 - TAB_GAP * 2) / 3;
        int contentY = tabY + TAB_H + PADDING;
        int actionRowY = contentY + 16 + PADDING; // 搜索框高
        int typeListY = actionRowY + ITEM_H + PADDING;
        int typeVisible = Math.min(MAX_VISIBLE_ITEMS,
                Math.max(MIN_VISIBLE_ITEMS, (scrH - typeListY - 30) / ITEM_H));
        int groupVisibleRows = Math.max(MIN_VISIBLE_ITEMS, (scrH - contentY - 10) / ITEM_H);
        return new StructLayout(tabY, tabW, contentY, actionRowY, typeListY, typeVisible,
                contentY, groupVisibleRows);
    }

    @Override
    void onInit(GuiMap screen, int scrW, int scrH) {
        super.onInit(screen, scrW, scrH);
        if (!SeedMapPanel.panelOpen)
            return;
        int searchY = PADDING + HEADER_H + PADDING + 20 + PADDING;
        structSearchField = new EditBox(font, PADDING + 12, searchY, PANEL_WIDTH - PADDING - 14, 14,
                Component.translatable("xsm.gui.panel.search_structures"));
        structSearchField.setValue(structSearchText);
        structSearchField.setResponder(s -> {
            structSearchText = s;
            updateStructFilter();
            structScrollOff = 0;
        });
        structSearchField.setCanLoseFocus(true);
        structSearchField.setVisible(structureExpanded);
        screen.addButton(structSearchField);

        groupEditField = new EditBox(font, PADDING, 0, PANEL_WIDTH - PADDING * 2 - 16, 14,
                Component.translatable("xsm.gui.panel.group_name_hint"));
        groupEditField.setMaxLength(StructureData.MAX_GROUP_NAME);
        groupEditField.setCanLoseFocus(true);
        groupEditField.setVisible(false);
        groupEditField.setResponder(s -> {
            groupEditField.setTextColor(renameTargetNameOk(s, editorGroup)
                    ? 0xFFFFFFFF : 0xFFFF5555);
        });
        if (editorGroup != null)
            groupEditField.setValue(displayNameOf(editorGroup));
        screen.addButton(groupEditField);
    }

    @Override
    void onPanelOpened() {
        sliderDragging = false;
        sliderValue = ServerConfig.getStructureIconSize();
        updateStructFilter();
    }

    @Override
    void onPanelClosed() {
        if (structSearchField != null)
            structSearchField.setFocused(false);
    }

    @Override
    @Nullable EditBox fieldAt(int mx, int my) {
        // 仅在各自可见态命中 (修复: 原实现按残留旧坐标命中不可见输入框)
        if (structureExpanded && structTab == 0 && structSearchField != null
                && structSearchField.isMouseOver(mx, my))
            return structSearchField;
        if (structureExpanded && structTab == 1 && editorGroup != null && groupEditField != null
                && groupEditField.isMouseOver(mx, my))
            return groupEditField;
        return null;
    }

    @Override
    void onAnyClick() {
        sliderDragging = false;
    }

    @Override
    void tickDrag(int mouseX) {
        // slider drag update
        if (sliderDragging)
            updateSlider(mouseX);
        // color slider drag: live preview (落盘在 完成 提交或离开编辑器, 见 editorColorDirty)
        if (colorSliderDrag > 0)
            applyColorSliderDrag(mouseX);
    }

    @Override
    boolean mouseReleased(int button) {
        if (button == CompatInput.MOUSE_LEFT && sliderDragging) {
            sliderDragging = false;
            return true;
        }
        if (button == CompatInput.MOUSE_LEFT && colorSliderDrag > 0) {
            colorSliderDrag = -1; // 颜色不在此处落盘: 完成 提交或离开编辑器时 flush
            return true;
        }
        return false;
    }

    // ─── RENDER ──────────────────────────────────────────────

    @Override
    int renderContent(GuiGraphicsExtractor g, int mx, int my, int headerY) {
        boolean enabled = ServerConfig.isStructureEnabled();
        boolean hoverHdr = my >= headerY && my < headerY + HEADER_H
                && mx >= PADDING && mx <= PANEL_WIDTH - PADDING;
        if (hoverHdr)
            g.fill(PADDING, headerY, PANEL_WIDTH - PADDING, headerY + HEADER_H, 0x22_FFFFFF);

        boolean hoverCb = PanelUi.hitCheckbox(mx, my, headerY);
        PanelUi.renderCheckbox(g, PADDING, headerY + (HEADER_H - 9) / 2, enabled, hoverCb);

        String label = I18n.get("xsm.gui.panel.structures_header", I18n.get(enabled ? "xsm.value.on" : "xsm.value.off"));
        g.text(font, label, PADDING + 12, headerY + (HEADER_H - font.lineHeight) / 2,
                enabled ? 0xFFFFFFFF : 0xFF888888);

        String arrow = structureExpanded ? "▼" : "▶";
        int arrX = PANEL_WIDTH - PADDING - font.width(arrow);
        g.text(font, arrow, arrX, headerY + (HEADER_H - font.lineHeight) / 2, 0xFFFFFFFF);

        if (structSearchField != null)
            structSearchField.setVisible(structureExpanded && structTab == 0);
        if (groupEditField != null)
            groupEditField.setVisible(structureExpanded && structTab == 1 && editorGroup != null);

        if (!structureExpanded)
            return headerY + HEADER_H;

        var L = structLayout(headerY);
        renderTabBar(g, L, mx, my);

        // 返回实际渲染底界 (base 记为 bottom(), 供点击路由使用, 避免点击侧重算
        // 与分组 tab 实际行数/滚动钳制不一致)
        return switch (structTab) {
            case 0 -> renderTypesTab(g, mx, my, L);
            case 1 -> renderGroupsTab(g, mx, my, L);
            default -> renderIconsTab(g, mx, my, L);
        };
    }

    private void renderTabBar(GuiGraphicsExtractor g, StructLayout L, int mx, int my) {
        String[] keys = { "xsm.gui.panel.tab_types", "xsm.gui.panel.tab_groups", "xsm.gui.panel.tab_icons" };
        for (int i = 0; i < 3; i++) {
            int x = PADDING + i * (L.tabW() + TAB_GAP);
            boolean active = structTab == i;
            boolean hover = mx >= x && mx <= x + L.tabW() && my >= L.tabY() && my <= L.tabY() + TAB_H;
            g.fill(x, L.tabY(), x + L.tabW(), L.tabY() + TAB_H,
                    active ? 0xFF4A4A4A : hover ? 0xFF333333 : 0xFF262626);
            String tabLabel = I18n.get(keys[i]);
            g.text(font, tabLabel, x + (L.tabW() - font.width(tabLabel)) / 2,
                    L.tabY() + (TAB_H - font.lineHeight) / 2, active ? 0xFFFFFFFF : 0xFFAAAAAA);
        }
    }

    /** Tab 命中; -1 = 未命中。 */
    private int hitTab(int mx, int my, StructLayout L) {
        if (my < L.tabY() || my > L.tabY() + TAB_H || mx < PADDING)
            return -1;
        int i = (mx - PADDING) / (L.tabW() + TAB_GAP);
        return i >= 0 && i < 3 ? i : -1;
    }

    /** 内置组 + 用户组 (去重) 的展示顺序列表。 */
    private static List<String> allGroupNames() {
        var out = new ArrayList<String>(StructureGroups.BUILTIN);
        for (var ug : StructureDataConfig.userGroups())
            if (!out.contains(ug.name()))
                out.add(ug.name());
        return out;
    }

    /** 组显示名: 内置组走翻译, 用户组显示原名。 */
    private static String displayNameOf(String group) {
        return StructureGroups.isBuiltin(group)
                ? I18n.get(StructureGroups.translationKey(group))
                : group;
    }

    // ─── Tab: 类型 ───────────────────────────────────────────

    private int renderTypesTab(GuiGraphicsExtractor g, int mx, int my, StructLayout L) {
        // search field
        if (structSearchField != null) {
            structSearchField.setY(L.contentY());
            structSearchField.extractRenderState(g, mx, my, 0);
        }

        // 批量动作行 (全选/全不选/反选/只显搜索)
        var actionLayout = BatchActionRow.layout(font, L.actionRowY());
        BatchActionRow.render(g, font, actionLayout, batchEnabled(), mx, my);

        int y = L.typeListY();

        // structure list
        if (filteredStructures == null)
            updateStructFilter();
        int visible = L.typeVisible();
        int size = filteredStructures.size();
        if (structScrollOff > size - visible)
            structScrollOff = Math.max(0, size - visible);

        int end = Math.min(structScrollOff + visible, size);
        var wc = ServerConfig.getActiveWorldConfig();
        StructureBitFlagView flags = wc != null ? wc.getDisabledStructures() : DEFAULT_FLAGS;

        for (int i = structScrollOff; i < end; i++) {
            StructRow row = filteredStructures.get(i);
            int itemY = y + (i - structScrollOff) * ITEM_H;
            boolean hover = mx >= PADDING && mx <= PANEL_WIDTH - PADDING
                    && my >= itemY && my <= itemY + ITEM_H;
            if (row.isVariant()) {
                // 变种行: 缩进显示, 用变种专属图标
                final int v = row.variant();
                boolean on = !flags.isStructureSet(row.type().id) && !flags.isVariantSet(row.type().id, v);
                int cbX = PADDING + 14;
                PanelUi.renderCheckbox(g, cbX, itemY + (ITEM_H - 9) / 2, on, hover);
                float v0 = (row.type().getSpriteIndex(v) * 16f)
                        / StructureType.PLAIN_SPRITESHEET_WIDTH;
                float v1 = v0 + 16f / StructureType.PLAIN_SPRITESHEET_WIDTH;
                g.blit(StructureType.STRUCTURES_PLAIN_TEXTURE,
                        cbX + 12, itemY + 1,
                        cbX + 22, itemY + 11,
                        v0, v1, 0.0F, 1.0F);
                g.text(font, I18n.get(row.type().variantTranslationKey(v)),
                        cbX + 24, itemY + (ITEM_H - font.lineHeight) / 2,
                        on ? 0xFFFFFFFF : 0xFF888888);
            } else {
                StructureType s = row.type();
                boolean si = !flags.isStructureSet(s.id);
                PanelUi.renderCheckbox(g, PADDING, itemY + (ITEM_H - 9) / 2, si, hover);

                float u0 = (s.getSpriteIndex(0) * 16f) / StructureType.PLAIN_SPRITESHEET_WIDTH;
                float u1 = u0 + 16f / StructureType.PLAIN_SPRITESHEET_WIDTH;
                g.blit(StructureType.STRUCTURES_PLAIN_TEXTURE,
                        PADDING + 12, itemY + 1,
                        PADDING + 22, itemY + 11,
                        u0, u1, 0.0F, 1.0F);

                g.text(font, I18n.get(s.translationKey()), PADDING + 24,
                        itemY + (ITEM_H - font.lineHeight) / 2,
                        si ? 0xFFFFFFFF : 0xFF888888);
            }
        }
        return y + visible * ITEM_H;
    }

    /** 动作行各钮可用性: 无世界配置时整行置灰; 「只显搜索」还需搜索词非空。 */
    private boolean[] batchEnabled() {
        boolean hasWc = ServerConfig.getActiveWorldConfig() != null;
        boolean only = hasWc && !structSearchText.isEmpty();
        return new boolean[] { hasWc, hasWc, hasWc, only };
    }

    /** 批量操作 (语义见 gui/AGENTS.md); render-only, 无 native/瓦片缓存同步。 */
    private void applyBatch(int action) {
        var wc = ServerConfig.getActiveWorldConfig();
        if (wc == null)
            return;
        switch (action) {
            case BatchActionRow.ALL -> wc.enableAllStructures();
            case BatchActionRow.NONE -> wc.disableAllStructures();
            case BatchActionRow.INVERT -> wc.invertStructures();
            case BatchActionRow.SHOW_ONLY -> {
                if (structSearchText.isEmpty())
                    return;
                showOnlyMatched(wc);
            }
            default -> {
                return;
            }
        }
        updateStructFilter(); // 类型禁用 → 收拢变种行
    }

    /**
     * 「只显搜索」: 把过滤树固化为地图显示状态 —
     * 类型名/id/key 命中 → 启用整类并清其变种位; 仅变种名命中 → 启用该类型,
     * 命中变种清位 / 未命中变种置位; 未命中 → 禁用整类。
     */
    private void showOnlyMatched(WorldConfig wc) {
        String search = structSearchText.toLowerCase();
        for (StructureType s : StructureType.values()) {
            if (typeMatchesSearch(s, search)) {
                wc.setStructureEnabled(s.id, true);
                for (int v : s.getVariants())
                    wc.setVariantEnabled(s.id, v, true);
                continue;
            }
            IntList matched = variantsMatchingSearch(s, search);
            if (matched.isEmpty()) {
                wc.setStructureEnabled(s.id, false);
                continue;
            }
            wc.setStructureEnabled(s.id, true);
            for (int v : s.getVariants())
                wc.setVariantEnabled(s.id, v, matched.contains(v));
        }
    }

    /** 类型行命中判定 (与 updateStructFilter 同源: id / key / 展示名)。 */
    private static boolean typeMatchesSearch(StructureType s, String search) {
        if (search.isEmpty())
            return true;
        return Integer.toString(s.id).contains(search)
                || s.key.contains(search)
                || I18n.get(s.translationKey()).toLowerCase().contains(search);
    }

    /** 变种名命中列表 (与 updateStructFilter 同源)。 */
    private static IntList variantsMatchingSearch(StructureType s, String search) {
        var out = new IntArrayList();
        for (int v : s.getVariants())
            if (I18n.get(s.variantTranslationKey(v)).toLowerCase().contains(search))
                out.add(v);
        return out;
    }

    // ─── Tab: 分组 ───────────────────────────────────────────

    /** 列表总行数 (编辑器额外高度折算行)。 */
    private static int groupRowsUsed(List<String> names) {
        int rows = names.size() + 1; // + 新建组
        if (editorGroup != null)
            rows += EDITOR_ROWS;
        return rows;
    }

    private int renderGroupsTab(GuiGraphicsExtractor g, int mx, int my, StructLayout L) {
        var sdata = StructureDataConfig.getActiveData();
        if (--groupCountCooldown <= 0) {
            updateGroupCounts();
            groupCountCooldown = 20;
        }

        var names = allGroupNames();
        int listBottom = L.groupListY() + L.groupVisibleRows() * ITEM_H;
        int rowsUsed = groupRowsUsed(names);
        if (groupsScrollOff > rowsUsed - L.groupVisibleRows())
            groupsScrollOff = Math.max(0, rowsUsed - L.groupVisibleRows());

        int y = L.groupListY();
        for (int i = groupsScrollOff; i < names.size() && y < listBottom; i++) {
            String group = names.get(i);
            boolean shown = sdata == null || !sdata.isGroupHidden(group);
            boolean hoverRow = my >= y && my < y + ITEM_H && mx >= PADDING && mx <= PANEL_WIDTH - PADDING;
            PanelUi.renderCheckbox(g, PADDING, y + (ITEM_H - 9) / 2, shown, hoverRow);
            int color = StructureGroups.colorOf(sdata, group);
            int textX = PADDING + 12;
            if (!group.isEmpty() || color != 0) {
                // 色块 (显示用强制不透明; 未分组有覆盖色时同样展示)
                g.fill(textX, y + 1, textX + 9, y + 10, 0xFF888888);
                g.fill(textX + 1, y + 2, textX + 8, y + 9, StructureGroups.opaque(color));
                textX += 12;
            }
            int count = GROUP_COUNTS.getOrDefault(group, 0);
            String full = PanelUi.truncate(font, displayNameOf(group) + " (" + count + ")",
                    PANEL_WIDTH - PADDING - textX);
            g.text(font, full, textX, y + (ITEM_H - font.lineHeight) / 2,
                    color != 0 ? StructureGroups.opaque(color)
                            : shown ? 0xFFFFFFFF : 0xFF888888);
            y += ITEM_H;
            if (group.equals(editorGroup))
                y = renderGroupEditor(g, mx, my, y, group);
        }

        // 新建组行
        if (y < listBottom) {
            boolean hoverNew = my >= y && my < y + ITEM_H && mx >= PADDING && mx <= PANEL_WIDTH - PADDING;
            if (hoverNew)
                g.fill(PADDING, y, PANEL_WIDTH - PADDING, y + ITEM_H, 0x22_FFFFFF);
            g.text(font, I18n.get("xsm.gui.panel.group_new"), PADDING + 12,
                    y + (ITEM_H - font.lineHeight) / 2, 0xFFAAAAAA);
            y += ITEM_H;
        }
        return y;
    }

    /** 组编辑器 (名称 + HSV/透明度滑条 + 按钮), 返回新的 y。 */
    private int renderGroupEditor(GuiGraphicsExtractor g, int mx, int my, int y, String group) {
        int x0 = PADDING + 12;
        int w = PANEL_WIDTH - PADDING - x0;

        // 名称行: EditBox + 颜色预览块
        int previewX = PANEL_WIDTH - PADDING - 12;
        if (groupEditField != null) {
            groupEditField.setX(x0);
            groupEditField.setY(y);
            groupEditField.setWidth(w - 16);
            groupEditField.extractRenderState(g, mx, my, 0);
        }
        g.fill(previewX, y, previewX + 12, y + 12, 0xFF888888);
        g.fill(previewX + 1, y + 1, previewX + 11, y + 11, currentEditorArgb());
        y += 16;

        // HSV + 透明度 4 滑条
        String[] keys = { "xsm.gui.panel.color.hue", "xsm.gui.panel.color.sat",
                "xsm.gui.panel.color.val", "xsm.gui.panel.color.alpha" };
        for (int id = 1; id <= 4; id++)
            y = renderColorSlider(g, mx, my, y, id, keys[id - 1], editorHSV[id - 1]);

        // 按钮行: 删除 (用户组) / 恢复默认色 (内置有覆盖时) … 完成 (右)
        var sdata = StructureDataConfig.getActiveData();
        if (!StructureGroups.isBuiltin(group)) {
            String delLabel = I18n.get(deleteArmed
                    ? "xsm.gui.panel.group_delete_confirm" : "xsm.gui.panel.group_delete");
            int delW = font.width(delLabel) + 8;
            boolean hov = my >= y && my <= y + ITEM_H && mx >= x0 && mx <= x0 + delW;
            g.fill(x0, y, x0 + delW, y + ITEM_H,
                    deleteArmed ? 0xFF7A2222 : hov ? 0xFF666666 : 0xFF333333);
            g.text(font, delLabel, x0 + 4, y + (ITEM_H - font.lineHeight) / 2, 0xFFFFFFFF);
        } else if (sdata != null && sdata.colorOf(group) != 0) {
            String resetLabel = I18n.get("xsm.gui.panel.group_reset_color");
            int rw = font.width(resetLabel) + 8;
            boolean hov = my >= y && my <= y + ITEM_H && mx >= x0 && mx <= x0 + rw;
            g.fill(x0, y, x0 + rw, y + ITEM_H, hov ? 0xFF666666 : 0xFF333333);
            g.text(font, resetLabel, x0 + 4, y + (ITEM_H - font.lineHeight) / 2, 0xFFFFFFFF);
        }
        boolean doneEnabled = canCommitRename(group) || editorColorDirty;
        String doneLabel = I18n.get("xsm.gui.panel.group_done");
        int doneW = font.width(doneLabel) + 8;
        int doneX = PANEL_WIDTH - PADDING - doneW;
        boolean hovDone = doneEnabled && my >= y && my <= y + ITEM_H
                && mx >= doneX && mx <= doneX + doneW;
        g.fill(doneX, y, doneX + doneW, y + ITEM_H,
                !doneEnabled ? 0xFF262626 : hovDone ? 0xFF666666 : 0xFF333333);
        g.text(font, doneLabel, doneX + 4, y + (ITEM_H - font.lineHeight) / 2,
                doneEnabled ? 0xFFFFFFFF : 0xFF888888);
        return y + ITEM_H + 2;
    }

    /** 线性小滑条 (标签 + 轨道 + thumb), 返回新的 y。 */
    private int renderColorSlider(GuiGraphicsExtractor g, int mx, int my, int y,
            int id, String labelKey, float t) {
        String colorLabel = I18n.get(labelKey);
        g.text(font, colorLabel, PADDING, y + (12 - font.lineHeight) / 2, 0xFFAAAAAA);
        int trackX1 = PANEL_WIDTH - PADDING;
        int trackY = y + 4;
        g.fill(COLOR_TRACK_X0, trackY, trackX1, trackY + 4, 0xFF444444);
        int thumbW = 6;
        int thumbX = COLOR_TRACK_X0 + (int) ((trackX1 - COLOR_TRACK_X0 - thumbW) * Math.max(0, Math.min(1, t)));
        boolean hover = mx >= thumbX && mx <= thumbX + thumbW && my >= y && my <= y + 12;
        g.fill(thumbX, y, thumbX + thumbW, y + 12,
                hover || colorSliderDrag == id ? 0xFFAAAAAA : 0xFF888888);
        return y + 12;
    }

    /** 当前编辑器 ARGB (透明度滑条 → alpha = 255×(1−t), 即透明度 0 = 纯色剪影)。 */
    private static int currentEditorArgb() {
        int a = (int) ((1f - editorHSV[3]) * 255f);
        return (a << 24) | (java.awt.Color.HSBtoRGB(editorHSV[0], editorHSV[1], editorHSV[2]) & 0xFFFFFF);
    }

    /** 由组的当前颜色同步编辑器 HSV 状态并展开 (先落盘上一会话未保存的颜色)。 */
    private void openGroupEditor(String group) {
        if (editorColorDirty)
            StructureDataConfig.flush();
        editorColorDirty = false;
        editorGroup = group;
        deleteArmed = false;
        int argb = StructureGroups.colorOf(StructureDataConfig.getActiveData(), group);
        float[] hsv = rgbToHsv(argb);
        editorHSV[0] = hsv[0];
        editorHSV[1] = hsv[1];
        editorHSV[2] = hsv[2];
        editorHSV[3] = 1f - ((argb >>> 24) & 0xFF) / 255f;
        if (groupEditField != null) {
            groupEditField.setValue(displayNameOf(group));
            groupEditField.setEditable(!StructureGroups.isBuiltin(group));
            groupEditField.setTextColor(0xFFFFFFFF);
        }
        groupCountCooldown = 0;
    }

    /** 新建用户组 (轮转默认色) 并立即展开编辑器。 */
    private void createGroup() {
        int color = NEW_GROUP_PALETTE[newGroupColorIdx % NEW_GROUP_PALETTE.length];
        String base = I18n.get("xsm.gui.panel.group_new_name");
        for (int n = 1; n < 1000; n++) {
            String name = base + n;
            if (StructureDataConfig.addGroup(name, color)) {
                newGroupColorIdx++;
                openGroupEditor(name);
                return;
            }
        }
    }

    /** 拖拽中的颜色滑条按 mouseX 更新 (只改内存, mouseReleased 时 flush)。 */
    private void applyColorSliderDrag(int mx) {
        if (colorSliderDrag < 1 || editorGroup == null)
            return;
        int trackX1 = PANEL_WIDTH - PADDING;
        float t = (float) (mx - COLOR_TRACK_X0) / (trackX1 - COLOR_TRACK_X0 - 6);
        editorHSV[colorSliderDrag - 1] = Math.max(0, Math.min(1, t));
        StructureDataConfig.previewGroupColor(editorGroup, currentEditorArgb());
        editorColorDirty = true;
    }

    private static float[] rgbToHsv(int argb) {
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        float[] hsv = new float[3];
        java.awt.Color.RGBtoHSB(r, g, b, hsv);
        return hsv;
    }

    // ─── Tab: 图标 ───────────────────────────────────────────

    /** 图标大小滑条几何 (单一来源: render / click / 拖拽轮询共用)。 */
    private record IconSliderGeom(int sliderStart, int sliderEnd, int trackLen) {
        /** v → t (多项式正映射)。 */
        float valueToT(float v) {
            return (float) ((-1.75f + Math.sqrt(3.0225f + 0.8f * v)) / 0.4f);
        }

        /** t → v (逆映射)。 */
        static float tToValue(float t) {
            return 0.05f + (1.75f + 0.2f * t) * t;
        }
    }

    private IconSliderGeom iconSliderGeom() {
        int labelW = font.width(I18n.get("xsm.gui.panel.icon_size"));
        int valW = font.width(String.format("%.2f", sliderValue));
        int sliderStart = PADDING + labelW + 5;
        int sliderEnd = PANEL_WIDTH - PADDING - valW - 5;
        return new IconSliderGeom(sliderStart, sliderEnd, sliderEnd - sliderStart - 8); // 减 thumbW
    }

    private int renderIconsTab(GuiGraphicsExtractor g, int mx, int my, StructLayout L) {
        int y = L.contentY() + 5;
        int thumbW = 8;
        int thumbH = 12;

        var geo = iconSliderGeom();
        String sizeTxt = String.format("%.2f", sliderValue);
        String sliderLabel = I18n.get("xsm.gui.panel.icon_size");
        int trackY = y + (thumbH - 4) / 2;

        g.text(font, sliderLabel, PADDING, y + (thumbH - font.lineHeight) / 2, 0xFFFFFFFF);
        g.fill(geo.sliderStart(), trackY, geo.sliderEnd(), trackY + 4, 0xFF444444);

        float t = geo.valueToT(sliderValue);
        int thumbX = geo.sliderStart() + (int) (t * geo.trackLen());
        boolean hoverThumb = mx >= thumbX && mx <= thumbX + thumbW
                && my >= y && my <= y + thumbH;
        g.fill(thumbX, y, thumbX + thumbW, y + thumbH,
                hoverThumb || sliderDragging ? 0xFFAAAAAA : 0xFF888888);

        g.text(font, sizeTxt, geo.sliderEnd() + 5, y + (thumbH - font.lineHeight) / 2, 0xFFFFFFFF);

        // loot preview checkbox
        y += thumbH + 5;
        boolean lootOn = ServerConfig.isLootPreviewEnabled();
        boolean hoverLoot = mx >= PADDING && mx <= PANEL_WIDTH - PADDING
                && my >= y && my <= y + ITEM_H;
        PanelUi.renderCheckbox(g, PADDING, y + (ITEM_H - 9) / 2, lootOn, hoverLoot);
        g.text(font, I18n.get("xsm.gui.panel.loot_preview"), PADDING + 12,
                y + (ITEM_H - font.lineHeight) / 2,
                lootOn ? 0xFFFFFFFF : 0xFF888888);

        // loot display mode button (right side of same row)
        String modeName = I18n.get(ServerConfig.getLootDisplayMode().translationKey());
        int modeBtnW = font.width(modeName) + 10;
        int modeBtnX = PANEL_WIDTH - PADDING - modeBtnW;
        int modeBtnH = ITEM_H;
        boolean hoverMode = mx >= modeBtnX && mx <= modeBtnX + modeBtnW
                && my >= y && my <= y + modeBtnH;
        g.fill(modeBtnX, y, modeBtnX + modeBtnW, y + modeBtnH,
                hoverMode ? 0xFF666666 : 0xFF333333);
        if (lootOn && hoverMode)
            g.fill(modeBtnX, y, modeBtnX + modeBtnW, y + 1, 0xFFFFFFFF);
        g.text(font, modeName, modeBtnX + 5, y + (modeBtnH - font.lineHeight) / 2,
                lootOn ? 0xFFFFFFFF : 0xFF888888);

        return y + ITEM_H;
    }

    // ─── MOUSE ──────────────────────────────────────────────

    @Override
    boolean mouseClicked(int mx, int my, int button, int headerY) {
        // header: checkbox 切总开关, 其余切折叠
        if (PanelUi.hitCheckbox(mx, my, headerY)) {
            PanelUi.playClickSound();
            ServerConfig.setStructureEnabled(!ServerConfig.isStructureEnabled());
            return true;
        }
        if (PanelUi.hitHeader(mx, my, headerY)) {
            PanelUi.playClickSound();
            structureExpanded = !structureExpanded;
            return true;
        }
        if (!structureExpanded)
            return false; // 折叠时点击头部以下 → 宿主推进到性能区

        var L = structLayout(headerY);
        int tab = hitTab(mx, my, L);
        if (tab >= 0 && tab != structTab) {
            PanelUi.playClickSound();
            structTab = tab;
            deleteArmed = false;
            colorSliderDrag = -1;
            if (tab == 1)
                groupCountCooldown = 0;
            return true;
        }

        // 结构区内容底界 = render 每帧回写的 bottom()
        // (点击越界 → 宿主落入性能区; 不得在此重算)
        int structBottom = bottom();
        if (my < structBottom) {
            if (structTab == 0) {
                // 批量动作行 (区域内点击一律消费; 置灰钮忽略)
                var actionLayout = BatchActionRow.layout(font, L.actionRowY());
                int action = BatchActionRow.hit(actionLayout, mx, my);
                if (action >= 0) {
                    if (batchEnabled()[action]) {
                        PanelUi.playClickSound();
                        applyBatch(action);
                    }
                    return true;
                }
                // structure list items
                if (filteredStructures == null)
                    updateStructFilter();
                int size = filteredStructures.size();
                int end = Math.min(structScrollOff + L.typeVisible(), size);
                for (int i = structScrollOff; i < end; i++) {
                    int itemY = L.typeListY() + (i - structScrollOff) * ITEM_H;
                    if (my >= itemY && my <= itemY + ITEM_H && mx >= PADDING && mx <= PANEL_WIDTH - PADDING) {
                        StructRow row = filteredStructures.get(i);
                        var wc = ServerConfig.getActiveWorldConfig();
                        if (wc != null) {
                            PanelUi.playClickSound();
                            StructureBitFlagView flags = wc.getDisabledStructures();
                            if (row.isVariant()) {
                                int v = row.variant();
                                boolean cur = !flags.isStructureSet(row.type().id)
                                        && !flags.isVariantSet(row.type().id, v);
                                wc.setVariantEnabled(row.type().id, v, !cur);
                            } else {
                                boolean cur = !flags.isStructureSet(row.type().id);
                                wc.setStructureEnabled(row.type().id, !cur);
                                updateStructFilter(); // 结构禁用 → 收拢变种行
                            }
                        }
                        return true;
                    }
                }
                return true;
            } else if (structTab == 1) {
                return clickGroupsTab(mx, my, L);
            } else {
                return clickIconsTab(mx, my, L);
            }
        }

        return false; // 内容底界以下 → 宿主落入性能区
    }

    /** 分组 tab 点击: 组行 (checkbox/展开编辑器) + 编辑器内部 + 新建组。 */
    private boolean clickGroupsTab(int mx, int my, StructLayout L) {
        var names = allGroupNames();
        int listBottom = L.groupListY() + L.groupVisibleRows() * ITEM_H;
        int y = L.groupListY();
        for (int i = groupsScrollOff; i < names.size() && y < listBottom; i++) {
            String group = names.get(i);
            if (my >= y && my < y + ITEM_H) {
                deleteArmed = false;
                colorSliderDrag = -1;
                if (mx >= PADDING && mx <= PADDING + 9) {
                    PanelUi.playClickSound();
                    StructureDataConfig.setGroupHidden(group,
                            !StructureDataConfig.isGroupHidden(group));
                    return true;
                }
                if (mx >= PADDING + 12) {
                    PanelUi.playClickSound();
                    // 再点当前展开的组行 = 收起; 否则展开/搬移 (含未分组)
                    if (group.equals(editorGroup)) {
                        if (editorColorDirty) {
                            editorColorDirty = false;
                            StructureDataConfig.flush();
                        }
                        editorGroup = null;
                    } else
                        openGroupEditor(group);
                    groupCountCooldown = 0;
                }
                return true;
            }
            y += ITEM_H;
            if (group.equals(editorGroup)) {
                if (handleGroupEditorClick(mx, my, y, group))
                    return true;
                y += EDITOR_H;
            }
        }
        // 新建组行
        if (y < listBottom && my >= y && my < y + ITEM_H) {
            deleteArmed = false;
            PanelUi.playClickSound();
            createGroup();
            return true;
        }
        return true;
    }

    /** 编辑器内部命中 (滑条/删除/恢复默认色/完成); 区域内点击一律消费。 */
    private boolean handleGroupEditorClick(int mx, int my, int y, String group) {
        if (my < y || my >= y + EDITOR_H)
            return false;

        // 滑条行 (名称行区域点击吞掉即可)
        int sliderY = y + 16;
        for (int id = 1; id <= 4; id++) {
            if (my >= sliderY && my < sliderY + 12) {
                deleteArmed = false;
                if (mx >= COLOR_TRACK_X0 && mx <= PANEL_WIDTH - PADDING) {
                    PanelUi.playClickSound();
                    colorSliderDrag = id;
                    applyColorSliderDrag(mx);
                }
                return true;
            }
            sliderY += 12;
        }

        // 按钮行
        int btnY = sliderY;
        int x0 = PADDING + 12;
        if (my >= btnY && my < btnY + ITEM_H) {
            if (!StructureGroups.isBuiltin(group)) {
                String delLabel = I18n.get(deleteArmed
                        ? "xsm.gui.panel.group_delete_confirm" : "xsm.gui.panel.group_delete");
                int delW = font.width(delLabel) + 8;
                if (mx >= x0 && mx <= x0 + delW) {
                    PanelUi.playClickSound();
                    if (!deleteArmed) {
                        deleteArmed = true;
                    } else if (StructureDataConfig.removeGroup(group)) {
                        editorGroup = null;
                        editorColorDirty = false; // removeGroup 已 flush
                    }
                    return true;
                }
                deleteArmed = false;
            } else {
                var sdata = StructureDataConfig.getActiveData();
                if (sdata != null && sdata.colorOf(group) != 0) {
                    String resetLabel = I18n.get("xsm.gui.panel.group_reset_color");
                    int rw = font.width(resetLabel) + 8;
                    if (mx >= x0 && mx <= x0 + rw) {
                        PanelUi.playClickSound();
                        StructureDataConfig.clearGroupColor(group);
                        openGroupEditor(group); // 同步滑条到默认色
                        return true;
                    }
                }
            }
            String doneLabel = I18n.get("xsm.gui.panel.group_done");
            int doneW = font.width(doneLabel) + 8;
            int doneX = PANEL_WIDTH - PADDING - doneW;
            if (mx >= doneX && mx <= doneX + doneW) {
                deleteArmed = false;
                if (canCommitRename(group)) {
                    PanelUi.playClickSound();
                    // 改名内部已 flush (含颜色); openGroupEditor 会清 dirty
                    commitGroupRename(group);
                } else if (editorColorDirty) {
                    PanelUi.playClickSound();
                    editorColorDirty = false;
                    StructureDataConfig.flush();
                }
                return true;
            }
        }
        deleteArmed = false;
        return true;
    }

    /** 改名目标名是否合法: 非空白/不超长/不撞内置保留名/不与其它自定义组重名 (自身不算冲突)。 */
    private boolean renameTargetNameOk(@Nullable String raw, @Nullable String self) {
        String t = raw == null ? "" : raw.trim();
        if (!StructureData.validGroupName(t))
            return false;
        if (t.equals(self))
            return true;
        for (var ug : StructureDataConfig.userGroups())
            if (ug.name().equals(t))
                return false;
        return true;
    }

    /** 当前编辑器能否提交改名 (完成 按钮可用): 用户组 + 名合法 + 不重名 + 确实改动。 */
    private boolean canCommitRename(@Nullable String group) {
        if (group == null || groupEditField == null)
            return false;
        if (StructureGroups.isBuiltin(group))
            return false;
        String t = groupEditField.getValue().trim();
        return !t.equals(group) && renameTargetNameOk(t, group);
    }

    /** 提交名称修改 (内置组不可改名); 失败静默 (输入框已红名提示)。 */
    private void commitGroupRename(String group) {
        if (groupEditField == null)
            return;
        String newName = groupEditField.getValue().trim();
        if (!StructureData.validGroupName(newName) || newName.equals(group))
            return;
        if (StructureDataConfig.renameGroup(group, newName))
            openGroupEditor(newName);
    }

    /** 图标 tab 点击: 图标大小滑条 + 战利品预览 + 显示模式。 */
    private boolean clickIconsTab(int mx, int my, StructLayout L) {
        int y = L.contentY() + 5;
        int thumbW = 8;
        int thumbH = 12;
        var geo = iconSliderGeom();
        float t = geo.valueToT(sliderValue);
        int thumbX = geo.sliderStart() + (int) (t * geo.trackLen());

        if (mx >= thumbX && mx <= thumbX + thumbW && my >= y && my <= y + thumbH) {
            PanelUi.playClickSound();
            sliderDragging = true;
            updateSlider(mx);
            return true;
        }
        // also allow click on track
        if (mx >= geo.sliderStart() && mx <= geo.sliderEnd() && my >= y && my <= y + thumbH) {
            PanelUi.playClickSound();
            sliderDragging = true;
            updateSlider(mx);
            return true;
        }

        // loot preview checkbox
        y += thumbH + 5;
        if (mx >= PADDING && mx <= PADDING + 9 && my >= y && my <= y + ITEM_H) {
            PanelUi.playClickSound();
            boolean next = !ServerConfig.isLootPreviewEnabled();
            ServerConfig.setLootPreviewEnabled(next);
            if (!next)
                LootPreviewState.close();
            return true;
        }
        // loot display mode button (cycle)
        String modeName = I18n.get(ServerConfig.getLootDisplayMode().translationKey());
        int modeBtnW = font.width(modeName) + 10;
        int modeBtnX = PANEL_WIDTH - PADDING - modeBtnW;
        if (mx >= modeBtnX && mx <= modeBtnX + modeBtnW && my >= y && my <= y + ITEM_H) {
            PanelUi.playClickSound();
            var next = ServerConfig.getLootDisplayMode().ordinal() + 1;
            ServerConfig.setLootDisplayMode(LootDisplayMode.values()[next % LootDisplayMode.values().length]);
            LootPreviewState.close();
            return true;
        }
        return true;
    }

    @Override
    boolean mouseScrolled(int mx, int my, double dir, int headerY) {
        if (!structureExpanded)
            return false;
        var L = structLayout(headerY);
        if (structTab == 0) {
            int typeTop = L.typeListY();
            int typeBottom = typeTop + L.typeVisible() * ITEM_H;
            if (my >= typeTop && my < typeBottom) {
                if (filteredStructures == null)
                    updateStructFilter();
                int maxOff = Math.max(0, filteredStructures.size() - L.typeVisible());
                structScrollOff = Math.max(0, Math.min(maxOff, structScrollOff + (int) dir));
                return true;
            }
        } else if (structTab == 1) {
            int top = L.groupListY();
            int listBottom = top + L.groupVisibleRows() * ITEM_H;
            if (my >= top && my < listBottom) {
                int rowsUsed = groupRowsUsed(allGroupNames());
                int maxOff = Math.max(0, rowsUsed - L.groupVisibleRows());
                groupsScrollOff = Math.max(0, Math.min(maxOff, groupsScrollOff + (int) dir));
                return true;
            }
        }
        return false;
    }

    /** 图标大小滑条按 mouseX 更新 (轨道点击/拖拽共用)。 */
    private void updateSlider(int mx) {
        var geo = iconSliderGeom();
        float t = (mx - geo.sliderStart()) / (float) geo.trackLen();
        t = Math.max(0, Math.min(1, t));
        sliderValue = IconSliderGeom.tToValue(t);
        ServerConfig.setStructureIconSize(sliderValue);
    }

    // ─── 过滤与计数 ──────────────────────────────────────────

    private void updateStructFilter() {
        filteredStructures = new ArrayList<>();
        String search = structSearchText.toLowerCase();
        var wc = ServerConfig.getActiveWorldConfig();
        StructureBitFlagView flags = wc != null ? wc.getDisabledStructures() : DEFAULT_FLAGS;
        for (StructureType s : StructureType.values()) {
            boolean structVisible = !flags.isStructureSet(s.id);
            IntList variants = s.getVariants();
            if (typeMatchesSearch(s, search)) {
                filteredStructures.add(new StructRow(s, null));
                if (structVisible) {
                    for (int v : variants)
                        filteredStructures.add(new StructRow(s, v));
                }
                continue;
            }
            if (variants.isEmpty())
                continue;
            // 搜索命中变种名 → 显示类型行 + 仅命中的变种行
            boolean addedType = false;
            for (int v : variants) {
                if (I18n.get(s.variantTranslationKey(v)).toLowerCase().contains(search)) {
                    if (!addedType) {
                        filteredStructures.add(new StructRow(s, null));
                        addedType = true;
                    }
                    if (structVisible)
                        filteredStructures.add(new StructRow(s, v));
                }
            }
        }
    }

    /**
     * 刷新分组计数 (渲染线程, 每 20 帧且面板展开时调用)。
     * 命名组 = 标记表全量计数 (用户标记量级, 极小);
     * 未分组 = 当前视口内未标记 (或组为默认) 的可见图标数, 遍历
     * {@link StructureCache#REGIONS} + 要塞快照, 与渲染同源的类型/变种过滤,
     * 逐图标仅一次 map get, 无 native 调用。
     */
    private void updateGroupCounts() {
        var wc = ServerConfig.getActiveWorldConfig();
        StructureBitFlagView flags = wc != null ? wc.getDisabledStructures() : DEFAULT_FLAGS;
        var enabledTypes = wc != null ? wc.getStructureTypeSet() : BitSetView.EMPTY;
        var dimData = StructureDataConfig.activeDimData();

        GROUP_COUNTS.clear();
        for (String group : allGroupNames()) {
            if (group.isEmpty())
                continue; // 未分组单独按视口统计
            GROUP_COUNTS.put(group, dimData == null ? 0
                    : dimData.countGroup(group, enabledTypes));
        }

        int ungrouped = 0;
        for (var entry : StructureCache.REGIONS.entrySet()) {
            StructureType type = entry.getKey();
            if (flags.isStructureSet(type.id))
                continue;
            for (StructureCache.StructurePos rp : entry.getValue()) {
                if (!rp.loaded())
                    continue;
                if (flags.isVariantSet(type.id, rp.getVariant()))
                    continue;
                var mark = dimData == null ? null
                        : dimData.getMark(type.id, StructureDataConfig.keyOf(rp.blockX(), rp.blockZ()));
                if (mark == null || mark.group().isEmpty())
                    ungrouped++;
            }
        }
        if (!flags.isStructureSet(StructureType.STRONGHOLD.id)) {
            var strongholds = StructureCache.strongholds();
            if (strongholds != null) {
                for (var sh : strongholds) {
                    if (sh == null)
                        continue;
                    var mark = dimData == null ? null
                            : dimData.getMark(StructureType.STRONGHOLD.id,
                                    StructureDataConfig.keyOf(sh.blockX(), sh.blockZ()));
                    if (mark == null || mark.group().isEmpty())
                        ungrouped++;
                }
            }
        }
        GROUP_COUNTS.put(StructureGroups.DEFAULT, ungrouped);
    }

    // ─── E2E gametest 钩子 (生产路径不调用) ─────────────────────

    /** 展开结构区 (面板截图前置状态; 保持生物群系折叠, 小屏下结构区才有可见空间)。 */
    void setExpandedForTest() {
        structureExpanded = true;
        groupCountCooldown = 0;
    }

    /** 切换结构区 Tab (0=类型 1=分组 2=图标)。 */
    void selectTabForTest(int tab) {
        structTab = Math.max(0, Math.min(2, tab));
        deleteArmed = false;
        colorSliderDrag = -1;
        groupCountCooldown = 0;
    }

    /** 新建用户组并展开其编辑器; 返回组名 (失败 null)。 */
    @Nullable String createGroupForTest() {
        createGroup();
        return editorGroup;
    }

    /** 设置搜索文本并刷新过滤 (模拟 EditBox responder)。 */
    void setSearchForTest(String text) {
        structSearchText = text == null ? "" : text;
        structScrollOff = 0;
        updateStructFilter();
    }

    /**
     * 执行类型 tab 批量操作 (all/none/invert/only); 返回启用类型数 (断言用)。
     * 操作与真实点击同路径 (applyBatch)。
     */
    int batchForTest(String action) {
        int idx = switch (action) {
            case "all" -> BatchActionRow.ALL;
            case "none" -> BatchActionRow.NONE;
            case "invert" -> BatchActionRow.INVERT;
            case "only" -> BatchActionRow.SHOW_ONLY;
            default -> throw new IllegalArgumentException("unknown batch action: " + action);
        };
        applyBatch(idx);
        var wc = ServerConfig.getActiveWorldConfig();
        int enabled = 0;
        if (wc != null) {
            var flags = wc.getDisabledStructures();
            for (StructureType t : StructureType.values())
                if (!flags.isStructureSet(t.id))
                    enabled++;
        }
        return enabled;
    }
}
