package bid.yuanlu.seedmap4xaero.client.gui;

import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.Nullable;

import bid.yuanlu.seedmap4xaero.client.biome.BiomeType;
import bid.yuanlu.seedmap4xaero.client.cache.CellCache;
import bid.yuanlu.seedmap4xaero.client.compat.CompatGui;
import bid.yuanlu.seedmap4xaero.client.configs.basic.ServerConfig;
import bid.yuanlu.seedmap4xaero.client.nativeapi.Xsm;
import bid.yuanlu.seedmap4xaero.client.render.BiomeColorTable;
import bid.yuanlu.seedmap4xaero.utils.BitSetView;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.WidgetSprites;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import xaero.map.gui.GuiMap;

/**
 * 生物群系区: header (总开关+折叠) + 配色按钮/搜索行 + 批量动作行 + 群系列表。
 * <p>
 * 几何单一来源 = {@link BiomeLayout}（render / mouseClicked / mouseScrolled 共用,
 * 禁止回退到各处手推 y）。批量操作语义见 gui/AGENTS.md。
 */
class BiomeSection extends PanelSection {

    /** 配色按钮边长 (与搜索框同行的 C 钮)。 */
    private static final int SCHEME_BTN = 20;

    // 会话级 UI 状态: 窗口缩放 re-init 后保持
    private static boolean biomeExpanded;
    private static int biomeScrollOff;
    private static String biomeSearchText = "";

    /** 配色按钮 sprite (MC 风格三态)。 */
    private static final WidgetSprites SCHEME_SPRITES = new WidgetSprites(
            Identifier.withDefaultNamespace("widget/button"),
            Identifier.withDefaultNamespace("widget/button_disabled"),
            Identifier.withDefaultNamespace("widget/button_highlighted"));

    private EditBox biomeSearchField;
    private List<BiomeType> filteredBiomes;

    /** 生物群系区展开态布局 (单一来源: render / mouseClicked / mouseScrolled 共用)。 */
    private record BiomeLayout(int schemeY, int actionY, int listY, int visible, int listBottom) {
    }

    private BiomeLayout layout(int headerY) {
        int schemeY = headerY + HEADER_H + PADDING;
        int actionY = schemeY + SCHEME_BTN + PADDING;
        int listY = actionY + ITEM_H + PADDING;
        int visible = Math.min(MAX_VISIBLE_ITEMS,
                Math.max(MIN_VISIBLE_ITEMS, (scrH - listY - 10) / ITEM_H));
        return new BiomeLayout(schemeY, actionY, listY, visible, listY + visible * ITEM_H);
    }

    @Override
    void onInit(GuiMap screen, int scrW, int scrH) {
        super.onInit(screen, scrW, scrH);
        if (!SeedMapPanel.panelOpen)
            return;
        biomeSearchField = new EditBox(font, PADDING + 24, PADDING + HEADER_H + PADDING + SCHEME_BTN + PADDING,
                PANEL_WIDTH - PADDING - 28, 14, Component.translatable("xsm.gui.panel.search_biomes"));
        biomeSearchField.setValue(biomeSearchText);
        biomeSearchField.setResponder(s -> {
            biomeSearchText = s;
            updateBiomeFilter();
            biomeScrollOff = 0;
        });
        biomeSearchField.setCanLoseFocus(true);
        biomeSearchField.setVisible(biomeExpanded);
        screen.addButton(biomeSearchField);
    }

    @Override
    void onPanelOpened() {
        updateBiomeFilter();
    }

    @Override
    void onPanelClosed() {
        if (biomeSearchField != null)
            biomeSearchField.setFocused(false);
    }

    @Override
    @Nullable EditBox fieldAt(int mx, int my) {
        // 展开态才可命中 (修复: 原实现收起后残留旧坐标仍可聚焦到不可见输入框)
        if (biomeExpanded && biomeSearchField != null && biomeSearchField.isMouseOver(mx, my))
            return biomeSearchField;
        return null;
    }

    // ─── RENDER ──────────────────────────────────────────────

