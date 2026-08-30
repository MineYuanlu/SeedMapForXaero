package bid.yuanlu.seedmap4xaero.client.gui;

import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.Nullable;

import bid.yuanlu.seedmap4xaero.client.biome.BiomeType;
import bid.yuanlu.seedmap4xaero.client.cache.CellCache;
import bid.yuanlu.seedmap4xaero.client.cache.StructureCache;
import bid.yuanlu.seedmap4xaero.client.configs.basic.LootDisplayMode;
import bid.yuanlu.seedmap4xaero.client.configs.basic.ServerConfig;
import bid.yuanlu.seedmap4xaero.client.configs.structure.StructureData;
import bid.yuanlu.seedmap4xaero.client.configs.structure.StructureDataConfig;
import bid.yuanlu.seedmap4xaero.client.configs.structure.StructureGroups;
import bid.yuanlu.seedmap4xaero.client.nativeapi.Xsm;
import bid.yuanlu.seedmap4xaero.client.render.BiomeColorTable;
import bid.yuanlu.seedmap4xaero.client.structure.LootPreviewState;
import bid.yuanlu.seedmap4xaero.client.structure.StructureBitFlag;
import bid.yuanlu.seedmap4xaero.client.structure.StructureBitFlagView;
import bid.yuanlu.seedmap4xaero.client.structure.StructureType;
import bid.yuanlu.seedmap4xaero.utils.BitSetView;
import it.unimi.dsi.fastutil.ints.IntList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.WidgetSprites;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import xaero.map.gui.GuiMap;

public class SeedMapPanel {

    private static final int PANEL_WIDTH = 220;
    private static final int HEADER_H = 20;
    private static final int ITEM_H = 12;
    private static final int PADDING = 5;
    private static final int MIN_VISIBLE_ITEMS = 4;
    private static final int MAX_VISIBLE_ITEMS = 18;

    private final GuiMap screen;
    private final Minecraft mc;
    private final Font font;

    // session-scoped UI state: survives map screen reopen, cleared only when the game closes
    public static boolean panelOpen;

    // section expand state
    private static boolean biomeExpanded;
    private static boolean structureExpanded;

    // scroll
    private static int biomeScrollOff;
    private static int structScrollOff;

    // search state (keys only, no EditBox widget for now)
    private static String biomeSearchText = "";
    private static String structSearchText = "";

    // filtered lists
    private List<BiomeType> filteredBiomes;
    private List<StructRow> filteredStructures;

    /**
     * 结构区一行: 类型行 (variant==null) 或该类型下的变种行 (缩进)。
     * 树形结构, 默认全部展开。
     */
    private record StructRow(StructureType type, @Nullable Integer variant) {
        boolean isVariant() {
            return variant != null;
        }
    }

    /** 无配置时的回退 flags: 全 0 = 全部可见 */
    private static final StructureBitFlagView DEFAULT_FLAGS = new StructureBitFlag();

    /** 分组计数缓存: 命名组 = 全量标记数, 未分组 = 当前视口可见的未标记图标数; 每 20 帧刷新 */
    private static final java.util.HashMap<String, Integer> GROUP_COUNTS = new java.util.HashMap<>();
    private static int groupCountCooldown;

    // ─── 结构区 Tab (类型/分组/图标) ────────────────────────────
    private static final int TAB_H = 14;
    private static final int TAB_GAP = 2;
    /** 组编辑器高度: 名称行 + 4 滑条 + 按钮行 + 尾距 (须与 renderGroupEditor 实际推进严格一致)。 */
    private static final int EDITOR_H = 16 + 4 * 12 + ITEM_H + 2;
    /** 编辑器高度折算的列表行数 (滚动钳制用)。 */
    private static final int EDITOR_ROWS = (EDITOR_H + ITEM_H - 1) / ITEM_H;
    /** 颜色滑条轨道起点 (标签固定宽)。 */
    private static final int COLOR_TRACK_X0 = PADDING + 34;

    /** 新建组的轮转默认色 (alpha=0: 默认无遮罩, 仅文字/色块着色)。 */
    private static final int[] NEW_GROUP_PALETTE = {
            0x00FF5555, 0x00FFAA00, 0x00FFFF55, 0x0055FF55,
            0x0000FFAA, 0x0000AAFF, 0x00AA55FF, 0x00FF55FF };
    private static int newGroupColorIdx;

