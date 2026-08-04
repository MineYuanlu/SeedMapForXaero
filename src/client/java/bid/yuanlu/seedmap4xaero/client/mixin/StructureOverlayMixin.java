package bid.yuanlu.seedmap4xaero.client.mixin;

import org.joml.Matrix3x2f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import bid.yuanlu.seedmap4xaero.client.cache.StrongholdCache.StrongholdPos;
import bid.yuanlu.seedmap4xaero.client.cache.StructureCache;
import bid.yuanlu.seedmap4xaero.client.cache.StructureCache.StructurePos;
import bid.yuanlu.seedmap4xaero.client.configs.ServerConfig;
import bid.yuanlu.seedmap4xaero.client.structure.StructureBitFlagView;
import bid.yuanlu.seedmap4xaero.client.structure.StructureType;
import bid.yuanlu.seedmap4xaero.utils.BitSetView;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.state.gui.BlitRenderState;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import net.minecraft.client.resources.language.I18n;

import xaero.map.MapProcessor;
import xaero.map.graphics.MapRenderHelper;
import xaero.map.gui.GuiMap;

/**
 * 结构图标叠加 + 悬停提示。
 *
 * <p>
 * 图标渲染回到合成后的屏幕空间 (与原始 guiGraphics.blit 完全一致: 透明、不受地图
 * 光照影响、坐标平滑), 但绕开 MC 26.1 GuiRenderState 的 O(N²) 空间节点树:
 * 直接用 {@link net.minecraft.client.renderer.state.gui.GuiRenderState#addBlitToCurrentLayer}
 * 把每个图标作为 BlitRenderState 追加到当前层, 单次插入 O(1), 且所有图标共用一张
 * 精灵图 → 渲染端自动合并为一次绘制。
 *
 * <p>注意: 渲染注入锚点是
 * {@code ImprovedFramebuffer.bindDefaultFramebuffer} (仅在 map-loaded 分支调用)。
 * Xaero 若重构该方法, 注入会静默失效 (图标消失但无报错), 重编时需检查该锚点。</p>
 */
@Mixin(GuiMap.class)
public class StructureOverlayMixin {
    @Shadow
    private double cameraX, cameraZ, scale, screenScale;

    @Shadow
    private MapProcessor mapProcessor;

    @Unique
    private static final int ICON_SIZE = 20;

    @Unique
    private String xsm$hoverText;

    @Unique
    private float xsm$bestDist;