    @Override
    int renderContent(GuiGraphicsExtractor g, int mx, int my, int headerY) {
        boolean enabled = isBiomeEnabled();
        boolean hoverHdr = my >= headerY && my < headerY + HEADER_H
                && mx >= PADDING && mx <= PANEL_WIDTH - PADDING;
        if (hoverHdr)
            g.fill(PADDING, headerY, PANEL_WIDTH - PADDING, headerY + HEADER_H, 0x22_FFFFFF);

        boolean hoverCb = PanelUi.hitCheckbox(mx, my, headerY);
        PanelUi.renderCheckbox(g, PADDING, headerY + (HEADER_H - 9) / 2, enabled, hoverCb);

        String label = I18n.get("xsm.gui.panel.biomes_header", I18n.get(enabled ? "xsm.value.on" : "xsm.value.off"));
        g.text(font, label, PADDING + 12, headerY + (HEADER_H - font.lineHeight) / 2,
                enabled ? 0xFFFFFFFF : 0xFF888888);

        String arrow = biomeExpanded ? "▼" : "▶";
        int arrX = PANEL_WIDTH - PADDING - font.width(arrow);
        g.text(font, arrow, arrX, headerY + (HEADER_H - font.lineHeight) / 2, 0xFFFFFFFF);

        if (biomeSearchField != null)
            biomeSearchField.setVisible(biomeExpanded);

        if (!biomeExpanded)
            return headerY + HEADER_H;

        var L = layout(headerY);

        // color scheme MC-style button + search edit box on same row
        int schemeX = PADDING;
        int schemeY = L.schemeY();
        boolean hoverSc = mx >= schemeX && mx <= schemeX + SCHEME_BTN
                && my >= schemeY && my <= schemeY + SCHEME_BTN;
        CompatGui.blitSprite(g,
                SCHEME_SPRITES.get(true, hoverSc),
                schemeX, schemeY, SCHEME_BTN, SCHEME_BTN, 1.0f);
        g.text(font, "C", schemeX + 6, schemeY + 6, 0xFFFFFFFF);

        int searchX = schemeX + SCHEME_BTN + 5;
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
            if (tipY < 0)
                tipY = schemeY + SCHEME_BTN + 2;
            g.fill(tipX - 2, tipY - 2, tipX + tw + 4, tipY + font.lineHeight + 2, 0xCC000000);
            g.text(font, tip, tipX, tipY, 0xFFFFFFFF);
        }

        // 批量动作行 (全选/全不选/反选/只显搜索)
        var actionLayout = BatchActionRow.layout(font, L.actionY());
        BatchActionRow.render(g, font, actionLayout, batchEnabled(), mx, my);

        // biome list
        if (filteredBiomes == null)
            updateBiomeFilter();
        int size = filteredBiomes.size();
        if (biomeScrollOff > size - L.visible())
            biomeScrollOff = Math.max(0, size - L.visible());

        int end = Math.min(biomeScrollOff + L.visible(), size);
        var wc = ServerConfig.getActiveWorldConfig();
        var disabledBiomes = wc != null ? wc.getDisabledBiomes() : BitSetView.EMPTY;

        for (int i = biomeScrollOff; i < end; i++) {
            BiomeType b = filteredBiomes.get(i);
            int itemY = L.listY() + (i - biomeScrollOff) * ITEM_H;
            boolean bi = !disabledBiomes.get(b.id);
            boolean hover = mx >= PADDING && mx <= PANEL_WIDTH - PADDING
                    && my >= itemY && my <= itemY + ITEM_H;
            PanelUi.renderCheckbox(g, PADDING, itemY + (ITEM_H - 9) / 2, bi, hover);
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

        return L.listBottom();
    }

    /** 动作行各钮可用性: 无世界配置时整行置灰; 「只显搜索」还需搜索词非空。 */
    private boolean[] batchEnabled() {
        boolean hasWc = ServerConfig.getActiveWorldConfig() != null;
        boolean only = hasWc && !biomeSearchText.isEmpty();
        return new boolean[] { hasWc, hasWc, hasWc, only };
    }

    // ─── MOUSE ──────────────────────────────────────────────

