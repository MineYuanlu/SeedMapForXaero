package bid.yuanlu.seedmap4xaero.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;

/**
 * 面板共享手绘件 (全区块复用): 复选框 / 区头命中 / 文字按钮 / 截断 / 点击音。
 * 面板交互是手工路由的 (绕开 AbstractWidget.mouseClicked), 状态变更交互需补 vanilla 点击音。
 */
final class PanelUi {

    private PanelUi() {
    }

    /** 手绘 9×9 复选框。 */
    static void renderCheckbox(GuiGraphicsExtractor g, int x, int y, boolean checked, boolean hovered) {
        int border = hovered ? 0xFFFFFFFF : 0xFF888888;
        int fill = checked ? 0xFFFFFFFF : 0xFF222222;
        g.fill(x, y, x + 9, y + 1, border);
        g.fill(x, y + 8, x + 9, y + 9, border);
        g.fill(x, y, x + 1, y + 9, border);
        g.fill(x + 8, y, x + 9, y + 9, border);
        g.fill(x + 1, y + 1, x + 8, y + 8, fill);
    }

    /** 区块 header 内复选框命中 (PanelSection.PADDING 列)。 */
    static boolean hitCheckbox(int mx, int my, int headerY) {
        int cbX = PanelSection.PADDING;
        int cbY = headerY + (PanelSection.HEADER_H - 9) / 2;
        return mx >= cbX && mx <= cbX + 9 && my >= cbY && my <= cbY + 9;
    }

    /** 区块 header 命中 (整行)。 */
    static boolean hitHeader(int mx, int my, int headerY) {
        return my >= headerY && my < headerY + PanelSection.HEADER_H
                && mx >= PanelSection.PADDING && mx <= PanelSection.PANEL_WIDTH - PanelSection.PADDING;
    }

    /** 文字按钮定宽: 文字宽 + 8。 */
    static int textButtonWidth(Font font, String label) {
        return font.width(label) + 8;
    }

    /**
     * 文字按钮 (样式照组编辑器按钮): 定宽填充底, hover 变色, 禁用置灰。
     * 高度 {@link PanelSection#ITEM_H}。
     */
    static void renderTextButton(GuiGraphicsExtractor g, Font font, String label,
            int x, int y, boolean enabled, boolean hovered) {
        int w = textButtonWidth(font, label);
        g.fill(x, y, x + w, y + PanelSection.ITEM_H,
                !enabled ? 0xFF262626 : hovered ? 0xFF666666 : 0xFF333333);
        g.text(font, label, x + 4, y + (PanelSection.ITEM_H - font.lineHeight) / 2,
                enabled ? 0xFFFFFFFF : 0xFF888888);
    }

    /** 超宽截断加省略号。 */
    static String truncate(Font font, String s, int maxW) {
        if (font.width(s) <= maxW)
            return s;
        while (!s.isEmpty() && font.width(s + "…") > maxW)
            s = s.substring(0, s.length() - 1);
        return s + "…";
    }

    /** 手工路由的状态变更交互补 vanilla 按钮点击音。 */
    static void playClickSound() {
        AbstractWidget.playButtonClickSound(Minecraft.getInstance().getSoundManager());
    }
}