    /** 结构区激活 Tab: 0=类型 1=分组 2=图标 (会话级)。 */
    private static int structTab;
    private static int groupsScrollOff;
    /** 正在展开编辑器的组名; null = 关闭。 */
    private static String editorGroup;
    private static boolean deleteArmed;
    /** 编辑器颜色状态: h/s/v + 透明度 (0=纯色剪影, 1=无遮罩), 均 [0,1]。 */
    private static final float[] editorHSV = new float[4];
    /** 拖拽中的颜色滑条 id 1..4; -1 = 无 (松开时 flush)。 */
    private static int colorSliderDrag = -1;

    // slider
    private float sliderValue = 1.0f;
    public boolean sliderDragging;

    // search edit boxes
    private EditBox biomeSearchField;
    private EditBox structSearchField;
    /** 组编辑器的名称输入框 (随编辑器展开定位)。 */
    private EditBox groupEditField;

    // screen dimensions
    private int scrW, scrH;

    /** 当前 GuiMap 的面板实例 (onInit 注册; E2E gametest 取用)。 */
    private static SeedMapPanel activePanel;

    public static SeedMapPanel activePanel() {
        return activePanel;
    }

    // ─── E2E gametest 钩子 (生产路径不调用) ─────────────────────

    /** 展开结构区 (面板截图前置状态; 保持生物群系折叠, 小屏下结构区才有可见空间)。 */
    public void testExpandSections() {
        structureExpanded = true;
        groupCountCooldown = 0;
    }

    /** 切换结构区 Tab (0=类型 1=分组 2=图标)。 */
    public void testSelectStructTab(int tab) {
        structTab = Math.max(0, Math.min(2, tab));
        deleteArmed = false;
        colorSliderDrag = -1;
        groupCountCooldown = 0;
    }

    /** 新建用户组并展开其编辑器; 返回组名 (失败 null)。 */
    public String testCreateGroup() {
        createGroup();
        return editorGroup;
    }

    public SeedMapPanel(GuiMap screen) {
        this.screen = screen;
        this.mc = Minecraft.getInstance();
        this.font = mc.font;
    }

    public void toggleOpen() {
        panelOpen = !panelOpen;
        if (panelOpen) {
            sliderDragging = false;
            sliderValue = ServerConfig.getStructureIconSize();
            updateBiomeFilter();
            updateStructFilter();
        } else {
            if (biomeSearchField != null) {
                biomeSearchField.setFocused(false);
            }
            if (structSearchField != null) {
                structSearchField.setFocused(false);
            }
        }
    }

