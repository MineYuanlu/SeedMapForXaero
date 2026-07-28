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
import bid.yuanlu.seedmap4xaero.client.structure.StructureType;
import bid.yuanlu.seedmap4xaero.utils.BitSetView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.WidgetSprites;
import net.minecraft.client.renderer.RenderPipelines;
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

    public boolean panelOpen;

    // section expand state
    private boolean biomeExpanded;
    private boolean structureExpanded;

    // scroll
    private int biomeScrollOff;
    private int structScrollOff;

    // search state (keys only, no EditBox widget for now)
    private String biomeSearchText = "";
    private String structSearchText = "";

    // filtered lists
    private List<BiomeType> filteredBiomes;
    private List<StructureType> filteredStructures;

    // slider
    private float sliderValue = 1.0f;
    public boolean sliderDragging;

    // search edit boxes
    private EditBox biomeSearchField;
    private EditBox structSearchField;

    // screen dimensions
    private int scrW, scrH;

    // last frame mouse for hover rendering
    private int lastMx, lastMy;

    public SeedMapPanel(GuiMap screen) {
        this.screen = screen;
        this.mc = Minecraft.getInstance();
        this.font = mc.font;
    }

    public void toggleOpen() {
        panelOpen = !panelOpen;
        if (panelOpen) {
            biomeScrollOff = 0;
            structScrollOff = 0;
            biomeExpanded = false;
            structureExpanded = false;
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
            String prevBiome = biomeSearchField != null ? biomeSearchField.getValue() : "";
            String prevStruct = structSearchField != null ? structSearchField.getValue() : "";

            int searchY = PADDING + HEADER_H + PADDING + 20 + PADDING;
            biomeSearchField = new EditBox(font, PADDING + 24, searchY, PANEL_WIDTH - PADDING - 28, 14,
                    Component.literal("过滤生物群系"));
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
                    Component.literal("过滤结构"));
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

        lastMx = mouseX;
        lastMy = mouseY;

        // slider drag update
        if (sliderDragging) {
            if (mc.mouseHandler.isLeftPressed()) {
                updateSlider(lastMx);
            } else {
                sliderDragging = false;
            }
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

        String label = "生物群系: " + (enabled ? "ON" : "OFF");
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
            String tip = "颜色表: " + (provider != null ? provider.name() : "?");
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
            g.text(font, b.name, PADDING + 24, itemY + (ITEM_H - font.lineHeight) / 2,
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

        String label = "结构: " + (enabled ? "ON" : "OFF");
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
        BitSetView enabledStructs = wc != null ? wc.getEnabledStructures() : StructureType.defaultEnabled();

        for (int i = structScrollOff; i < end; i++) {
            StructureType s = filteredStructures.get(i);
            int itemY = y + (i - structScrollOff) * ITEM_H;
            boolean si = enabledStructs.get(s.id);
            boolean hover = mx >= PADDING && mx <= PANEL_WIDTH - PADDING
                    && my >= itemY && my <= itemY + ITEM_H;
            renderCheckbox(g, PADDING, itemY + (ITEM_H - 9) / 2, si, hover);

            float u0 = (s.spriteIndex * 16f) / 400f;
            float u1 = u0 + 16f / 400f;
            g.blit(StructureType.STRUCTURES_PLAIN_TEXTURE,
                    PADDING + 12, itemY + 1,
                    PADDING + 22, itemY + 11,
                    u0, u1, 0.0F, 1.0F);

            g.text(font, s.key, PADDING + 24, itemY + (ITEM_H - font.lineHeight) / 2,
                    si ? 0xFFFFFFFF : 0xFF888888);
        }

        y += visible * ITEM_H;

        // slider
        y += 5;
        int sliderX = PADDING;
        int sliderW = PANEL_WIDTH - 2 * PADDING;
        int thumbW = 8;
        int thumbH = 12;
        int trackY = y + (thumbH - 4) / 2;

        g.fill(sliderX, trackY, sliderX + sliderW, trackY + 4, 0xFF444444);

        float t = (sliderValue - 0.5f) / 1.5f;
        int thumbX = sliderX + (int) (t * (sliderW - thumbW));
        boolean hoverThumb = mx >= thumbX && mx <= thumbX + thumbW
                && my >= y && my <= y + thumbH;
        g.fill(thumbX, y, thumbX + thumbW, y + thumbH,
                hoverThumb || sliderDragging ? 0xFFAAAAAA : 0xFF888888);

        String sizeTxt = String.format("%.1f", sliderValue);
        g.text(font, sizeTxt, sliderX + sliderW + 3, y + (thumbH - font.lineHeight) / 2, 0xFFFFFFFF);

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

    private boolean hitArrow(int mx, int my, int headerY, boolean expanded) {
        String arrow = expanded ? "▼" : "▶";
        int arrX = PANEL_WIDTH - PADDING - font.width(arrow);
        int arrY = headerY + (HEADER_H - font.lineHeight) / 2;
        return mx >= arrX && mx <= arrX + font.width(arrow)
                && my >= arrY && my <= arrY + font.lineHeight;
    }

    // ─── MOUSE ──────────────────────────────────────────────

    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!panelOpen)
            return false;
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

        // biome section header
        int y = PADDING;
        if (hitCheckbox(mx, my, y)) {
            toggleBiome();
            return true;
        }
        if (hitArrow(mx, my, y, biomeExpanded)) {
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

        // structure section header
        if (hitCheckbox(mx, my, y)) {
            ServerConfig.setStructureEnabled(!ServerConfig.isStructureEnabled());
            return true;
        }
        if (hitArrow(mx, my, y, structureExpanded)) {
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
                    StructureType s = filteredStructures.get(i);
                    var wc = ServerConfig.getActiveWorldConfig();
                    if (wc != null) {
                        boolean cur = wc.getEnabledStructures().get(s.id);
                        wc.setStructureEnabled(s.id, !cur);
                    }
                    return true;
                }
            }

            y += visible * ITEM_H + 5;

            // slider
            int sliderX = PADDING;
            int sliderW = PANEL_WIDTH - 2 * PADDING;
            int thumbW = 8;
            int thumbH = 12;
            float t = (sliderValue - 0.5f) / 1.5f;
            int thumbX = sliderX + (int) (t * (sliderW - thumbW));

            if (mx >= thumbX && mx <= thumbX + thumbW && my >= y && my <= y + thumbH) {
                sliderDragging = true;
                updateSlider(mx);
                return true;
            }
            // also allow click on track
            if (mx >= sliderX && mx <= sliderX + sliderW && my >= y && my <= y + thumbH) {
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

        // determine which section to scroll based on mouse position
        int y = PADDING + HEADER_H; // bottom of biome header

        if (biomeExpanded) {
            y += PADDING + 20 + PADDING; // scheme button + padding
            if (mouseY < y) {
                return true; // above list, consume but no scroll
            }
            // biome list area
            biomeScrollOff = Math.max(0,
                    Math.min(filteredBiomes.size() - MIN_VISIBLE_ITEMS, biomeScrollOff + dir));
            return true;
        }

        y += 5 + HEADER_H; // gap + structure header

        if (structureExpanded) {
            if (mouseY < y + PADDING) {
                return true; // above list
            }
            structScrollOff = Math.max(0,
                    Math.min(filteredStructures.size() - MIN_VISIBLE_ITEMS, structScrollOff + dir));
            return true;
        }

        return true;
    }

    private void updateSlider(int mx) {
        int sliderX = PADDING;
        int sliderW = PANEL_WIDTH - 2 * PADDING - 8;
        float t = (float) (mx - sliderX) / sliderW;
        t = Math.max(0, Math.min(1, t));
        sliderValue = 0.5f + t * 1.5f;
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
            if (search.isEmpty() || b.name.contains(search)) {
                filteredBiomes.add(b);
            }
        }
    }

    private void updateStructFilter() {
        filteredStructures = new ArrayList<>();
        String search = structSearchText.toLowerCase();
        for (StructureType s : StructureType.values()) {
            if (search.isEmpty() || s.key.contains(search)) {
                filteredStructures.add(s);
            }
        }
    }
}
