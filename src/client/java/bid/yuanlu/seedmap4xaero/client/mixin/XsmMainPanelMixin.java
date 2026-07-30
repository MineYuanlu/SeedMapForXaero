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
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.input.MouseButtonEvent;
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

    @Inject(method = "init", at = @At("TAIL"))
    private void xsm$onInit(CallbackInfo ci) {
        var mc = Minecraft.getInstance();
        var window = mc.getWindow();
        int w = window.getGuiScaledWidth();
        int h = window.getGuiScaledHeight();
        xsm$prevW = w;
        xsm$prevH = h;

        if (xsm$panel == null) {
            xsm$panel = new SeedMapPanel((GuiMap) (Object) this);
        }
        xsm$panel.onInit(w, h);

        var self = (GuiMap) (Object) this;
        xsm$panelBtn = new XsmIconButton(
                w - 20, h - 180,
                () -> {
                    xsm$panel.toggleOpen();
                    self.init(xsm$prevW, xsm$prevH);
                },
                () -> new Tooltip(net.minecraft.network.chat.Component.literal("种子地图设置"), false));
        this.addButton(xsm$panelBtn);
    }

    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void xsm$onRenderTail(GuiGraphicsExtractor guiGraphics,
            int scaledMouseX, int scaledMouseY, float partialTicks, CallbackInfo ci) {
        if (xsm$panel != null) {
            xsm$panel.render(guiGraphics, scaledMouseX, scaledMouseY);
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
