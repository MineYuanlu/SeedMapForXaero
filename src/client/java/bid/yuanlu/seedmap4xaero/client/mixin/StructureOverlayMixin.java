package bid.yuanlu.seedmap4xaero.client.mixin;

import bid.yuanlu.seedmap4xaero.client.accessor.SeedMapToggleAccessor;
import bid.yuanlu.seedmap4xaero.client.cache.StructureCache;
import bid.yuanlu.seedmap4xaero.client.cache.StructureCache.RegionPos;
import bid.yuanlu.seedmap4xaero.client.configs.ServerConfig;
import bid.yuanlu.seedmap4xaero.client.structure.StructureType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import xaero.map.MapProcessor;
import xaero.map.graphics.MapRenderHelper;
import xaero.map.gui.GuiMap;

@Mixin(GuiMap.class)
public class StructureOverlayMixin {
    private static final Logger LOGGER = LoggerFactory.getLogger("seedmap4xaero/StructureOverlayMixin");

    @Shadow
    private double cameraX, cameraZ, scale, screenScale;

    @Shadow
    private MapProcessor mapProcessor;

    @Unique
    private static final int ICON_SIZE = 20;

    @Unique
    private static final int ICON_HALF = ICON_SIZE / 2;

    @Unique
    private String xsm$hoverText;

    /**
     * 更新结构缓存（在每帧最末尾执行，避免阻塞渲染）
     */
    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void updateStructures(GuiGraphicsExtractor guiGraphics,
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
    }

    /**
     * 在 Xaero 的 HUD 文字之前，主帧缓冲绑回后渲染结构图标
     */
    @Inject(method = "extractRenderState", at = @At(value = "INVOKE", target = "Lxaero/map/graphics/ImprovedFramebuffer;bindDefaultFramebuffer(Lnet/minecraft/client/Minecraft;)V", shift = At.Shift.AFTER))
    private void renderStructures(GuiGraphicsExtractor guiGraphics,
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
        final double invScale = 1.0 / screenScale;

        xsm$hoverText = null;
        int bestDist = ICON_HALF;

        for (var entry : StructureCache.REGIONS.entrySet()) {
            StructureType type = entry.getKey();
            float u0 = (float) (type.spriteIndex * ICON_SIZE) / StructureType.SPRITESHEET_WIDTH;
            float u1 = u0 + (float) ICON_SIZE / StructureType.SPRITESHEET_WIDTH;
            for (RegionPos rp : entry.getValue()) {
                if (!rp.loaded)
                    continue;

                double pixelOffX = (rp.blockX - cameraX) * scale + windowW / 2.0;
                double pixelOffZ = (rp.blockZ - cameraZ) * scale + windowH / 2.0;
                double guiX = pixelOffX * invScale;
                double guiZ = pixelOffZ * invScale;

                int baseX = (int) Math.floor(guiX);
                int baseY = (int) Math.floor(guiZ);
                float fracX = (float) (guiX - baseX);
                float fracY = (float) (guiZ - baseY);

                guiGraphics.pose().pushMatrix();
                guiGraphics.pose().translate(fracX, fracY);
                guiGraphics.blit(StructureType.STRUCTURES_TEXTURE,
                        baseX - ICON_HALF, baseY - ICON_HALF,
                        baseX + ICON_HALF, baseY + ICON_HALF,
                        u0, u1, 0.0F, 1.0F);
                guiGraphics.pose().popMatrix();

                int dist = Math.max(Math.abs(scaledMouseX - baseX), Math.abs(scaledMouseY - baseY));
                if (dist < bestDist) {
                    bestDist = dist;
                    xsm$hoverText = type.key;
                }
            }
        }

        if (xsm$hoverText != null) {
            MapRenderHelper.drawStringWithBackground(guiGraphics,
                    mc.font, xsm$hoverText,
                    scaledMouseX + 12, scaledMouseY - 4,
                    -1, 0.0F, 0.0F, 0.0F, 0.6F);
        }
    }

}
