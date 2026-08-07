package bid.yuanlu.seedmap4xaero.client.mixin;

import java.util.HashSet;
import java.util.Set;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Surrogate;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import bid.yuanlu.seedmap4xaero.client.accessor.DropDownWidgetTitleAccessor;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

import xaero.lib.client.gui.widget.dropdown.DropDownWidget;

/**
 * 让右键菜单的"结构标题"也用 {@code selectedBackground} (标题背景色) 绘制,
 * 达到与 "Choose an Option" 一致的灰底分隔效果。
 * <p>
 * 原理: {@link DropDownWidget#drawSlot} 每行计算 {@code slotBackground} 后调用一次
 * {@code fill(IIIII)V}; 用 {@code @Inject HEAD} 捕获当行 {@code slotIndex}, 再对 fill 的
 * 颜色参数 (index 4) 做 {@code @ModifyArg}: 命中已注册标题行时强制换成
 * {@code selectedBackground}, 其余行原样保留。无需重写 XaeroLib 方法, 且允许任意多行灰底。
 * <p>
 * 注意: {@code selectedBackground} 是 protected 字段, {@code GuiRightClickMenu} 构造器
 * 已把它与 {@code selectedHoveredBackground} 一同设为标题背景色 (0xFF606060)。
 */
@Mixin(DropDownWidget.class)
public abstract class DropDownWidgetTitleMixin implements DropDownWidgetTitleAccessor {

    @Shadow
    protected int selectedBackground;

    @Unique
    private final Set<Integer> xsm$titleRows = new HashSet<>();

    /** {@link #xsm$renderTitleBackground} 需要的当前槽位索引, 由 drawSlot HEAD 填充。 */
    @Unique
    private int xsm$currentSlot;

    @Inject(method = "drawSlot", at = @At("HEAD"))
    private void xsm$captureSlot(GuiGraphicsExtractor guiGraphics, Component text, int slotIndex, int pos,
            int mouseX, int mouseY, boolean scrolling, int optionLimit,
            int xWithOffset, int yWithOffset, CallbackInfo ci) {
        xsm$currentSlot = slotIndex;
    }

    /**
     * 旧版 XaeroLib (如 1.1.x) 的 {@code drawSlot} 第二参数是 {@code String}；
     * 新版 (1.5.0+) 改为 {@code Component}。主 handler 用 Component（当前解析到的
     * 版本），此 @Surrogate 让老版目标签名也能匹配，单 jar 跨版本通用。
     */
    @Surrogate
    private void xsm$captureSlot(GuiGraphicsExtractor guiGraphics, String text, int slotIndex, int pos,
            int mouseX, int mouseY, boolean scrolling, int optionLimit,
            int xWithOffset, int yWithOffset, CallbackInfo ci) {
        xsm$currentSlot = slotIndex;
    }

    @ModifyArg(method = "drawSlot", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;fill(IIIII)V"), index = 4)
    private int xsm$renderTitleBackground(int color) {
        return xsm$titleRows.contains(xsm$currentSlot) ? selectedBackground : color;
    }

    @Override
    public void xsm$addTitleRow(int slotIndex) {
        xsm$titleRows.add(slotIndex);
    }
}
