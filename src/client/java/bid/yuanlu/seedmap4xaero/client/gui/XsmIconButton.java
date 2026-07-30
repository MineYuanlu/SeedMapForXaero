package bid.yuanlu.seedmap4xaero.client.gui;

import java.util.function.Supplier;

import net.minecraft.network.chat.Component;
import xaero.lib.client.gui.widget.Tooltip;
import xaero.map.gui.TooltipButton;

/**
 * 透明背景图标按钮占位符。
 * 待用户提供正式图标后将替换为 GuiTexturedButton。
 */
public class XsmIconButton extends TooltipButton {

    public XsmIconButton(int x, int y, Runnable onPress, Supplier<Tooltip> tooltip) {
        super(x, y, 20, 20, Component.translatable("xsm.button.sm"), b -> onPress.run(), tooltip);
    }
}
