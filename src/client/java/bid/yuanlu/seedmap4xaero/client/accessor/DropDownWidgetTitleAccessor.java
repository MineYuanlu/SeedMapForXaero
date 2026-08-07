package bid.yuanlu.seedmap4xaero.client.accessor;

import xaero.lib.client.gui.widget.dropdown.DropDownWidget;

/**
 * 跨版本注册右键菜单的"标题行" (XaeroLib 私有逻辑, 无公开 API)。
 * <p>
 * {@link DropDownWidget#drawSlot} 里只有 {@code selected} 行会用
 * {@code selectedBackground} (即菜单标题背景色) 绘制, 而 {@code selected} 只有一个。
 * 通过 {@link bid.yuanlu.seedmap4xaero.client.mixin.DropDownWidgetTitleMixin}
 * 用 {@code @ModifyArg} 把已注册标题行的 fill 颜色强制换成 {@code selectedBackground},
 * 实现"两行标题都灰"而无需改动 XaeroLib 的 {@code drawSlot} 逻辑。
 */
public interface DropDownWidgetTitleAccessor {

    void xsm$addTitleRow(int slotIndex);
}
