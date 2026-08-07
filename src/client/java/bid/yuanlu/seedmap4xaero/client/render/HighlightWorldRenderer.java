package bid.yuanlu.seedmap4xaero.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;

import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.blockentity.BeaconRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.phys.Vec3;

import bid.yuanlu.seedmap4xaero.client.accessor.GameRendererAccessor;
import bid.yuanlu.seedmap4xaero.client.structure.HighlightedStructures;

/**
 * 世界内高亮: 会话级高亮的结构以自绘信标光柱在 3D 世界中显示。
 * <p>
 * 不用 {@link BeaconRenderer#submitBeaconBeam} 原版方法: 它的贴图滚动动画偏移量
 * 与光束高度 (y1) 强耦合, 而我们用贯穿建筑高度的整根光柱 (y1 可达 300+), 原版参数
 * 语义会放大出高频闪烁/拉伸。这里用 {@code submitCustomGeometry}
 * 直接画一个方形光柱 (4 个竖面), UV 与旋转完全自控。
 * <p>
 * 只在玩家所在维度与高亮维度一致时渲染 (跨维度高亮无意义); 地图打开时
 * GuiMap.shouldSkipWorldRender=true 世界不渲染, 光柱自动隐藏。
 */
public final class HighlightWorldRenderer {

    /** 光柱半径 (半宽)。 */
    private static final float RADIUS = 0.5f;
    /** 贴图纵向每 16 格重复一次。 */
    private static final float TILE_BLOCKS = 16.0f;
    /** 满亮度 (block light 15<<4 | sky light 15<<20)。 */
    private static final int FULL_BRIGHT = 15728880;

    private static boolean registered;

    private HighlightWorldRenderer() {
    }

    public static void register() {
        if (registered)
            return;
        registered = true;
        LevelRenderEvents.COLLECT_SUBMITS.register(context -> {
            final Minecraft mc = Minecraft.getInstance();
            final var level = mc.level;
            if (level == null)
                return;
            final var highlights = HighlightedStructures.all();
            if (highlights.isEmpty())
                return;
            final var dim = level.dimension();
            final Vec3 cam = ((GameRendererAccessor) mc.gameRenderer).xsm$mainCamera().position();
            final var pose = context.poseStack();
            final var collector = context.submitNodeCollector();
            final int y0 = level.getMinY();
            final int y1 = level.getMaxY();
            final float tiles = (y1 - y0) / TILE_BLOCKS;
            // 缓慢自转: 1°/tick, 一整圈 6 秒; partial tick 插值避免 20Hz 整数步进抖动
            final float partialTicks = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
            final float rotation = (level.getGameTime() + partialTicks) % 360.0f;
            final RenderType renderType = RenderTypes.beaconBeam(BeaconRenderer.BEAM_LOCATION, false);
            for (var key : highlights) {
                if (!dim.equals(key.dim()))
                    continue;
                pose.pushPose();
                // pose 原点 = 相机; 平移到结构锚点方块中心 (+0.5 与 HUD 图标投影一致),
                // 减去 camY 使光柱落在绝对世界高度
                pose.translate(key.blockX() + 0.5 - cam.x, -cam.y, key.blockZ() + 0.5 - cam.z);
                pose.mulPose(Axis.YP.rotationDegrees(rotation));
                collector.submitCustomGeometry(pose, renderType, (p, buf) ->
                        xsmRenderColumn(buf, p, y0, y1, tiles));
                pose.popPose();
            }
        });
    }

    /** 4 个面向外的竖面构成方形光柱 (围绕原点-Y 轴)。 */
    private static void xsmRenderColumn(VertexConsumer buf, PoseStack.Pose pose,
            int y0, int y1, float tiles) {
        xsmRenderFace(buf, pose, -RADIUS, +RADIUS, +RADIUS, +RADIUS, y0, y1, tiles); // +Z
        xsmRenderFace(buf, pose, +RADIUS, -RADIUS, -RADIUS, -RADIUS, y0, y1, tiles); // -Z
        xsmRenderFace(buf, pose, +RADIUS, -RADIUS, +RADIUS, +RADIUS, y0, y1, tiles); // +X
        xsmRenderFace(buf, pose, -RADIUS, +RADIUS, -RADIUS, -RADIUS, y0, y1, tiles); // -X
    }

    /** 一个竖面: (x0,z0)→(x1,z1) 的水平线段纵向拉伸 y0..y1, 双面绘制。 */
    private static void xsmRenderFace(VertexConsumer buf, PoseStack.Pose pose,
            float x0, float z0, float x1, float z1, int y0, int y1, float tiles) {
        // 正面
        xsmRenderVertex(buf, pose, x0, y0, z0, 0.0F, 0.0F);
        xsmRenderVertex(buf, pose, x1, y0, z1, 1.0F, 0.0F);
        xsmRenderVertex(buf, pose, x1, y1, z1, 1.0F, tiles);
        xsmRenderVertex(buf, pose, x0, y1, z0, 0.0F, tiles);
        // 反面 (反转绕序): 兼容任意 cull 模式, 保证所有视角都可见
        xsmRenderVertex(buf, pose, x0, y1, z0, 0.0F, tiles);
        xsmRenderVertex(buf, pose, x1, y1, z1, 1.0F, tiles);
        xsmRenderVertex(buf, pose, x1, y0, z1, 1.0F, 0.0F);
        xsmRenderVertex(buf, pose, x0, y0, z0, 0.0F, 0.0F);
    }

    private static void xsmRenderVertex(VertexConsumer buf, PoseStack.Pose pose,
            float x, float y, float z, float u, float v) {
        buf.addVertex(pose, x, y, z)
                .setColor(0xFFFFFFFF)
                .setUv(u, v)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(FULL_BRIGHT)
                .setNormal(pose, 0.0F, 1.0F, 0.0F);
    }
}
