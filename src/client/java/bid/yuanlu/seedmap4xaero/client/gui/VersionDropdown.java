package bid.yuanlu.seedmap4xaero.client.gui;

import java.util.Objects;

import org.jetbrains.annotations.Nullable;

import bid.yuanlu.seedmap4xaero.client.configs.ServerConfig;
import bid.yuanlu.seedmap4xaero.client.nativeapi.Xsm;
import net.minecraft.SharedConstants;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.resources.language.I18n;
import xaero.map.MapProcessor;

/**
 * 世界切换面板上的 MC 版本下拉选择控件（纯自绘）。
 * <p>
 * 索引 0 表示"跟随客户端"(null)，其余为 {@link Xsm#SUPPORTED_VERSIONS}。
 * 静态 {@link #active} 由 GuiMapSwitchingMixin 在面板 init 时设置，
 * 供 GuiMap 的鼠标事件注入路由（展开列表不是普通 widget，需要拦截点击/滚轮）。
 */
public final class VersionDropdown {

    private static final int ROW_H = 14;
    private static final int MAX_VISIBLE_ROWS = 8;
    private static final int LIST_PAD = 2;

    private static final int COL_BORDER = 0xFF555555;
    private static final int COL_BG = 0xEE2B2B2B;
    private static final int COL_BG_HOVER = 0xEE3D3D3D;
    private static final int COL_LIST_BG = 0xF00E0E0E;
    private static final int COL_ROW_HOVER = 0x30FFFFFF;
    private static final int COL_TEXT = 0xFFFFFFFF;
    private static final int COL_TEXT_SELECTED = 0xFF55FF55;

    /** 当前挂载的实例；null = 不在切换面板或单机模式。 */
    private static volatile VersionDropdown active;

    private final int x;
    private final int y;
    private final int width;
    private final int height = 20;
    private final @Nullable MapProcessor mapProcessor;

    private boolean expanded;
    private int scroll;

    public VersionDropdown(int x, int y, int width, @Nullable MapProcessor mapProcessor) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.mapProcessor = mapProcessor;
        active = this;
    }

    /** 面板重新 init 或离开时卸载，防止事件路由到失效实例。 */
    public static void unsetActive() {
        active = null;
    }

    public static @Nullable VersionDropdown active() {
        return active;
    }

    /** 当前选中的版本字符串，null = 跟随客户端。 */
    public @Nullable String currentSelection() {
        var wc = ServerConfig.getActiveWorldConfig();
        return wc != null ? wc.mcVersion() : null;
    }

    private int entryCount() {
        return Xsm.SUPPORTED_VERSIONS.size() + 1;
    }

    private int visibleRows() {
        return Math.min(entryCount(), MAX_VISIBLE_ROWS);
    }

    private int listBottom() {
        return y + height + visibleRows() * ROW_H + LIST_PAD * 2;
    }

    private int maxScroll() {
        return Math.max(0, entryCount() - visibleRows());
    }

    private boolean overButton(double mx, double my) {
        return mx >= x && mx < x + width && my >= y && my < y + height;
    }

    private boolean overList(double mx, double my) {
        return expanded && mx >= x && mx < x + width && my >= y + height && my < listBottom();
    }

    public void render(GuiGraphicsExtractor g, Font font, int mouseX, int mouseY) {
        // 收起态按钮本体
        g.fill(x, y, x + width, y + height, overButton(mouseX, mouseY) ? COL_BG_HOVER : COL_BG);
        g.fill(x, y, x + width, y + 1, COL_BORDER);
        g.fill(x, y + height - 1, x + width, y + height, COL_BORDER);
        g.fill(x, y, x + 1, y + height, COL_BORDER);
        g.fill(x + width - 1, y, x + width, y + height, COL_BORDER);

        String value = currentSelection() != null
                ? currentSelection()
                : I18n.get("xsm.gui.switching.version.auto", SharedConstants.getCurrentVersion().name());
        String text = I18n.get("xsm.gui.switching.version") + ": " + value;
        int ty = y + (height - font.lineHeight) / 2;
        g.text(font, text, x + 5, ty, COL_TEXT);
        String arrow = expanded ? "\u25B2" : "\u25BC";
        g.text(font, arrow, x + width - 16, ty, COL_TEXT);

        if (!expanded)
            return;

        // 展开列表（绘制在按钮下方，覆盖后续内容）
        g.fill(x, y + height, x + width, listBottom(), COL_LIST_BG);
        g.fill(x, y + height, x + width, y + height + 1, COL_BORDER);
        g.fill(x, listBottom() - 1, x + width, listBottom(), COL_BORDER);
        g.fill(x, y + height, x + 1, listBottom(), COL_BORDER);
        g.fill(x + width - 1, y + height, x + width, listBottom(), COL_BORDER);

        String selected = currentSelection();
        for (int row = 0; row < visibleRows(); row++) {
            int entry = scroll + row;
            if (entry >= entryCount())
                break;
            int ry = y + height + LIST_PAD + row * ROW_H;
            boolean hovered = mouseX >= x && mouseX < x + width && mouseY >= ry && mouseY < ry + ROW_H;
            if (hovered)
                g.fill(x + 1, ry, x + width - 1, ry + ROW_H, COL_ROW_HOVER);
            String label = entry == 0
                    ? I18n.get("xsm.gui.switching.version.auto", SharedConstants.getCurrentVersion().name())
                    : Xsm.SUPPORTED_VERSIONS.get(entry - 1);
            int color = Objects.equals(entry == 0 ? null : label, selected) ? COL_TEXT_SELECTED : COL_TEXT;
            g.text(font, label, x + 6, ry + (ROW_H - font.lineHeight) / 2, color);
        }
    }

    /** @return true 表示已消费该点击。 */
    public boolean mouseClicked(MouseButtonEvent event) {
        double mx = event.x();
        double my = event.y();
        if (expanded) {
            if (overList(mx, my) && event.button() == 0) {
                int row = (int) ((my - y - height - LIST_PAD) / ROW_H);
                int entry = scroll + row;
                if (entry >= 0 && entry < entryCount())
                    select(entry == 0 ? null : Xsm.SUPPORTED_VERSIONS.get(entry - 1));
                expanded = false;
                return true;
            }
            expanded = false;
            if (overButton(mx, my) && event.button() == 0) {
                return true; // 再点按钮 = 收起
            }
            return true; // 展开时吞掉所有点击以关闭/避免误触下层
        }
        if (overButton(mx, my) && event.button() == 0) {
            expanded = true;
            // 让当前选中项尽量可见
            String sel = currentSelection();
            int idx = sel == null ? 0 : Xsm.SUPPORTED_VERSIONS.indexOf(sel) + 1;
            scroll = idx > 0 ? Math.min(Math.max(0, idx - visibleRows() / 2), maxScroll()) : 0;
            return true;
        }
        return false;
    }

    /** @return true 表示已消费该滚动。 */
    public boolean mouseScrolled(double mx, double my, double deltaY) {
        if (!overList(mx, my) || deltaY == 0)
            return false;
        int step = Math.abs(deltaY) >= 1 ? (int) Math.signum(deltaY) : (deltaY > 0 ? 1 : -1);
        scroll = Math.max(0, Math.min(maxScroll(), scroll - step));
        return true;
    }

    private void select(@Nullable String version) {
        var cfg = ServerConfig.getActiveConfig();
        if (cfg == null || mapProcessor == null)
            return;
        cfg.getOrCreateWorld(mapProcessor.getCurrentMWId()).mcVersion(version);
        ServerConfig.save();
    }
}
