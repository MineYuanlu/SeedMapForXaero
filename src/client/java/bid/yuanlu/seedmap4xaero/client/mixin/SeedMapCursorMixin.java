package bid.yuanlu.seedmap4xaero.client.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import bid.yuanlu.seedmap4xaero.client.accessor.SeedMapToggleAccessor;
import bid.yuanlu.seedmap4xaero.client.cache.QueryPointCache;
import bid.yuanlu.seedmap4xaero.client.nativeapi.Xsm;
import xaero.lib.client.config.ClientConfigManager;
import xaero.map.MapProcessor;
import xaero.map.WorldMap;
import xaero.map.common.config.option.WorldMapProfiledConfigOptions;
import xaero.map.graphics.MapRenderHelper;
import xaero.map.gui.GuiMap;

/**
 * Mixin into Xaero World Map's {@code GuiMap.extractRenderState}，
 * 接管未探索区域的坐标与生物群系文本显示。
 *
 * <p>
 * 通过 {@link Xsm#queryPoint} 查询 C 侧数据。
 * </p>
 */
@Mixin(GuiMap.class)
public class SeedMapCursorMixin {

    @Shadow
    private int mouseBlockPosX;

    @Shadow
    private int mouseBlockPosY;

    @Shadow
    private int mouseBlockPosZ;

    @Shadow
    private MapProcessor mapProcessor;

    @Unique
    private boolean xsm$biomeAlreadyDrawn;

    // ═══════════════════════════════════════════════════════════════
    // @Redirect: 坐标文本 (ordinal=0, String overload)
    // ═══════════════════════════════════════════════════════════════

    @Redirect(method = "extractRenderState", at = @At(value = "INVOKE", target = "Lxaero/map/graphics/MapRenderHelper;drawCenteredStringWithBackground(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/gui/Font;Ljava/lang/String;IIIFFFF)V", ordinal = 0))
    private void xsm$redirectCoordsText(GuiGraphicsExtractor gui, Font font, String str, int x, int y, int color,
            float r, float g, float b, float a) {
        xsm$biomeAlreadyDrawn = false;

        if (this.mouseBlockPosY != 32767 || !xsm$isSeedMapEnabled()) {
            MapRenderHelper.drawCenteredStringWithBackground(gui, font, str, x, y, color, r, g, b, a);
            return;
        }

        long seed = xsm$getWorldSeed();
        if (seed == Long.MIN_VALUE) {
            MapRenderHelper.drawCenteredStringWithBackground(gui, font, str, x, y, color, r, g, b, a);
            return;
        }

        int dim = xsm$getDimensionId();
        if (dim == Integer.MIN_VALUE) {
            MapRenderHelper.drawCenteredStringWithBackground(gui, font, str, x, y, color, r, g, b, a);
            return;
        }

        int seedY = QueryPointCache.queryHeight(this.mouseBlockPosX, this.mouseBlockPosZ);

        String newStr = "X: " + this.mouseBlockPosX;
        if (seedY != QueryPointCache.UNKNOWN_HEIGHT) {
            newStr += " Y: " + seedY;
        }
        newStr += " Z: " + this.mouseBlockPosZ;
        MapRenderHelper.drawCenteredStringWithBackground(gui, font, newStr, x, y, color, r, g, b, a);

        if (xsm$isDisplayBiomeEnabled()) {
            String biome = QueryPointCache.queryBiomeName(this.mouseBlockPosX, this.mouseBlockPosZ);
            if (biome != null) {
                String biomeText = I18n.get("biome.minecraft." + biome);
                MapRenderHelper.drawCenteredStringWithBackground(gui, font, biomeText, x, y + 10, color, r, g, b, a);
                xsm$biomeAlreadyDrawn = true;
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // @Redirect: 生物群系文本 (ordinal=1, String overload)
    // ═══════════════════════════════════════════════════════════════

    @Redirect(method = "extractRenderState", at = @At(value = "INVOKE", target = "Lxaero/map/graphics/MapRenderHelper;drawCenteredStringWithBackground(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/gui/Font;Ljava/lang/String;IIIFFFF)V", ordinal = 1))
    private void xsm$redirectBiomeText(GuiGraphicsExtractor gui, Font font, String str, int x, int y, int color,
            float r, float g, float b, float a) {
        MapRenderHelper.drawCenteredStringWithBackground(gui, font, str, x, y, color, r, g, b, a);
    }

    // ═══════════════════════════════════════════════════════════════
    // @Inject (@At("RETURN")): 未探索区域生物群系保底
    // ═══════════════════════════════════════════════════════════════

    @Inject(method = "extractRenderState", at = @At("RETURN"))
    private void xsm$onReturn(GuiGraphicsExtractor gui, int scaledMouseX, int scaledMouseY, float partialTicks,
            CallbackInfo ci) {
        xsm$biomeAlreadyDrawn = false;
        if (!xsm$isSeedMapEnabled())
            return;
        if (this.mouseBlockPosY != 32767)
            return;

        long seed = xsm$getWorldSeed();
        if (seed == Long.MIN_VALUE)
            return;

        int dim = xsm$getDimensionId();
        if (dim == Integer.MIN_VALUE)
            return;

        if (!xsm$isDisplayBiomeEnabled())
            return;

        String biome = QueryPointCache.queryBiomeName(this.mouseBlockPosX, this.mouseBlockPosZ);
        if (biome != null) {
            int yOff = xsm$isCoordsEnabled() ? 12 : 2;
            var mcFont = Minecraft.getInstance().font;
            int scrW = Minecraft.getInstance().getWindow().getGuiScaledWidth();
            MapRenderHelper.drawCenteredStringWithBackground(gui, mcFont, I18n.get("biome.minecraft." + biome),
                    scrW / 2, yOff, -1, 0.0F, 0.0F, 0.0F, 0.4F);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 辅助方法
    // ═══════════════════════════════════════════════════════════════

    @Unique
    private boolean xsm$isSeedMapEnabled() {
        return ((SeedMapToggleAccessor) this).xsm$isSeedMapEnabled();
    }

    @Unique
    private static long xsm$getWorldSeed() {
        final var server = Minecraft.getInstance().getSingleplayerServer();
        if (server != null) {
            try {
                return server.getWorldGenSettings().options().seed();
            } catch (Exception e) {
                return Long.MIN_VALUE;
            }
        }
        return Long.MIN_VALUE;
    }

    @Unique
    private int xsm$getDimensionId() {
        try {
            var dimKey = this.mapProcessor.getMapWorld().getCurrentDimension().getDimId();
            if (dimKey == Level.OVERWORLD)
                return 0;
            if (dimKey == Level.NETHER)
                return -1;
            if (dimKey == Level.END)
                return 1;
            return 0;
        } catch (Exception e) {
            return Integer.MIN_VALUE;
        }
    }

    @Unique
    private static boolean xsm$isCoordsEnabled() {
        ClientConfigManager cm = WorldMap.INSTANCE.getConfigs().getClientConfigManager();
        return (Boolean) cm.getEffective(WorldMapProfiledConfigOptions.COORDINATES);
    }

    @Unique
    private static boolean xsm$isDisplayBiomeEnabled() {
        ClientConfigManager cm = WorldMap.INSTANCE.getConfigs().getClientConfigManager();
        return (Boolean) cm.getEffective(WorldMapProfiledConfigOptions.DISPLAY_HOVERED_BIOME);
    }

}
