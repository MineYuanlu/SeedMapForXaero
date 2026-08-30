package bid.yuanlu.seedmap4xaero.client.mixin;

import org.joml.Matrix3x2f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import bid.yuanlu.seedmap4xaero.client.accessor.GameRendererAccessor;
import bid.yuanlu.seedmap4xaero.client.cache.StructureCache;
import bid.yuanlu.seedmap4xaero.client.configs.basic.ServerConfig;
import bid.yuanlu.seedmap4xaero.client.configs.structure.StructureDataConfig;
import bid.yuanlu.seedmap4xaero.client.configs.structure.StructureGroups;
import bid.yuanlu.seedmap4xaero.client.structure.ChestLootWidget;
import bid.yuanlu.seedmap4xaero.client.structure.LootPreviewState;
import bid.yuanlu.seedmap4xaero.client.structure.StructureIcons;
import bid.yuanlu.seedmap4xaero.client.structure.StructureType;
import bid.yuanlu.seedmap4xaero.utils.BitSetView;

import java.util.ArrayList;

import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.state.gui.BlitRenderState;
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
    private final ArrayList<String> xsm$hoverLines = new ArrayList<>();

    @Unique
    private float xsm$bestDist;

    @Unique
    private StructureType xsm$hoverType;

    @Unique
    private int xsm$hoverBlockX, xsm$hoverBlockZ;

    @Unique
    private double xsm$hoverGuiX, xsm$hoverGuiZ;

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

        final float iconScale = ServerConfig.getStructureIconSize();
        final float iconHalf = ICON_SIZE * iconScale * 0.5f;

        final Minecraft mc = Minecraft.getInstance();
        final var guiRenderState = ((GameRendererAccessor) mc.gameRenderer).xsm$gameRenderState().guiRenderState;
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
        xsm$hoverType = null;
        xsm$hoverLines.clear();

        final StructureIcons.Transform t = new StructureIcons.Transform(
                cameraX, cameraZ, scale, invScale, guiW, guiH);
        StructureIcons.forEachVisible((type, variant, blockX, blockZ, guiX, guiZ) -> {
            if (guiX < -iconHalf || guiX > guiW + iconHalf || guiZ < -iconHalf || guiZ > guiH + iconHalf)
                return;

            final double dx = scaledMouseX - guiX;
            final double dy = scaledMouseY - guiZ;
            final float dist = (float) Math.max(Math.abs(dx), Math.abs(dy));
            if (dist < xsm$bestDist) {
                xsm$bestDist = dist;
                xsm$hoverType = type;
                xsm$hoverBlockX = blockX;
                xsm$hoverBlockZ = blockZ;
                xsm$hoverGuiX = guiX;
                xsm$hoverGuiZ = guiZ;
                String hover = I18n.get(type.translationKey());
                String vk = type.variantTranslationKey(variant);
                if (vk != null)
                    hover += " (" + I18n.get(vk) + ")";
                xsm$hoverText = hover;
                xsm$buildHoverLines(type, blockX, blockZ);
            }

            final int idx = type.getSpriteIndex(variant);
            final float u0 = (float) (idx * ICON_SIZE) / StructureType.SPRITESHEET_WIDTH;
            final float u1 = u0 + (float) ICON_SIZE / StructureType.SPRITESHEET_WIDTH;

            final Matrix3x2f pose = new Matrix3x2f(basePose)
                    .translate((float) guiX, (float) guiZ)
                    .scale(iconScale, iconScale);
            final BlitRenderState blit = new BlitRenderState(RenderPipelines.GUI_TEXTURED, setup, pose,
                    -ICON_SIZE / 2, -ICON_SIZE / 2, ICON_SIZE / 2, ICON_SIZE / 2,
                    u0, u1, 0.0F, 1.0F, -1, null);
            guiRenderState.addBlitToCurrentLayer(blit);
        }, t);

        xsm$renderLootWidget(guiGraphics, scaledMouseX, scaledMouseY, guiW, guiH);

        // hover tooltip 最后绘制, 压在战利品预览 widget 之上
        if (!xsm$hoverLines.isEmpty()) {
            xsm$drawTooltip(guiGraphics, mc.font, xsm$hoverLines,
                    scaledMouseX, scaledMouseY, guiW, guiH);
        }
    }

    /** 组装 hover 的多行内容: 标题 + 访问状态 + 分组。 */
    @Unique
    private void xsm$buildHoverLines(StructureType type, int blockX, int blockZ) {
        xsm$hoverLines.clear();
        xsm$hoverLines.add(xsm$hoverText);
        final var dimData = StructureDataConfig.activeDimData();
        final var mark = dimData == null ? null
                : dimData.getMark(type.id, StructureDataConfig.keyOf(blockX, blockZ));
        xsm$hoverLines.add(I18n.get(mark != null && mark.visited()
                ? "xsm.hover.visited" : "xsm.hover.unvisited",
                mark == null ? 0 : mark.minDist()));
        if (mark != null && !mark.group().isEmpty()) {
            xsm$hoverLines.add(I18n.get("xsm.hover.group",
                    I18n.get(StructureGroups.translationKey(mark.group()))));
        }
    }

    /**
     * 原版物品 tooltip 风格的多行悬浮框: 深色底 + 紫色 1px 边框,
     * 首行白色 (标题), 其余行灰色 (详情)。
     */
    @Unique
    private static void xsm$drawTooltip(GuiGraphicsExtractor g, Font font,
            ArrayList<String> lines, int mouseX, int mouseY, double guiW, double guiH) {
        final int PAD = 3;
        int textW = 0;
        for (String line : lines)
            textW = Math.max(textW, font.width(line));
        final int lineH = font.lineHeight + 1;
        final int boxW = textW + PAD * 2;
        final int boxH = lines.size() * lineH + PAD * 2 - 1;
        int x = mouseX + 12;
        int y = mouseY - 4;
        if (x + boxW > guiW)
            x = (int) guiW - boxW;
        if (y + boxH > guiH)
            y = (int) guiH - boxH;
        x = Math.max(1, x);
        y = Math.max(1, y);

        g.fill(x, y, x + boxW, y + boxH, 0xF0100010);
        // 紫色边框 (上下 0xFF5000FF, 左右渐变简化为同色系, 与原版观感一致)
        g.fill(x - 1, y - 1, x + boxW + 1, y, 0xFF5000FF);
        g.fill(x - 1, y + boxH, x + boxW + 1, y + boxH + 1, 0xFF5000FF);
        g.fill(x - 1, y, x, y + boxH, 0xFF2A007F);
        g.fill(x + boxW, y, x + boxW + 1, y + boxH, 0xFF2A007F);

        for (int i = 0; i < lines.size(); i++) {
            g.text(font, lines.get(i), x + PAD, y + PAD + i * lineH,
                    i == 0 ? 0xFFFFFFFF : 0xFFA0A0A8);
        }
    }

    @Unique
    private void xsm$renderLootWidget(GuiGraphicsExtractor guiGraphics,
            int scaledMouseX, int scaledMouseY, double guiW, double guiH) {
        if (!ServerConfig.isLootPreviewEnabled())
            return;

        // 悬浮快速查看: 命中战利品结构图标 → 建立 widget
        if (xsm$hoverType != null && LootPreviewState.isLootSupported(xsm$hoverType)) {
            LootPreviewState.onHover(xsm$hoverType, xsm$hoverBlockX, xsm$hoverBlockZ,
                    xsm$hoverGuiX, xsm$hoverGuiZ, guiW, guiH);
        } else {
            LootPreviewState.onHoverEnd();
        }

        // 固定态滑出容器+缓冲 → 解除固定
        LootPreviewState.onUnpinIfOutside(scaledMouseX, scaledMouseY);

        ChestLootWidget widget = LootPreviewState.widget();
        if (widget == null)
            return;

        final Minecraft mc = Minecraft.getInstance();
        widget.extractRenderState(guiGraphics, scaledMouseX, scaledMouseY, mc.font);

        // 物品 tooltip 渲染到新 stratum (与 SeedMapper 一致)
        var tooltip = widget.getPendingItemTooltip();
        if (tooltip != null) {
            final var guiRenderState = ((GameRendererAccessor) mc.gameRenderer).xsm$gameRenderState().guiRenderState;
            guiRenderState.nextStratum();
            guiGraphics.tooltip(mc.font, tooltip,
                    widget.getPendingTooltipX(), widget.getPendingTooltipY(),
                    net.minecraft.client.gui.screens.inventory.tooltip.DefaultTooltipPositioner.INSTANCE,
                    null);
        }
    }

}
