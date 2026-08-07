package bid.yuanlu.seedmap4xaero.client.render;

import org.joml.Matrix3x2f;

import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.state.gui.BlitRenderState;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;

import bid.yuanlu.seedmap4xaero.client.accessor.GameRendererAccessor;
import bid.yuanlu.seedmap4xaero.client.structure.HighlightedStructures;
import bid.yuanlu.seedmap4xaero.client.structure.StructureType;

/**
 * 结构高亮的 HUD 图标: 在屏幕 GUI 阶段 (InGameHud 末尾) 绘制永远可见的半透明
 * 结构图标 (复用 structures.png), 锚定在玩家视线高度 (水平位置恒定), 下方显示距离数字。
 * <p>
 * 与世界光柱 ({@link HighlightWorldRenderer}) 并存互不干扰: 光柱贯穿建筑高度标明
 * "结构在哪", 图标让玩家不看向光柱方向时也能察觉高亮存在。只画水平距离 (结构无 Y),
 * 目标在相机背后 (ndc.z&gt;1) 或超 {@link #MAX_RENDER_DISTANCE} 时隐藏; 打开地图时
 * GuiMap.shouldSkipWorldRender 世界不渲染但 HUD 仍触发, 地图覆盖其上, 无冲突。
 */
public final class HighlightHudRenderer {

    /** 图标边长 (GUI px)。 */
    static final int ICON_SIZE = 16;
    /** 半透明 (50% alpha) 白色。 */
    static final int ICON_ALPHA = 0x80FFFFFF;
    /** 精灵图单格宽度 (px): structures.png 每图标 20px, 与地图 overlay 一致。 */
    static final int SOURCE_CELL = 20;
    /** 距离上限 (水平格数): 超出隐藏, 避免远处图标群聚遮挡屏幕。 */
    static final double MAX_RENDER_DISTANCE = 512.0;

    private static boolean registered;

    private HighlightHudRenderer() {
    }

    public static void register() {
        if (registered)
            return;
        registered = true;
        HudElementRegistry.addLast(
                Identifier.fromNamespaceAndPath("seed-map-for-xaero", "structure_highlights"),
                (graphics, deltaTracker) -> render(graphics));
    }

    /** 每帧在 HUD 末尾绘制全部可见高亮图标 + 距离文字。 */
    private static void render(GuiGraphicsExtractor graphics) {
        final Minecraft mc = Minecraft.getInstance();
        final var level = mc.level;
        if (level == null || mc.gameRenderer == null)
            return;
        final var highlights = HighlightedStructures.all();
        if (highlights.isEmpty())
            return;

        final var dim = level.dimension();
        final var cam = mc.gameRenderer.getMainCamera().position();
        final int guiW = graphics.guiWidth();
        final int guiH = graphics.guiHeight();

        final var guiRenderState = ((GameRendererAccessor) mc.gameRenderer).xsm$gameRenderState().guiRenderState;
        // 新开一层 stratum: addBlitToCurrentLayer 只挂到 current, 保证本层图标在其它
        // HUD 元素之上 (与 StructureOverlayMixin 同模式)。
        guiRenderState.nextStratum();
        final var tex = mc.getTextureManager().getTexture(StructureType.STRUCTURES_TEXTURE);
        final GpuTextureView texView = tex.getTextureView();
        final GpuSampler sampler = tex.getSampler();
        final TextureSetup setup = TextureSetup.singleTexture(texView, sampler);
        final Matrix3x2f basePose = new Matrix3x2f(graphics.pose());
        final int half = ICON_SIZE / 2;

        for (var key : highlights) {
            if (!dim.equals(key.dim()))
                continue;
            final double bx = key.blockX() + 0.5;
            final double bz = key.blockZ() + 0.5;
            final double dist = Math.hypot(bx - cam.x, bz - cam.z);
            if (dist > MAX_RENDER_DISTANCE)
                continue;
            final float[] s = ndcToScreen(
                    mc.gameRenderer.projectPointToScreen(new Vec3(bx, cam.y, bz)),
                    guiW, guiH);
            if (s == null)
                continue;
            final float sx = s[0];
            final float sy = s[1];

            final float[] uv = spriteUv(key.type(), key.variant());
            // 浮点屏幕坐标经 pose 承载 → 亚像素平滑移动 (整数截断会让移动时每帧跳格)
            final Matrix3x2f pose = new Matrix3x2f(basePose).translate(sx, sy);
            guiRenderState.addBlitToCurrentLayer(new BlitRenderState(RenderPipelines.GUI_TEXTURED,
                    setup, pose, -half, -half, half, half, uv[0], uv[1], 0.0F, 1.0F,
                    ICON_ALPHA, null, null));

            // 文字 API 只收 int, 就近取整 (图标平滑, 文字偶尔 1px 漂移可接受)
            final int tx = Math.round(sx);
            final int ty = Math.round(sy);
            final String str = I18n.get("xsm.highlight.distance", String.format("%.0f", dist));
            graphics.text(mc.font, str, tx - mc.font.width(str) / 2, ty + half + 2,
                    0xFFFFFFFF, true);
        }
    }

    /**
     * NDC (相机前方的点 z∈[0,1]) → 屏幕坐标 (亚像素浮点); 目标在相机背后返回 null。
     */
    static float[] ndcToScreen(Vec3 ndc, int guiW, int guiH) {
        if (ndc.z > 1.0)
            return null;
        float sx = (float) ((ndc.x + 1.0) * 0.5 * guiW);
        float sy = (float) ((1.0 - ndc.y) * 0.5 * guiH);
        return new float[] { sx, sy };
    }

    /** 结构类型 + 变种 → 精灵图归一化 UV 区间 (源贴图每格 {@value #SOURCE_CELL}px)。 */
    static float[] spriteUv(StructureType type, int variant) {
        final int idx = type.getSpriteIndex(variant);
        final float u0 = (float) (idx * SOURCE_CELL) / StructureType.SPRITESHEET_WIDTH;
        final float u1 = u0 + (float) SOURCE_CELL / StructureType.SPRITESHEET_WIDTH;
        return new float[] { u0, u1 };
    }
}
