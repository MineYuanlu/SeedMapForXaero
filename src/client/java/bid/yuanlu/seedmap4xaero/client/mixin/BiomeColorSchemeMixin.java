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

import bid.yuanlu.seedmap4xaero.client.cache.CellCache;
import bid.yuanlu.seedmap4xaero.client.nativeapi.Xsm;
import bid.yuanlu.seedmap4xaero.client.render.BiomeColorTable;
import xaero.lib.client.gui.widget.Tooltip;
import xaero.map.gui.GuiMap;
import xaero.map.gui.TooltipButton;

@Mixin(GuiMap.class)
public abstract class BiomeColorSchemeMixin {

    @Unique
    private Button xsm$schemeButton;

    @Inject(method = "init", at = @At("TAIL"))
    private void xsm$onInitTail(CallbackInfo ci) {
        var window = Minecraft.getInstance().getWindow();
        this.xsm$schemeButton = new TooltipButton(
                window.getGuiScaledWidth() - 20,
                window.getGuiScaledHeight() - 200,
                20, 20,
                Component.literal(schemeLabel()),
                this::xsm$onCycleScheme,
                this::xsm$createSchemeTooltip);
        this.addButton(this.xsm$schemeButton);

        Xsm.setBiomeColorTable(BiomeColorTable.getProvider());
    }

    @Unique
    private static String schemeLabel() {
        String name = BiomeColorTable.getProvider().name();
        return name.length() <= 2 ? name : name.substring(0, 2);
    }

    @Unique
    private Tooltip xsm$createSchemeTooltip() {
        return new Tooltip(Component.literal("Color: " + BiomeColorTable.getProvider().name()), false);
    }

    @Unique
    private void xsm$onCycleScheme(Button b) {
        BiomeColorTable.cycleProvider();
        b.setMessage(Component.literal(schemeLabel()));
        Xsm.setBiomeColorTable(BiomeColorTable.getProvider());
        CellCache.clear();
    }

    @Shadow
    public abstract GuiEventListener addButton(GuiEventListener guiEventListener);
}