    /** 结构功能开关 + 活跃世界配置的合并守卫; null = 本次不绘制/不更新 */
    @Unique
    private BitSetView xsm$enabledTypes() {
        if (mapProcessor == null)
            return null;
        if (!ServerConfig.isStructureEnabled())
            return null;
        final var wc = ServerConfig.getActiveWorldConfig();
        if (wc == null)
            return null;
        final var enabled = wc.getStructureTypeSet();
        if (enabled.isEmpty())
            return null;
        return enabled;
    }

    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void updateStructures(GuiGraphicsExtractor guiGraphics,
            int scaledMouseX, int scaledMouseY, float partialTicks, CallbackInfo ci) {
        final var enabled = xsm$enabledTypes();
        if (enabled == null)
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
     * 屏幕空间绘制结构图标 (合成之后, 默认帧缓冲已恢复为主目标)。
     * 每个图标 = 一个 BlitRenderState 直接追加到 GuiRenderState 当前层, O(N) 插入,
     * 渲染端按纹理自动批处理成一次绘制。
     */
    @Inject(method = "extractRenderState", at = @At(value = "INVOKE", target = "Lxaero/map/graphics/ImprovedFramebuffer;bindDefaultFramebuffer(Lnet/minecraft/client/Minecraft;)V", shift = At.Shift.AFTER))
    private void renderStructures(GuiGraphicsExtractor guiGraphics,
            int scaledMouseX, int scaledMouseY, float partialTicks, CallbackInfo ci) {
        if (xsm$enabledTypes() == null)
            return;

        final var wc = ServerConfig.getActiveWorldConfig();
        final float iconScale = ServerConfig.getStructureIconSize();
        final float iconHalf = ICON_SIZE * iconScale * 0.5f;

        // 二级过滤仅作用于渲染, 生成/缓存不变: 结构整体禁用 或 该变种禁用 则不显示
        final StructureBitFlagView flags = wc.getDisabledStructures();

        final Minecraft mc = Minecraft.getInstance();
        final var guiRenderState = mc.gameRenderer.getGameRenderState().guiRenderState;
        // 新开一层 stratum: 保证图标渲染在 map composite 之上 (addBlitToCurrentLayer 只挂到
        // current, 而 current 在 Xaero 的 debug 文本等操作后不可靠, 可能在地图之下被覆盖)
        guiRenderState.nextStratum();
        final var tex = mc.getTextureManager().getTexture(StructureType.STRUCTURES_TEXTURE);
        final GpuTextureView texView = tex.getTextureView();
        final GpuSampler sampler = tex.getSampler();
        final TextureSetup setup = TextureSetup.singleTexture(texView, sampler);
        final Matrix3x2f basePose = new Matrix3x2f(guiGraphics.pose());

        final double invScale = 1.0 / screenScale;
        final int windowW = mc.getWindow().getWidth();
        final int windowH = mc.getWindow().getHeight();
        final double guiW = windowW * invScale;
        final double guiH = windowH * invScale;

        xsm$hoverText = null;
        xsm$bestDist = iconHalf;

        for (var entry : StructureCache.REGIONS.entrySet()) {
            StructureType type = entry.getKey();
            final int typeId = type.id;
            for (StructurePos rp : entry.getValue()) {
                if (!rp.loaded())
                    continue;
                if (flags.isStructureSet(typeId) || flags.isVariantSet(typeId, rp.getVariant()))
                    continue;
                xsm$submitIcon(guiRenderState, setup, basePose, type, rp.getVariant(),
                        rp.blockX(), rp.blockZ(), invScale, iconScale, iconHalf,
                        guiW, guiH, scaledMouseX, scaledMouseY);
            }
        }

        if (!flags.isStructureSet(StructureType.STRONGHOLD.id)) {
            final var strongholds = StructureCache.strongholds();
            if (strongholds != null) {
                for (StrongholdPos sh : strongholds) {
                    if (sh == null)
                        continue;
                    xsm$submitIcon(guiRenderState, setup, basePose, StructureType.STRONGHOLD,
                            sh.getVariant(), sh.blockX(), sh.blockZ(), invScale, iconScale, iconHalf,
                            guiW, guiH, scaledMouseX, scaledMouseY);
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

    /**
     * 屏幕坐标: 视口剔除 → 悬停判定 → 构造 BlitRenderState 追加到当前 GUI 层。
     * 坐标语义与原始 blit 一致: 图标 20×20(×iconScale) 居中于结构锚点。
     */
    @Unique
    private void xsm$submitIcon(GuiRenderState guiRenderState, TextureSetup setup, Matrix3x2f basePose,
            StructureType type, int variant, int blockX, int blockZ,
            double invScale, float iconScale, float iconHalf,
            double guiW, double guiH, int scaledMouseX, int scaledMouseY) {
        final double guiX = (blockX - cameraX) * scale * invScale + guiW / 2.0;
        final double guiZ = (blockZ - cameraZ) * scale * invScale + guiH / 2.0;

        if (guiX < -iconHalf || guiX > guiW + iconHalf || guiZ < -iconHalf || guiZ > guiH + iconHalf)
            return;

        final double dx = scaledMouseX - guiX;
        final double dy = scaledMouseY - guiZ;
        final float dist = (float) Math.max(Math.abs(dx), Math.abs(dy));
        if (dist < xsm$bestDist) {
            xsm$bestDist = dist;
            String hover = I18n.get(type.translationKey());
            String vk = type.variantTranslationKey(variant);
            if (vk != null)
                hover += " (" + I18n.get(vk) + ")";
            xsm$hoverText = hover;
        }

        final int idx = type.getSpriteIndex(variant);
        final float u0 = (float) (idx * ICON_SIZE) / StructureType.SPRITESHEET_WIDTH;
        final float u1 = u0 + (float) ICON_SIZE / StructureType.SPRITESHEET_WIDTH;

        final Matrix3x2f pose = new Matrix3x2f(basePose)
                .translate((float) guiX, (float) guiZ)
                .scale(iconScale, iconScale);
        final BlitRenderState blit = new BlitRenderState(RenderPipelines.GUI_TEXTURED, setup, pose,
                -ICON_SIZE / 2, -ICON_SIZE / 2, ICON_SIZE / 2, ICON_SIZE / 2,
                u0, u1, 0.0F, 1.0F, -1, null, null);
        guiRenderState.addBlitToCurrentLayer(blit);
    }
}
