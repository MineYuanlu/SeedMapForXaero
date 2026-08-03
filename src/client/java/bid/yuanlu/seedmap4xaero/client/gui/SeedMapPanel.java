package bid.yuanlu.seedmap4xaero.client.gui;

import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.Nullable;

import bid.yuanlu.seedmap4xaero.client.biome.BiomeType;
import bid.yuanlu.seedmap4xaero.client.cache.CellCache;
import bid.yuanlu.seedmap4xaero.client.configs.ServerConfig;
import bid.yuanlu.seedmap4xaero.client.configs.WorldConfig;
import bid.yuanlu.seedmap4xaero.client.nativeapi.Xsm;
import bid.yuanlu.seedmap4xaero.client.render.BiomeColorTable;
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

    // slider
    private float sliderValue = 1.0f;
    public boolean sliderDragging;

    // search edit boxes
    private EditBox biomeSearchField;
    private EditBox structSearchField;

    // screen dimensions
    private int scrW, scrH;

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

        y += HEADER_H;

        if (structSearchField != null)
            structSearchField.setVisible(structureExpanded);

        if (!structureExpanded)
            return y;

        y += PADDING;

        // search field
        if (structSearchField != null) {
            structSearchField.setY(y);
            structSearchField.extractRenderState(g, mx, my, 0);
            y += 16;
        }

        // structure list
        if (filteredStructures == null)
            updateStructFilter();
        int visible = Math.min(MAX_VISIBLE_ITEMS,
                Math.max(MIN_VISIBLE_ITEMS, (scrH - y - 30) / ITEM_H));
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

        y += visible * ITEM_H;

        // slider
        y += 5;
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

        return y + thumbH;
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
        if (hitCheckbox(mx, my, y)) {
            ServerConfig.setStructureEnabled(!ServerConfig.isStructureEnabled());
            return true;
        }
        if (hitHeader(mx, my, y)) {
            structureExpanded = !structureExpanded;
            return true;
        }
        y += HEADER_H;

        if (structureExpanded) {
            y += PADDING;
            y += 16; // search field height

            // structure list items
            int visible = Math.min(MAX_VISIBLE_ITEMS,
                    Math.max(MIN_VISIBLE_ITEMS, (scrH - y - 30) / ITEM_H));
            int size = filteredStructures.size();
            int end = Math.min(structScrollOff + visible, size);
            for (int i = structScrollOff; i < end; i++) {
                int itemY = y + (i - structScrollOff) * ITEM_H;
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

            y += visible * ITEM_H + 5;

            // slider
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
        }

        return true;
    }

    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0 && sliderDragging) {
            sliderDragging = false;
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
        y += HEADER_H; // past structure header

        if (structureExpanded) {
            y += PADDING + 16; // past search field
            if (filteredStructures == null)
                updateStructFilter();
            int structVisible = Math.min(MAX_VISIBLE_ITEMS,
                    Math.max(MIN_VISIBLE_ITEMS, (scrH - y - 30) / ITEM_H));
            int structListBottom = y + structVisible * ITEM_H;
            if (my >= y && my < structListBottom) {
                int size = filteredStructures.size();
                int maxOff = Math.max(0, size - structVisible);
                structScrollOff = Math.max(0, Math.min(maxOff, structScrollOff + dir));
                return true;
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
