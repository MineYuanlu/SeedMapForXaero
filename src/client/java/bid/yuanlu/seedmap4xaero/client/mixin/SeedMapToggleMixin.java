package bid.yuanlu.seedmap4xaero.client.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import bid.yuanlu.seedmap4xaero.client.accessor.SeedMapToggleAccessor;
import xaero.lib.client.gui.widget.Tooltip;
import xaero.map.gui.GuiMap;
import xaero.map.gui.TooltipButton;

@Mixin(GuiMap.class)
public abstract class SeedMapToggleMixin implements SeedMapToggleAccessor {

    @Unique
    private boolean xsm$seedMapEnabled = true;

    @Unique
    private Button xsm$toggleButton;

    @Override
    public boolean xsm$isSeedMapEnabled() {
        return xsm$seedMapEnabled;
    }

    @Override
    public void xsm$setSeedMapEnabled(boolean enabled) {
        this.xsm$seedMapEnabled = enabled;
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void xsm$onInitTail(CallbackInfo ci) {
        var window = Minecraft.getInstance().getWindow();
        this.xsm$toggleButton = new TooltipButton(
                window.getGuiScaledWidth() - 20,
                window.getGuiScaledHeight() - 180,
                20, 20,
                Component.literal("S"),
                this::xsm$onToggleSeedMap,
                this::xsm$createTooltip);
        this.addButton(this.xsm$toggleButton);
    }

    @Unique
    private Tooltip xsm$createTooltip() {
        return new Tooltip(Component.literal(
                xsm$seedMapEnabled ? "Seed Map: ON" : "Seed Map: OFF"), false);
    }

    @Unique
    private void xsm$onToggleSeedMap(Button b) {
        xsm$seedMapEnabled = !xsm$seedMapEnabled;
    }

    @Shadow
    public abstract GuiEventListener addButton(GuiEventListener guiEventListener);
}
