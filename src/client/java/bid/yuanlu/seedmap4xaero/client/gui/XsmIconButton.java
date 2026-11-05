package bid.yuanlu.seedmap4xaero.client.gui;

import java.util.function.Supplier;

import net.minecraft.resources.Identifier;
import xaero.lib.client.gui.widget.Tooltip;
import xaero.map.gui.GuiTexturedButton;

/**
 * 侧栏图标按钮：16×16 自绘贴图（白 SM + 彩色种子 + 单级阴影），
 * 与 Xaero 侧栏按钮（GuiTexturedButton）同款染色/hover 行为。
 */
public class XsmIconButton extends GuiTexturedButton {

    private static final Identifier ICON = Identifier.fromNamespaceAndPath(
            "seed-map-for-xaero", "textures/gui/sm_button.png");

    public XsmIconButton(int x, int y, Runnable onPress, Supplier<Tooltip> tooltip) {
        super(x, y, 20, 20, 0, 0, 16, 16, ICON, b -> onPress.run(), tooltip, 16, 16);
    }
}
