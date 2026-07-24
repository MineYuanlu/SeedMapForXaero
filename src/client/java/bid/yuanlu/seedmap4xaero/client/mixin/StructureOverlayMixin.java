package bid.yuanlu.seedmap4xaero.client.mixin;

import bid.yuanlu.seedmap4xaero.client.accessor.SeedMapToggleAccessor;
import bid.yuanlu.seedmap4xaero.client.cache.StructureCache;
import bid.yuanlu.seedmap4xaero.client.cache.StructureCache.RegionPos;
import bid.yuanlu.seedmap4xaero.client.configs.ServerConfig;
import bid.yuanlu.seedmap4xaero.client.structure.StructureType;
import bid.yuanlu.seedmap4xaero.utils.BitSetView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.UnmodifiableView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import xaero.map.MapProcessor;
import xaero.map.gui.GuiMap;

@Mixin(GuiMap.class)
public class StructureOverlayMixin {

    @Shadow
    private double cameraX, cameraZ, scale;

    @Shadow
    private MapProcessor mapProcessor;

    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void onExtractRenderState(GuiGraphicsExtractor guiGraphics,
            int scaledMouseX, int scaledMouseY, float partialTicks, CallbackInfo ci) {
        if (mapProcessor == null)
            return;

        if (!((SeedMapToggleAccessor) this).xsm$isSeedMapEnabled())
            return;

        final var wc = ServerConfig.getActiveWorldConfig();
        if (wc == null)
            return;
        final var enabled = wc.getEnabledStructures();
        if (enabled.isEmpty())
            return;

        final Minecraft mc = Minecraft.getInstance();
        final int windowW = mc.getWindow().getWidth();
        final int windowH = mc.getWindow().getHeight();
        final double left = cameraX - (windowW / 2.0) / scale;
        final double right = left + windowW / scale;
        final double top = cameraZ - (windowH / 2.0) / scale;
        final double bottom = top + windowH / scale;

        StructureCache.updateStructuresInArea(enabled,
                (int) Math.floor(left), (int) Math.floor(top),
                (int) Math.ceil(right), (int) Math.ceil(bottom));

        for (var entry : StructureCache.REGIONS.entrySet()) {
            for (RegionPos rp : entry.getValue()) {
                if (rp.loaded) {
                    renderStructureMarker(guiGraphics, entry.getKey(), rp.blockX, rp.blockZ);
                }
            }
        }
    }

    @Unique
    private void renderStructureMarker(GuiGraphicsExtractor guiGraphics, @NotNull StructureType type, int blockX,
            int blockZ) {
        // TODO: 渲染结构标记
    }

}
