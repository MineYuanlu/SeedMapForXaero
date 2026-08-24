package bid.yuanlu.seedmap4xaero.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import bid.yuanlu.seedmap4xaero.client.gui.VersionDropdown;
import net.minecraft.client.input.MouseButtonEvent;
import xaero.map.gui.GuiMap;

/**
 * 世界切换面板版本下拉框的鼠标事件路由。
 * <p>
 * 展开列表不是普通 widget（需覆盖渲染在其它控件之上），点击/滚轮在
 * {@link GuiMap#mouseClicked}/{@link GuiMap#mouseScrolled} HEAD 处拦截：
 * 下拉框消费则短路后续处理。{@code VersionDropdown.active} 仅在世界切换
 * 面板 init 且多人模式时非空，其余场景直接放行。
 */
@Mixin(GuiMap.class)
public class GuiMapVersionDropdownMixin {

    @Inject(method = "mouseClicked", at = @At("HEAD"))
    private void xsm$onMouseClicked(MouseButtonEvent event, boolean doubleClick,
            CallbackInfoReturnable<Boolean> cir) {
        var dd = VersionDropdown.active();
        if (dd != null && dd.mouseClicked(event))
            cir.setReturnValue(true);
    }

    @Inject(method = "mouseScrolled", at = @At("HEAD"))
    private void xsm$onMouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY,
            CallbackInfoReturnable<Boolean> cir) {
        var dd = VersionDropdown.active();
        if (dd != null && dd.mouseScrolled(mouseX, mouseY, scrollY))
            cir.setReturnValue(true);
    }
}
