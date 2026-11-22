package bid.yuanlu.seedmap4xaero.client.gui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.resources.language.I18n;

/**
 * 批量动作行: [全选] [全不选] [反选] [只显搜索]。
 * 生物群系区与结构区类型 tab 共用; 「只显搜索」在搜索框为空时置灰。
 * 布局为 render / click 共用的单一来源 (每次按当前 i18n 文案现算, 两侧确定一致)。
 */
final class BatchActionRow {

    static final int COUNT = 4;
    private static final int GAP = 3;
    private static final String[] KEYS = {
            "xsm.gui.panel.batch_all", "xsm.gui.panel.batch_none",
            "xsm.gui.panel.batch_invert", "xsm.gui.panel.batch_show_only" };

    /** 动作索引。 */
    static final int ALL = 0;
    static final int NONE = 1;
    static final int INVERT = 2;
    static final int SHOW_ONLY = 3;

    private BatchActionRow() {
    }

    /** 按钮几何 (x 起点数组 + 宽数组), 行高 {@link PanelSection#ITEM_H}。 */
    record Layout(int y, int[] xs, int[] ws) {
    }

    static Layout layout(Font font, int y) {
        int[] xs = new int[COUNT];
        int[] ws = new int[COUNT];
        int x = PanelSection.PADDING;
        for (int i = 0; i < COUNT; i++) {
            ws[i] = font.width(I18n.get(KEYS[i])) + 8;
            xs[i] = x;
            x += ws[i] + GAP;
        }
        return new Layout(y, xs, ws);
    }

    /** 命中索引; -1 = 未命中 (不区分置灰, 调用方自行判断)。 */
    static int hit(Layout L, int mx, int my) {
        if (my < L.y() || my >= L.y() + PanelSection.ITEM_H)
            return -1;
        for (int i = 0; i < COUNT; i++)
            if (mx >= L.xs()[i] && mx <= L.xs()[i] + L.ws()[i])
                return i;
        return -1;
    }

    /** 渲染一行; enabled 按索引, 置灰钮不高亮不响应 hover。 */
    static void render(GuiGraphicsExtractor g, Font font, Layout L, boolean[] enabled, int mx, int my) {
        for (int i = 0; i < COUNT; i++) {
            boolean en = enabled[i];
            boolean hov = en && my >= L.y() && my < L.y() + PanelSection.ITEM_H
                    && mx >= L.xs()[i] && mx <= L.xs()[i] + L.ws()[i];
            PanelUi.renderTextButton(g, font, I18n.get(KEYS[i]), L.xs()[i], L.y(), en, hov);
        }
    }
}