    public void onInit(int width, int height) {
        this.scrW = width;
        this.scrH = height;
        activePanel = this;

        // recreate search fields if panel is open
        if (panelOpen) {
            String prevBiome = biomeSearchText;
            String prevStruct = structSearchText;

            int searchY = PADDING + HEADER_H + PADDING + 20 + PADDING;
            biomeSearchField = new EditBox(font, PADDING + 24, searchY, PANEL_WIDTH - PADDING - 28, 14,
                    Component.translatable("xsm.gui.panel.search_biomes"));
            biomeSearchField.setValue(prevBiome);
            biomeSearchField.setResponder(s -> {
                biomeSearchText = s;
                updateBiomeFilter();
                biomeScrollOff = 0;
            });
            biomeSearchField.setCanLoseFocus(true);
            biomeSearchField.setVisible(biomeExpanded);
            screen.addButton(biomeSearchField);

            structSearchField = new EditBox(font, PADDING + 12, searchY, PANEL_WIDTH - PADDING - 14, 14,
                    Component.translatable("xsm.gui.panel.search_structures"));
            structSearchField.setValue(prevStruct);
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
                String t = s.trim();
                groupEditField.setTextColor(t.isEmpty() || StructureData.validGroupName(t)
                        ? 0xFFFFFFFF : 0xFFFF5555);
            });
            if (editorGroup != null)
                groupEditField.setValue(displayNameOf(editorGroup));
            screen.addButton(groupEditField);
        }
    }

    // ─── RENDER ──────────────────────────────────────────────

    public void render(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        if (!panelOpen)
            return;

        // slider drag update
        if (sliderDragging) {
            updateSlider(mouseX);
        }
        // color slider drag: live preview (flush on release)
        if (colorSliderDrag > 0) {
            applyColorSliderDrag(mouseX);
        }

        // refresh slider value from config
        sliderValue = ServerConfig.getStructureIconSize();

        renderPanelBg(g);

        int y = PADDING;
        y = renderBiomeSection(g, mouseX, mouseY, y);
        y += 5;
        y = renderStructSection(g, mouseX, mouseY, y);
    }

    private void renderPanelBg(GuiGraphicsExtractor g) {
        g.fill(0, 0, PANEL_WIDTH, scrH, 0x77_000000);
    }

    private int renderBiomeSection(GuiGraphicsExtractor g, int mx, int my, int y) {
        boolean enabled = isBiomeEnabled();
        boolean hoverHdr = my >= y && my < y + HEADER_H && mx >= PADDING && mx <= PANEL_WIDTH - PADDING;
        if (hoverHdr)
            g.fill(PADDING, y, PANEL_WIDTH - PADDING, y + HEADER_H, 0x22_FFFFFF);

        boolean hoverCb = hitCheckbox(mx, my, y);
        renderCheckbox(g, PADDING, y + (HEADER_H - 9) / 2, enabled, hoverCb);

        String label = I18n.get("xsm.gui.panel.biomes_header", I18n.get(enabled ? "xsm.value.on" : "xsm.value.off"));
        g.text(font, label, PADDING + 12, y + (HEADER_H - font.lineHeight) / 2,
                enabled ? 0xFFFFFFFF : 0xFF888888);

        String arrow = biomeExpanded ? "▼" : "▶";
        int arrX = PANEL_WIDTH - PADDING - font.width(arrow);
        g.text(font, arrow, arrX, y + (HEADER_H - font.lineHeight) / 2, 0xFFFFFFFF);

        y += HEADER_H;

        if (biomeSearchField != null)
            biomeSearchField.setVisible(biomeExpanded);

        if (!biomeExpanded)
            return y;

        y += PADDING;

        // color scheme MC-style button + search edit box on same row
        int schemeX = PADDING;
        int schemeY = y;
        int schemeW = 20;
        int schemeH = 20;
        boolean hoverSc = mx >= schemeX && mx <= schemeX + schemeW
                && my >= schemeY && my <= schemeY + schemeH;

        var btnSprites = new WidgetSprites(
                Identifier.withDefaultNamespace("widget/button"),
                Identifier.withDefaultNamespace("widget/button_disabled"),
                Identifier.withDefaultNamespace("widget/button_highlighted"));
        g.blitSprite(RenderPipelines.GUI_TEXTURED,
                btnSprites.get(true, hoverSc),
                schemeX, schemeY, schemeW, schemeH, 1.0f);
        g.text(font, "C", schemeX + 6, schemeY + 6, 0xFFFFFFFF);

        int searchX = schemeX + schemeW + 5;
        int searchW = PANEL_WIDTH - PADDING - searchX;
        if (biomeSearchField != null) {
            biomeSearchField.setVisible(true);
            biomeSearchField.setX(searchX);
            biomeSearchField.setY(schemeY);
            biomeSearchField.setWidth(searchW);
            biomeSearchField.extractRenderState(g, mx, my, 0);
        }

        if (hoverSc) {
            var provider = BiomeColorTable.resolveProvider();
            String tip = I18n.get("xsm.gui.panel.color_table",
                    provider != null ? I18n.get(provider.translationKey()) : "?");
            int tw = font.width(tip);
            int tipX = schemeX;
            int tipY = schemeY - font.lineHeight - 4;
            if (tipY < 0) tipY = schemeY + schemeH + 2;
            g.fill(tipX - 2, tipY - 2, tipX + tw + 4, tipY + font.lineHeight + 2, 0xCC000000);
            g.text(font, tip, tipX, tipY, 0xFFFFFFFF);
        }

        y += schemeH + PADDING;

        // biome list
        if (filteredBiomes == null)
            updateBiomeFilter();
        int visible = Math.min(MAX_VISIBLE_ITEMS,
                Math.max(MIN_VISIBLE_ITEMS, (scrH - y - 10) / ITEM_H));
        int size = filteredBiomes.size();
        if (biomeScrollOff > size - visible)
            biomeScrollOff = Math.max(0, size - visible);

        int end = Math.min(biomeScrollOff + visible, size);
        var wc = ServerConfig.getActiveWorldConfig();
        var disabledBiomes = wc != null ? wc.getDisabledBiomes() : BitSetView.EMPTY;

        for (int i = biomeScrollOff; i < end; i++) {
            BiomeType b = filteredBiomes.get(i);
            int itemY = y + (i - biomeScrollOff) * ITEM_H;
            boolean bi = !disabledBiomes.get(b.id);
            boolean hover = mx >= PADDING && mx <= PANEL_WIDTH - PADDING
                    && my >= itemY && my <= itemY + ITEM_H;
            renderCheckbox(g, PADDING, itemY + (ITEM_H - 9) / 2, bi, hover);
            float u0 = (b.spriteIndex * 16f) / BiomeType.SPRITESHEET_WIDTH;
            float u1 = u0 + 16f / BiomeType.SPRITESHEET_WIDTH;
            g.blit(BiomeType.BIOMES_TEXTURE,
                    PADDING + 12, itemY + 1,
                    PADDING + 22, itemY + 11,
                    u0, u1, 0.0F, 1.0F);
            String bioKey = "biome.minecraft." + b.name;
            String bioName = b.name.indexOf(' ') < 0 && b.name.indexOf('(') < 0
                    && !I18n.get(bioKey).equals(bioKey) ? I18n.get(bioKey) : b.name;
            g.text(font, bioName, PADDING + 24, itemY + (ITEM_H - font.lineHeight) / 2,
                    bi ? 0xFFFFFFFF : 0xFF888888);
        }

        return y + visible * ITEM_H;
    }

    // ─── 结构区: Tab 布局 ──────────────────────────────────────

    /** 结构区内容区布局 (单一来源: render / mouseClicked / mouseScrolled 共用)。 */
    private record StructLayout(int tabY, int tabW, int contentY,
            int typeListY, int typeVisible,
            int groupListY, int groupVisibleRows) {
    }

    private StructLayout structLayout(int headerY) {
        int tabY = headerY + HEADER_H + PADDING;
        int tabW = (PANEL_WIDTH - PADDING * 2 - TAB_GAP * 2) / 3;
        int contentY = tabY + TAB_H + PADDING;
        int typeListY = contentY + 16; // 搜索框高
        int typeVisible = Math.min(MAX_VISIBLE_ITEMS,
                Math.max(MIN_VISIBLE_ITEMS, (scrH - typeListY - 30) / ITEM_H));
        int groupVisibleRows = Math.max(MIN_VISIBLE_ITEMS, (scrH - contentY - 10) / ITEM_H);
        return new StructLayout(tabY, tabW, contentY, typeListY, typeVisible, contentY, groupVisibleRows);
    }

    private void renderTabBar(GuiGraphicsExtractor g, StructLayout L, int mx, int my) {
        String[] keys = { "xsm.gui.panel.tab_types", "xsm.gui.panel.tab_groups", "xsm.gui.panel.tab_icons" };
        for (int i = 0; i < 3; i++) {
            int x = PADDING + i * (L.tabW() + TAB_GAP);
            boolean active = structTab == i;
            boolean hover = mx >= x && mx <= x + L.tabW() && my >= L.tabY() && my <= L.tabY() + TAB_H;
            g.fill(x, L.tabY(), x + L.tabW(), L.tabY() + TAB_H,
                    active ? 0xFF4A4A4A : hover ? 0xFF333333 : 0xFF262626);
            String label = I18n.get(keys[i]);
            g.text(font, label, x + (L.tabW() - font.width(label)) / 2,
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

    /** 超宽截断加省略号。 */
    private String truncate(String s, int maxW) {
        if (font.width(s) <= maxW)
            return s;
        while (!s.isEmpty() && font.width(s + "…") > maxW)
            s = s.substring(0, s.length() - 1);
        return s + "…";
    }

    private int renderStructSection(GuiGraphicsExtractor g, int mx, int my, int y) {
        boolean enabled = ServerConfig.isStructureEnabled();
        boolean hoverHdr = my >= y && my < y + HEADER_H && mx >= PADDING && mx <= PANEL_WIDTH - PADDING;
        if (hoverHdr)
            g.fill(PADDING, y, PANEL_WIDTH - PADDING, y + HEADER_H, 0x22_FFFFFF);

        boolean hoverCb = hitCheckbox(mx, my, y);
        renderCheckbox(g, PADDING, y + (HEADER_H - 9) / 2, enabled, hoverCb);

        String label = I18n.get("xsm.gui.panel.structures_header", I18n.get(enabled ? "xsm.value.on" : "xsm.value.off"));
        g.text(font, label, PADDING + 12, y + (HEADER_H - font.lineHeight) / 2,
                enabled ? 0xFFFFFFFF : 0xFF888888);

        String arrow = structureExpanded ? "▼" : "▶";
        int arrX = PANEL_WIDTH - PADDING - font.width(arrow);
        g.text(font, arrow, arrX, y + (HEADER_H - font.lineHeight) / 2, 0xFFFFFFFF);

        int structHeaderY = y;
        y += HEADER_H;

        if (structSearchField != null)
            structSearchField.setVisible(structureExpanded && structTab == 0);
        if (groupEditField != null)
            groupEditField.setVisible(structureExpanded && structTab == 1 && editorGroup != null);

        if (!structureExpanded)
            return y;

        var L = structLayout(structHeaderY);
        renderTabBar(g, L, mx, my);

        return switch (structTab) {
            case 0 -> renderTypesTab(g, mx, my, L);
            case 1 -> renderGroupsTab(g, mx, my, L);
            default -> renderIconsTab(g, mx, my, L);
        };
    }

    // ─── Tab: 类型 ───────────────────────────────────────────

    private int renderTypesTab(GuiGraphicsExtractor g, int mx, int my, StructLayout L) {
        int y = L.contentY();

        // search field
        if (structSearchField != null) {
            structSearchField.setY(y);
            structSearchField.extractRenderState(g, mx, my, 0);
        }
        y = L.typeListY();

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
                renderCheckbox(g, cbX, itemY + (ITEM_H - 9) / 2, on, hover);
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
                renderCheckbox(g, PADDING, itemY + (ITEM_H - 9) / 2, si, hover);

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
            renderCheckbox(g, PADDING, y + (ITEM_H - 9) / 2, shown, hoverRow);
            int color = StructureGroups.colorOf(sdata, group);
            int textX = PADDING + 12;
            if (!group.isEmpty()) {
                // 色块 (显示用强制不透明)
                g.fill(textX, y + 1, textX + 9, y + 10, 0xFF888888);
                g.fill(textX + 1, y + 2, textX + 8, y + 9, StructureGroups.opaque(color));
                textX += 12;
            }
            int count = GROUP_COUNTS.getOrDefault(group, 0);
            String full = truncate(displayNameOf(group) + " (" + count + ")",
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
        String doneLabel = I18n.get("xsm.gui.panel.group_done");
        int doneW = font.width(doneLabel) + 8;
        int doneX = PANEL_WIDTH - PADDING - doneW;
        boolean hovDone = my >= y && my <= y + ITEM_H && mx >= doneX && mx <= doneX + doneW;
        g.fill(doneX, y, doneX + doneW, y + ITEM_H, hovDone ? 0xFF666666 : 0xFF333333);
        g.text(font, doneLabel, doneX + 4, y + (ITEM_H - font.lineHeight) / 2, 0xFFFFFFFF);
        return y + ITEM_H + 2;
    }

    /** 线性小滑条 (标签 + 轨道 + thumb), 返回新的 y。 */
    private int renderColorSlider(GuiGraphicsExtractor g, int mx, int my, int y,
            int id, String labelKey, float t) {
        String label = I18n.get(labelKey);
        g.text(font, label, PADDING, y + (12 - font.lineHeight) / 2, 0xFFAAAAAA);
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

    /** 由组的当前颜色同步编辑器 HSV 状态并展开。 */
    private void openGroupEditor(String group) {
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

    private int renderIconsTab(GuiGraphicsExtractor g, int mx, int my, StructLayout L) {
        int y = L.contentY() + 5;
        int thumbW = 8;
        int thumbH = 12;

        String sizeTxt = String.format("%.2f", sliderValue);
        String sliderLabel = I18n.get("xsm.gui.panel.icon_size");
        int labelW = font.width(sliderLabel);
        int valW = font.width(sizeTxt);
        int sliderStart = PADDING + labelW + 5;
        int sliderEnd = PANEL_WIDTH - PADDING - valW - 5;
        int trackLen = sliderEnd - sliderStart - thumbW;
        int trackY = y + (thumbH - 4) / 2;

        g.text(font, sliderLabel, PADDING, y + (thumbH - font.lineHeight) / 2, 0xFFFFFFFF);
        g.fill(sliderStart, trackY, sliderEnd, trackY + 4, 0xFF444444);

        float t = (float) ((-1.75f + Math.sqrt(3.0225f + 0.8f * sliderValue)) / 0.4f);
        int thumbX = sliderStart + (int) (t * trackLen);
        boolean hoverThumb = mx >= thumbX && mx <= thumbX + thumbW
                && my >= y && my <= y + thumbH;
        g.fill(thumbX, y, thumbX + thumbW, y + thumbH,
                hoverThumb || sliderDragging ? 0xFFAAAAAA : 0xFF888888);

        g.text(font, sizeTxt, sliderEnd + 5, y + (thumbH - font.lineHeight) / 2, 0xFFFFFFFF);

        // loot preview checkbox
        y += thumbH + 5;
        boolean lootOn = ServerConfig.isLootPreviewEnabled();
        boolean hoverLoot = mx >= PADDING && mx <= PANEL_WIDTH - PADDING
                && my >= y && my <= y + ITEM_H;
        renderCheckbox(g, PADDING, y + (ITEM_H - 9) / 2, lootOn, hoverLoot);
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

    private void renderCheckbox(GuiGraphicsExtractor g, int x, int y, boolean checked, boolean hovered) {
        int border = hovered ? 0xFFFFFFFF : 0xFF888888;
        int fill = checked ? 0xFFFFFFFF : 0xFF222222;
        g.fill(x, y, x + 9, y + 1, border);
        g.fill(x, y + 8, x + 9, y + 9, border);
        g.fill(x, y, x + 1, y + 9, border);
        g.fill(x + 8, y, x + 9, y + 9, border);
        g.fill(x + 1, y + 1, x + 8, y + 8, fill);
    }

    private boolean hitCheckbox(int mx, int my, int headerY) {
        int cbX = PADDING;
        int cbY = headerY + (HEADER_H - 9) / 2;
        return mx >= cbX && mx <= cbX + 9 && my >= cbY && my <= cbY + 9;
    }

    private boolean hitHeader(int mx, int my, int headerY) {
        return my >= headerY && my < headerY + HEADER_H
                && mx >= PADDING && mx <= PANEL_WIDTH - PADDING;
    }

    // ─── MOUSE ──────────────────────────────────────────────

    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!panelOpen)
            return false;
        // any click on the panel ends a slider drag
        sliderDragging = false;

        int mx = (int) mouseX;
        int my = (int) mouseY;
        if (mx > PANEL_WIDTH)
            return false;

        // handle EditBox clicks directly (sync screen focus)
        if (biomeSearchField != null && biomeSearchField.isMouseOver(mx, my)) {
            screen.setFocused(biomeSearchField);
            return true;
        }
        if (structSearchField != null && structSearchField.isMouseOver(mx, my)) {
            screen.setFocused(structSearchField);
            return true;
        }
        if (groupEditField != null && editorGroup != null && groupEditField.isMouseOver(mx, my)) {
            screen.setFocused(groupEditField);
            return true;
        }

        // clicking panel → unfocus EditBox and clear screen focus
        screen.setFocused(null);

        // biome section header: checkbox toggles enable, rest of the header toggles expand
        int y = PADDING;
        if (hitCheckbox(mx, my, y)) {
            toggleBiome();
            return true;
        }
        if (hitHeader(mx, my, y)) {
            biomeExpanded = !biomeExpanded;
            return true;
        }
        y += HEADER_H;

        if (biomeExpanded) {
            y += PADDING;
            // color scheme button
            if (mx >= PADDING && mx <= PADDING + 20 && my >= y && my <= y + 20) {
                cycleScheme();
                return true;
            }
            y += 20 + PADDING;

            // biome list items
            int visible = Math.min(MAX_VISIBLE_ITEMS,
                    Math.max(MIN_VISIBLE_ITEMS, (scrH - y - 10) / ITEM_H));
            int size = filteredBiomes.size();
            int end = Math.min(biomeScrollOff + visible, size);
            for (int i = biomeScrollOff; i < end; i++) {
                int itemY = y + (i - biomeScrollOff) * ITEM_H;
                if (my >= itemY && my <= itemY + ITEM_H && mx >= PADDING && mx <= PANEL_WIDTH - PADDING) {
                    BiomeType b = filteredBiomes.get(i);
                    var wc = ServerConfig.getActiveWorldConfig();
                    if (wc != null) {
                        boolean disabled = wc.getDisabledBiomes().get(b.id);
                        wc.setBiomeDisabled(b.id, !disabled);
                        Xsm.setBiomeDisabled(wc.getDisabledBiomes());
                        CellCache.clear();
                    }
                    return true;
                }
            }

            y += visible * ITEM_H;
        }

        y += 5;

        // structure section header: checkbox toggles enable, rest of the header toggles expand
        int structHeaderY = y;
        if (hitCheckbox(mx, my, y)) {
            ServerConfig.setStructureEnabled(!ServerConfig.isStructureEnabled());
            return true;
        }
        if (hitHeader(mx, my, y)) {
            structureExpanded = !structureExpanded;
            return true;
        }

        if (!structureExpanded)
            return true;

        var L = structLayout(structHeaderY);
        int tab = hitTab(mx, my, L);
        if (tab >= 0 && tab != structTab) {
            structTab = tab;
            deleteArmed = false;
            colorSliderDrag = -1;
            if (tab == 1)
                groupCountCooldown = 0;
            return true;
        }

        if (structTab == 0) {
            // structure list items
            if (filteredStructures == null)
                updateStructFilter();
            int visible = L.typeVisible();
            int size = filteredStructures.size();
            int end = Math.min(structScrollOff + visible, size);
            for (int i = structScrollOff; i < end; i++) {
                int itemY = L.typeListY() + (i - structScrollOff) * ITEM_H;
                if (my >= itemY && my <= itemY + ITEM_H && mx >= PADDING && mx <= PANEL_WIDTH - PADDING) {
                    StructRow row = filteredStructures.get(i);
                    var wc = ServerConfig.getActiveWorldConfig();
                    if (wc != null) {
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
        } else if (structTab == 1) {
            return clickGroupsTab(mx, my, L);
        } else {
            return clickIconsTab(mx, my, L);
        }

        return true;
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
                if (mx >= PADDING && mx <= PADDING + 9) {
                    StructureDataConfig.setGroupHidden(group,
                            !StructureDataConfig.isGroupHidden(group));
                    return true;
                }
                if (!group.isEmpty() && mx >= PADDING + 12) {
                    openGroupEditor(group);
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
                    if (!deleteArmed) {
                        deleteArmed = true;
                    } else if (StructureDataConfig.removeGroup(group)) {
                        editorGroup = null;
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
                commitGroupRename(group);
                return true;
            }
        }
        deleteArmed = false;
        return true;
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
        int labelW = font.width(I18n.get("xsm.gui.panel.icon_size"));
        int valW = font.width(String.format("%.2f", sliderValue));
        int sliderStart = PADDING + labelW + 5;
        int sliderEnd = PANEL_WIDTH - PADDING - valW - 5;
        int trackLen = sliderEnd - sliderStart - thumbW;
        float t = (float) ((-1.75f + Math.sqrt(3.0225f + 0.8f * sliderValue)) / 0.4f);
        int thumbX = sliderStart + (int) (t * trackLen);

        if (mx >= thumbX && mx <= thumbX + thumbW && my >= y && my <= y + thumbH) {
            sliderDragging = true;
            updateSlider(mx);
            return true;
        }
        // also allow click on track
        if (mx >= sliderStart && mx <= sliderEnd && my >= y && my <= y + thumbH) {
            sliderDragging = true;
            updateSlider(mx);
            return true;
        }

        // loot preview checkbox
        y += thumbH + 5;
        if (mx >= PADDING && mx <= PADDING + 9 && my >= y && my <= y + ITEM_H) {
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
            var next = ServerConfig.getLootDisplayMode().ordinal() + 1;
            ServerConfig.setLootDisplayMode(LootDisplayMode.values()[next % LootDisplayMode.values().length]);
            LootPreviewState.close();
            return true;
        }
        return true;
    }

    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0 && sliderDragging) {
            sliderDragging = false;
            return true;
        }
        if (button == 0 && colorSliderDrag > 0) {
            colorSliderDrag = -1;
            StructureDataConfig.flush();
            return true;
        }
        return false;
    }

    public boolean mouseDragged(double mouseX, double mouseY, int button) {
        if (button == 0 && sliderDragging) {
            updateSlider((int) mouseX);
            return true;
        }
        return false;
    }

    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (!panelOpen || mouseX > PANEL_WIDTH || mouseY < 0 || mouseY > scrH)
            return false;

        int dir = (int) -scrollY;
        int my = (int) mouseY;
        int y = PADDING; // 5

        // ── Biome section ──
        y += HEADER_H; // past header

        if (biomeExpanded) {
            y += PADDING + 20 + PADDING; // past scheme button
            if (filteredBiomes == null)
                updateBiomeFilter();
            int biomeVisible = Math.min(MAX_VISIBLE_ITEMS,
                    Math.max(MIN_VISIBLE_ITEMS, (scrH - y - 10) / ITEM_H));
            int biomeListBottom = y + biomeVisible * ITEM_H;
            if (my >= y && my < biomeListBottom) {
                int size = filteredBiomes.size();
                int maxOff = Math.max(0, size - biomeVisible);
                biomeScrollOff = Math.max(0, Math.min(maxOff, biomeScrollOff + dir));
                return true;
            }
            y = biomeListBottom;
        }

        y += 5; // gap

        // ── Structure section ──
        int structHeaderY = y;
        y += HEADER_H; // past structure header

        if (structureExpanded) {
            var L = structLayout(structHeaderY);
            if (structTab == 0) {
                int typeTop = L.typeListY();
                int typeBottom = typeTop + L.typeVisible() * ITEM_H;
                if (my >= typeTop && my < typeBottom) {
                    if (filteredStructures == null)
                        updateStructFilter();
                    int size = filteredStructures.size();
                    int maxOff = Math.max(0, size - L.typeVisible());
                    structScrollOff = Math.max(0, Math.min(maxOff, structScrollOff + dir));
                }
            } else if (structTab == 1) {
                int top = L.groupListY();
                int bottom = top + L.groupVisibleRows() * ITEM_H;
                if (my >= top && my < bottom) {
                    int rowsUsed = groupRowsUsed(allGroupNames());
                    int maxOff = Math.max(0, rowsUsed - L.groupVisibleRows());
                    groupsScrollOff = Math.max(0, Math.min(maxOff, groupsScrollOff + dir));
                }
            }
        }

        return true;
    }

    private void updateSlider(int mx) {
        int labelW = font.width(I18n.get("xsm.gui.panel.icon_size"));
        int valW = font.width(String.format("%.2f", sliderValue));
        int sliderStart = PADDING + labelW + 5;
        int sliderEnd = PANEL_WIDTH - PADDING - valW - 5;
        int trackLen = sliderEnd - sliderStart - 8; // minus thumbW
        float t = (float) (mx - sliderStart) / trackLen;
        t = Math.max(0, Math.min(1, t));
        sliderValue = 0.05f + (1.75f + 0.2f * t) * t;
        ServerConfig.setStructureIconSize(sliderValue);
    }

    // ─── HELPERS ─────────────────────────────────────────────

    private boolean isBiomeEnabled() {
        var cfg = ServerConfig.getActiveConfig();
        return cfg == null || !cfg.isInvisibleBiomes();
    }

    private void toggleBiome() {
        var cfg = ServerConfig.getActiveConfig();
        if (cfg != null)
            cfg.setInvisibleBiomes(!cfg.isInvisibleBiomes());
    }

    private void cycleScheme() {
        var cfg = ServerConfig.getActiveConfig();
        if (cfg == null)
            return;
        var next = BiomeColorTable.nextProvider(cfg.getTheme());
        cfg.setTheme(next.name());
        Xsm.setBiomeColorTable(next);
        CellCache.clear();
    }

    private void updateBiomeFilter() {
        filteredBiomes = new ArrayList<>();
        String search = biomeSearchText.toLowerCase();
        for (BiomeType b : BiomeType.values()) {
            if (search.isEmpty()) {
                filteredBiomes.add(b);
                continue;
            }
            
            if (Integer.toString(b.id).contains(search)) {
                filteredBiomes.add(b);
                continue;
            }
            if (b.name.toLowerCase().contains(search)) {
                filteredBiomes.add(b);
                continue;
            }
            String bioKey = "biome.minecraft." + b.name;
            if (!b.name.contains(" ") && !b.name.contains("(") && !I18n.get(bioKey).equals(bioKey)
                    && I18n.get(bioKey).toLowerCase().contains(search)) {
                filteredBiomes.add(b);
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

    private void updateStructFilter() {
        filteredStructures = new ArrayList<>();
        String search = structSearchText.toLowerCase();
        var wc = ServerConfig.getActiveWorldConfig();
        StructureBitFlagView flags = wc != null ? wc.getDisabledStructures() : DEFAULT_FLAGS;
        for (StructureType s : StructureType.values()) {
            boolean typeMatch = search.isEmpty();
            if (!typeMatch) {
                if (Integer.toString(s.id).contains(search)) {
                    typeMatch = true;
                } else if (s.key.contains(search)) {
                    typeMatch = true;
                } else if (I18n.get(s.translationKey()).toLowerCase().contains(search)) {
                    typeMatch = true;
                }
            }
            boolean structVisible = !flags.isStructureSet(s.id);
            IntList variants = s.getVariants();
            if (typeMatch) {
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
}