    @Override
    boolean mouseClicked(int mx, int my, int button, int headerY) {
        // header: checkbox 切总开关, 其余切折叠
        if (PanelUi.hitCheckbox(mx, my, headerY)) {
            PanelUi.playClickSound();
            toggleBiome();
            return true;
        }
        if (PanelUi.hitHeader(mx, my, headerY)) {
            PanelUi.playClickSound();
            biomeExpanded = !biomeExpanded;
            return true;
        }
        if (!biomeExpanded)
            return false;

        var L = layout(headerY);

        // color scheme button
        if (mx >= PADDING && mx <= PADDING + SCHEME_BTN && my >= L.schemeY() && my <= L.schemeY() + SCHEME_BTN) {
            PanelUi.playClickSound();
            cycleScheme();
            return true;
        }

        // 批量动作行 (区域内点击一律消费; 置灰钮忽略)
        var actionLayout = BatchActionRow.layout(font, L.actionY());
        int action = BatchActionRow.hit(actionLayout, mx, my);
        if (action >= 0) {
            if (batchEnabled()[action]) {
                PanelUi.playClickSound();
                applyBatch(action);
            }
            return true;
        }

        // biome list items
        int size = filteredBiomes != null ? filteredBiomes.size() : 0;
        int end = Math.min(biomeScrollOff + L.visible(), size);
        for (int i = biomeScrollOff; i < end; i++) {
            int itemY = L.listY() + (i - biomeScrollOff) * ITEM_H;
            if (my >= itemY && my <= itemY + ITEM_H && mx >= PADDING && mx <= PANEL_WIDTH - PADDING) {
                BiomeType b = filteredBiomes.get(i);
                var wc = ServerConfig.getActiveWorldConfig();
                if (wc != null) {
                    PanelUi.playClickSound();
                    boolean disabled = wc.getDisabledBiomes().get(b.id);
                    wc.setBiomeDisabled(b.id, !disabled);
                    Xsm.setBiomeDisabled(wc.getDisabledBiomes());
                    CellCache.clear();
                }
                return true;
            }
        }
        return false;
    }

    @Override
    boolean mouseScrolled(int mx, int my, double dir, int headerY) {
        if (!biomeExpanded)
            return false;
        var L = layout(headerY);
        if (my < L.listY() || my >= L.listBottom())
            return false;
        if (filteredBiomes == null)
            updateBiomeFilter();
        int maxOff = Math.max(0, filteredBiomes.size() - L.visible());
        biomeScrollOff = Math.max(0, Math.min(maxOff, biomeScrollOff + (int) dir));
        return true;
    }

    // ─── 状态与批量操作 ──────────────────────────────────────

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

    /** 批量操作 (语义见 gui/AGENTS.md): 改 WorldConfig 后同步 native + 清瓦片缓存各一次。 */
    private void applyBatch(int action) {
        var wc = ServerConfig.getActiveWorldConfig();
        if (wc == null)
            return;
        switch (action) {
            case BatchActionRow.ALL -> wc.enableAllBiomes();
            case BatchActionRow.NONE -> wc.disableAllBiomes();
            case BatchActionRow.INVERT -> wc.invertBiomes();
            case BatchActionRow.SHOW_ONLY -> {
                if (biomeSearchText.isEmpty())
                    return;
                if (filteredBiomes == null)
                    updateBiomeFilter();
                var ids = new IntArrayList(filteredBiomes.size());
                for (BiomeType b : filteredBiomes)
                    ids.add(b.id);
                wc.enableOnlyBiomes(ids);
            }
            default -> {
                return;
            }
        }
        Xsm.setBiomeDisabled(wc.getDisabledBiomes());
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

    // ─── E2E gametest 钩子 (生产路径不调用) ─────────────────────

    /** 展开/收起生物群系区 (面板截图前置状态; 展开帧走 CompatGui.blitSprite, issue #32 回归路径)。 */
    void setExpandedForTest(boolean expanded) {
        biomeExpanded = expanded;
        biomeScrollOff = 0;
        if (expanded)
            updateBiomeFilter();
    }

    /** 设置搜索文本并刷新过滤 (模拟 EditBox responder)。 */
    void setSearchForTest(String text) {
        biomeSearchText = text == null ? "" : text;
        biomeScrollOff = 0;
        updateBiomeFilter();
    }

    /**
     * 执行批量操作 (all/none/invert/only); 返回已知群系的启用数 (断言用)。
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
            var disabled = wc.getDisabledBiomes();
            for (BiomeType b : BiomeType.values())
                if (!disabled.get(b.id))
                    enabled++;
        }
        return enabled;
    }
}
