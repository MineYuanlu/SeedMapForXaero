package bid.yuanlu.seedmap4xaero.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import bid.yuanlu.seedmap4xaero.client.gui.SeedMapPanel;
import bid.yuanlu.seedmap4xaero.client.gui.XsmIconButton;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import xaero.lib.client.gui.widget.Tooltip;
import xaero.map.gui.GuiMap;
import xaero.map.misc.Misc;
import xaero.map.mods.SupportMods;

@Mixin(GuiMap.class)
public abstract class XsmMainPanelMixin {

    @Unique
    private SeedMapPanel xsm$panel;

    @Unique
    private XsmIconButton xsm$panelBtn;

    @Unique
    private int xsm$prevW, xsm$prevH;

    @Unique
    private boolean xsm$settleChecked;

    @Unique
    private int xsm$topRightBtnY = Integer.MAX_VALUE;

    @Inject(method = "init", at = @At("HEAD"))
    private void xsm$onInitHead(CallbackInfo ci) {
        var window = Minecraft.getInstance().getWindow();
        xsm$prevW = window.getGuiScaledWidth();
        xsm$prevH = window.getGuiScaledHeight();
        xsm$topRightBtnY = Integer.MAX_VALUE;
        xsm$settleChecked = false;
    }

    @Inject(method = "addRenderableWidget", at = @At("TAIL"))
    private void xsm$onAddRenderableWidget(GuiEventListener widget,
            CallbackInfoReturnable<GuiEventListener> cir) {
        if (widget == xsm$panelBtn || !(widget instanceof Button btn)) {
            return;
        }
        if (btn.getX() == xsm$prevW - 20 && btn.getWidth() == 20) {
            xsm$topRightBtnY = Math.min(xsm$topRightBtnY, btn.getY());
        }
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void xsm$onInit(CallbackInfo ci) {
        int w = xsm$prevW;
        int h = xsm$prevH;

        if (xsm$panel == null) {
            xsm$panel = new SeedMapPanel((GuiMap) (Object) this);
        }
        xsm$panel.onInit(w, h);

        var self = (GuiMap) (Object) this;
        int btnX = w - 20;
        int btnY = xsm$computePanelBtnY();
        if (btnY < 0 || btnY + 20 > h) {
            btnX = 30;
            btnY = 0;
        }
        xsm$panelBtn = new XsmIconButton(
                btnX, btnY,
                () -> {
                    xsm$panel.toggleOpen();
                    self.init(xsm$prevW, xsm$prevH);
                },
                () -> new Tooltip(Component.translatable("xsm.button.settings"), false));
        this.addButton(xsm$panelBtn);
    }

    @Unique
    private int xsm$computePanelBtnY() {
        if (xsm$topRightBtnY == Integer.MAX_VALUE) {
            return -1;
        }
        return xsm$topRightBtnY - 20;
    }

    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void xsm$onRenderTail(GuiGraphicsExtractor guiGraphics,
            int scaledMouseX, int scaledMouseY, float partialTicks, CallbackInfo ci) {
        if (xsm$panel != null) {
            xsm$panel.render(guiGraphics, scaledMouseX, scaledMouseY);
        }
        if (!xsm$settleChecked) {
            xsm$settleChecked = true;
            int y = xsm$computePanelBtnY();
            if (y >= 0 && (xsm$panelBtn.getX() != xsm$prevW - 20 || xsm$panelBtn.getY() != y)) {
                xsm$panelBtn.setPosition(xsm$prevW - 20, y);
            }
        }
    }

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void xsm$onMouseClicked(MouseButtonEvent event, boolean doubleClick,
            CallbackInfoReturnable<Boolean> cir) {
        if (xsm$panel != null) {
            var mc = Minecraft.getInstance();
            var window = mc.getWindow();
            double mx = Misc.getMouseX(mc, SupportMods.vivecraft)
                    * window.getGuiScaledWidth() / window.getWidth();
            double my = Misc.getMouseY(mc, SupportMods.vivecraft)
                    * window.getGuiScaledHeight() / window.getHeight();
            if (xsm$panel.mouseClicked(mx, my, event.button())) {
                cir.setReturnValue(true);
            }
        }
    }

    @Inject(method = "mouseReleased", at = @At("HEAD"), cancellable = true)
    private void xsm$onMouseReleased(MouseButtonEvent event,
            CallbackInfoReturnable<Boolean> cir) {
        if (xsm$panel != null) {
            var mc = Minecraft.getInstance();
            var window = mc.getWindow();
            double mx = Misc.getMouseX(mc, SupportMods.vivecraft)
                    * window.getGuiScaledWidth() / window.getWidth();
            double my = Misc.getMouseY(mc, SupportMods.vivecraft)
                    * window.getGuiScaledHeight() / window.getHeight();
            if (xsm$panel.mouseReleased(mx, my, event.button())) {
                cir.setReturnValue(true);
            }
        }
    }

    @Inject(method = "mouseScrolled", at = @At("HEAD"), cancellable = true)
    private void xsm$onMouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY,
            CallbackInfoReturnable<Boolean> cir) {
        if (xsm$panel != null && xsm$panel.mouseScrolled(mouseX, mouseY, scrollX, scrollY)) {
            cir.setReturnValue(true);
        }
    }

    @Shadow
    public abstract GuiEventListener addButton(GuiEventListener guiEventListener);
}
