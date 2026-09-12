package bid.yuanlu.seedmap4xaero.client.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.resources.language.I18n;
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
import bid.yuanlu.seedmap4xaero.client.configs.basic.ServerConfig;
import bid.yuanlu.seedmap4xaero.client.configs.structure.StructureDataConfig;
import bid.yuanlu.seedmap4xaero.client.gui.VersionDropdown;
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
    private Button xsm$seedCopyBtn;

    @Unique
    private Long xsm$currentSeed;

    @Unique
    private VersionDropdown xsm$versionDropdown;

    @Unique
    private void xsm$useSeed(long seed) {
        var cfg = ServerConfig.getActiveConfig();
        if (cfg == null || this.mapProcessor == null)
            return;
        if (Minecraft.getInstance().getSingleplayerServer() != null) {
            xsm$LOGGER.info("useSeed: skip in singleplayer");
            return;
        }
        var mw = this.mapProcessor.getCurrentMWId();
        cfg.getOrCreateWorld(mw).seed(seed);
        ServerConfig.save();
    }

    @Inject(method = "init", at = @At("TAIL"), remap = false)
    private void xsm$onInitTail(GuiMap mapScreen, Minecraft minecraft, int width, int height, CallbackInfo ci) {
        xsm$LOGGER.info("init: active={}, mapProcessor={}", this.active, this.mapProcessor);
        if (this.active && this.mapProcessor != null) {
            ServerConfig.activate(this.mapProcessor);
            StructureDataConfig.activate(this.mapProcessor);
        }
        this.xsm$seedInput = null;
        this.xsm$seedConfirmBtn = null;
        this.xsm$seedCopyBtn = null;
        this.xsm$currentSeed = null;
        VersionDropdown.unsetActive();
        this.xsm$versionDropdown = null;
        if (!this.active)
            return;

        xsm$currentSeed = ServerConfig.resolveSeed();
        xsm$LOGGER.info("init: resolveSeed={}", xsm$currentSeed);
        xsm$seedInput = new EditBox(minecraft.font, width / 2 - 100, 148, 145, 20, Component.translatable("xsm.gui.switching.seed"));
        if (xsm$currentSeed != null) {
            xsm$seedInput.setSuggestion(String.valueOf(xsm$currentSeed));
        }

        xsm$seedConfirmBtn = Button.builder(
                Component.translatable("xsm.gui.switching.confirm"),
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
                            xsm$currentSeed = seed;
                        } catch (NumberFormatException e) {
                            xsm$LOGGER.warn("confirm: invalid seed format", e);
                        }
                    }
                }).bounds(width / 2 + 50, 148, 50, 20).build();

        xsm$seedCopyBtn = Button.builder(
                Component.translatable("xsm.gui.switching.copy"),
                b -> {
                    Long seed = xsm$currentSeed;
                    if (seed == null)
                        return;
                    minecraft.keyboardHandler.setClipboard(String.valueOf(seed));
                    xsm$LOGGER.info("copy: copied seed={}", seed);
                    if (minecraft.player != null) {
                        minecraft.player.sendOverlayMessage(
                                Component.translatable("xsm.gui.switching.copied", seed));
                    }
                }).bounds(width / 2 + 105, 148, 40, 20).build();
        xsm$seedCopyBtn.active = xsm$currentSeed != null;

        mapScreen.addButton(xsm$seedInput);
        mapScreen.addButton(xsm$seedConfirmBtn);
        mapScreen.addButton(xsm$seedCopyBtn);

        // MC 版本选择：仅多人模式（单机世界版本固定为客户端版本）
        if (Minecraft.getInstance().getSingleplayerServer() == null) {
            xsm$versionDropdown = new VersionDropdown(width / 2 - 100, 172, 200, this.mapProcessor);
        }
    }

    @Inject(method = "renderText", at = @At("TAIL"), remap = false)
    private void xsm$onRenderTextTail(GuiGraphicsExtractor guiGraphics, Minecraft minecraft, int mouseX, int mouseY,
            int width, int height, CallbackInfo ci) {
        if (!this.active)
            return;
        String label = I18n.get("xsm.gui.switching.current_seed") + " "
                + (xsm$currentSeed != null ? String.valueOf(xsm$currentSeed) : "-");
        MapRenderHelper.drawStringWithBackground(guiGraphics, minecraft.font, label, width / 2 - 100, 132, -1, 0.0F,
                0.0F, 0.0F, 0.4F);
        if (xsm$versionDropdown != null) {
            xsm$versionDropdown.render(guiGraphics, minecraft.font, mouseX, mouseY);
        }
    }

    @Shadow
    public boolean active;

    @Shadow
    private MapProcessor mapProcessor;
}
