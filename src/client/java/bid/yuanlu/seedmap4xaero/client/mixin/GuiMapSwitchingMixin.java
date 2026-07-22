package bid.yuanlu.seedmap4xaero.client.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import bid.yuanlu.seedmap4xaero.client.cache.CellCache;
import bid.yuanlu.seedmap4xaero.client.configs.ServerConfig;
import xaero.map.MapProcessor;
import xaero.map.graphics.MapRenderHelper;
import xaero.map.gui.GuiMap;
import xaero.map.gui.GuiMapSwitching;

@Mixin(GuiMapSwitching.class)
public class GuiMapSwitchingMixin {

    @Unique
    private static final Logger xsm$LOGGER = LoggerFactory.getLogger("seedmap4xaero/GuiMapSwitchingMixin");

    @Unique
    private EditBox xsm$seedInput;

    @Unique
    private Button xsm$seedConfirmBtn;

    @Unique
    private void xsm$useSeed(long seed) {
        var cfg = ServerConfig.getActiveConfig();
        if (cfg == null || this.mapProcessor == null)
            return;
        if (Minecraft.getInstance().getSingleplayerServer() != null) {
            xsm$LOGGER.info("useSeed: skip in singleplayer");
            return;
        }
        var dim = this.mapProcessor.getCurrentDimId();
        var mw = this.mapProcessor.getCurrentMWId();
        var wc = cfg.getOrCreateWorld(dim, mw);
        wc.seed(seed);
        ServerConfig.save();
    }

    @Inject(method = "init", at = @At("TAIL"), remap = false)
    private void xsm$onInitTail(GuiMap mapScreen, Minecraft minecraft, int width, int height, CallbackInfo ci) {
        xsm$LOGGER.info("init: active={}, mapProcessor={}", this.active, this.mapProcessor);
        if (this.active && this.mapProcessor != null) {
            ServerConfig.activate(this.mapProcessor);
        }
        this.xsm$seedInput = null;
        this.xsm$seedConfirmBtn = null;
        if (!this.active)
            return;

        Long currentSeed = ServerConfig.resolveSeed();
        xsm$LOGGER.info("init: resolveSeed={}", currentSeed);
        xsm$seedInput = new EditBox(minecraft.font, width / 2 - 100, 148, 145, 20, Component.literal("Seed"));
        if (currentSeed != null) {
            xsm$seedInput.setSuggestion(String.valueOf(currentSeed));
        }

        xsm$seedConfirmBtn = Button.builder(
                Component.literal("确定"),
                b -> {
                    String text = xsm$seedInput.getValue();
                    if (!text.isEmpty()) {
                        try {
                            long seed = Long.parseLong(text);
                            xsm$LOGGER.info("confirm: parsed seed={}", seed);
                            xsm$useSeed(seed);
                            CellCache.clear();
                            xsm$seedInput.setValue("");
                            xsm$seedInput.setSuggestion(String.valueOf(seed));
                        } catch (NumberFormatException e) {
                            xsm$LOGGER.warn("confirm: invalid seed format", e);
                        }
                    }
                }
        ).bounds(width / 2 + 50, 148, 50, 20).build();

        mapScreen.addButton(xsm$seedInput);
        mapScreen.addButton(xsm$seedConfirmBtn);
    }

    @Inject(method = "renderText", at = @At("TAIL"), remap = false)
    private void xsm$onRenderTextTail(GuiGraphicsExtractor guiGraphics, Minecraft minecraft, int mouseX, int mouseY, int width, int height, CallbackInfo ci) {
        if (!this.active)
            return;
        String label = "当前世界种子:";
        MapRenderHelper.drawStringWithBackground(guiGraphics, minecraft.font, label, width / 2 - 100, 132, -1, 0.0F, 0.0F, 0.0F, 0.4F);
    }

    @Shadow
    public boolean active;

    @Shadow
    private MapProcessor mapProcessor;
}
