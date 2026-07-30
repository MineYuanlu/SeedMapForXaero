package bid.yuanlu.seedmap4xaero.client.mixin;

import java.util.concurrent.atomic.AtomicBoolean;

import com.mojang.blaze3d.vertex.BufferBuilder;

import bid.yuanlu.seedmap4xaero.client.accessor.SeedMapToggleAccessor;
import bid.yuanlu.seedmap4xaero.client.cache.CacheHelper;
import bid.yuanlu.seedmap4xaero.client.cache.CellCache;
import bid.yuanlu.seedmap4xaero.client.configs.ServerConfig;
import bid.yuanlu.seedmap4xaero.client.nativeapi.Xsm;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.joml.Matrix4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import xaero.lib.client.graphics.GpuTextureAndView;
import xaero.map.MapProcessor;
import xaero.map.WorldMap;
import xaero.map.graphics.CustomRenderTypes;
import xaero.map.graphics.MapRenderHelper;
import xaero.map.graphics.renderer.multitexture.MultiTextureRenderTypeRenderer;
import xaero.map.gui.GuiMap;
import xaero.map.region.LeveledRegion;
import xaero.map.region.MapRegion;
import xaero.map.region.MapTileChunk;
import xaero.map.region.texture.RegionTexture;

/**
 * Mixin into Xaero World Map's {@code GuiMap.extractRenderState}，
 * 在 Xaero 自身绘制之后注入种子地图瓦片的叠加渲染。
 *
 * <p>
 * 由 {@link #xsm$scaleForUserScale} 根据 userScale 自动选择 cell scale（1,4,16,64,256）。
 * 遍历可见 Xaero LeveledRegion，通过 CellCache 获取种子数据，用 3 级决策树检测探索状态。
 * </p>
 */
@Mixin(GuiMap.class)
public class SeedMapMixin {

    private static final Logger LOGGER = LoggerFactory.getLogger("seedmap4xaero/SeedMapMixin");
    private static final AtomicBoolean loggedInjection = new AtomicBoolean(false);

    @Shadow
    private double cameraX;

    @Shadow
    private double cameraZ;

    @Shadow
    private double userScale;

    @Shadow
    private double scale;

    @Shadow
    private MapProcessor mapProcessor;

    @Shadow
    private int mouseBlockPosX;

    @Shadow
    private int mouseBlockPosY;

    @Shadow
    private int mouseBlockPosZ;

    @Unique
    private int xsm$debugTileX;

    @Unique
    private int xsm$debugTileZ;

    @Unique
    private int xsm$debugScale;

    @Unique
    private String xsm$debugDecision;

    /**
     * 把 Xaero 的 userScale（用户缩放比）映射到种子地图使用的逻辑 scale。
     * 支持 1,4,16,64,256（256 仅在主世界），逐级差 4×。
     * <b>此 scale 与 Xaero 的 {@code this.scale} 不同</b>：Xaero 的 scale 是屏幕空间到
     * 世界空间的变换因子，用于鼠标/边界计算；这里只是种子地图 tile 粒度的开关。
     */
    @Unique
    private static int xsm$scaleForUserScale(double userScale, int dim) {
        if (userScale >= 0.5)
            return 1;
        if (userScale >= 0.125)
            return 4;
        if (userScale >= 0.03125)
            return 16;
        if (userScale >= 0.0078125)
            return 64;
        return dim == 0 ? 256 : 64;
    }

    /**
     * 对应 Xaero GuiMap.extractRenderState 中 textureLevel 的计算逻辑。
     * textureLevel 决定从 Xaero 的哪个 LOD 层级读取纹理数据：
     * <ul>
     * <li>0 (userScale ≥ 1.0)： 最精细，1 像素 = 1 方块</li>
     * <li>1 (userScale ∈ [0.5, 1.0))：1 像素 = 2 方块</li>
     * <li>2 (userScale ∈ [0.25, 0.5))：1 像素 = 4 方块</li>
     * <li>3 (userScale ∈ [0, 0.25)：最粗，1 像素 = 8 方块</li>
     * </ul>
     * 公式：reversedScale=1/userScale，textureLevel = min(floor(log2(reversedScale)),
     * 3)
     */
    @Unique
    private static int xsm$textureLevelForScale(double userScale) {
        if (userScale >= 1.0)
            return 0;
        double reversedScale = 1.0 / userScale;
        double log2 = Math.log(reversedScale) / Math.log(2.0);
        return Math.min((int) Math.floor(log2), 3);
    }

    @Inject(method = "init", at = @At("RETURN"))
    private void xsm$onGuiMapInit(CallbackInfo ci) {
        if (this.mapProcessor != null) {
            ServerConfig.activate(this.mapProcessor);
        }
    }

    /**
     * 在所有渲染工作之前, 处理缓存、C侧切换
     */
    @Inject(method = "extractRenderState", at = @At("HEAD"))
    private void tickWorldInfo(GuiGraphicsExtractor guiGraphics, int scaledMouseX, int scaledMouseY,
            float partialTicks, CallbackInfo ci) {
        final var toggle = ((SeedMapToggleAccessor) this);

        final Long seed = ServerConfig.resolveSeed();
        if (seed == null) {
            toggle.xsm$setSeedMapLoadedWorldInfo(false);
            return;
        }
        final int dim = ServerConfig.resolveDimId();
        if (dim == Integer.MIN_VALUE) {
            toggle.xsm$setSeedMapLoadedWorldInfo(false);
            return;
        }

        Xsm.setWorld(seed, dim);
        var wc = ServerConfig.getActiveWorldConfig();
        if (wc != null) {
            Xsm.setBiomeDisabled(wc.getDisabledBiomes());
        }
        CacheHelper.setWorld(seed, dim);
        CacheHelper.tick();
        toggle.xsm$setSeedMapLoadedWorldInfo(true);
    }

    /**
     * 生物群系渲染
     */
    @Inject(method = "extractRenderState", at = @At(value = "INVOKE", target = "Lxaero/map/graphics/renderer/multitexture/MultiTextureRenderTypeRendererProvider;draw(Lxaero/map/graphics/renderer/multitexture/MultiTextureRenderTypeRenderer;)V", ordinal = 1, shift = At.Shift.AFTER))
    private void renderSeedMapTiles(GuiGraphicsExtractor guiGraphics, int scaledMouseX, int scaledMouseY,
            float partialTicks, CallbackInfo ci) {
        if (!((SeedMapToggleAccessor) this).xsm$isSeedMapEnabled())
            return;
        if (this.mapProcessor == null || !this.mapProcessor.isMapWorldUsable())
            return;

        if (loggedInjection.compareAndSet(false, true)) {
            LOGGER.info("SeedMapMixin injected");
        }

        final int dim = ServerConfig.resolveDimId();
        final int curScale = xsm$scaleForUserScale(this.userScale, dim);
        final int blockSize = 64 * curScale;
        this.xsm$debugScale = curScale;
        this.xsm$debugDecision = null;

        final Minecraft mc = Minecraft.getInstance();
        final int windowW = mc.getWindow().getWidth();
        final int windowH = mc.getWindow().getHeight();
        final double leftBorder = this.cameraX - (double) (windowW / 2) / this.scale;
        final double rightBorder = leftBorder + (double) windowW / this.scale;
        final double topBorder = this.cameraZ - (double) (windowH / 2) / this.scale;
        final double bottomBorder = topBorder + (double) windowH / this.scale;

        this.xsm$debugTileX = Math.floorDiv(this.mouseBlockPosX, blockSize);
        this.xsm$debugTileZ = Math.floorDiv(this.mouseBlockPosZ, blockSize);

        final int caveLayer = this.mapProcessor.getCurrentCaveLayer();
        final double flooredCameraX = Math.floor(this.cameraX);
        final double flooredCameraZ = Math.floor(this.cameraZ);

        final var rendererProvider = this.mapProcessor.getMultiTextureRenderTypeRenderers();
        if (rendererProvider == null)
            return;
        final var renderer = rendererProvider.getRenderer(CustomRenderTypes.MAP);
        if (renderer == null)
            return;
        final var matrix = WorldMap.worldMapClientOnly.getMapScreenPoseStack().last().pose();

        xsm$fillXwmRegion(dim, leftBorder, rightBorder, topBorder, bottomBorder,
                matrix, renderer, flooredCameraX, flooredCameraZ, caveLayer);

        CellCache.cancelStalePending(); // 取消本帧不可见的 pending 任务
        CellCache.cleanByTTL(); // 清理过期的 CellCache 数据

        rendererProvider.draw(renderer);

        // debug HUD
        int guiWidth = mc.getWindow().getGuiScaledWidth();
        String line1 = "SeedMap scale=" + curScale + " | 鼠标方块: " + mouseBlockPosX + " " + mouseBlockPosY + " "
                + mouseBlockPosZ;
        MapRenderHelper.drawCenteredStringWithBackground(guiGraphics, mc.font, line1, guiWidth / 2, 40, -1, 0.0F, 0.0F,
                0.0F, 0.4F);
        String decision = this.xsm$debugDecision;
        if (decision != null) {
            MapRenderHelper.drawCenteredStringWithBackground(guiGraphics, mc.font, "fillGaps: " + decision,
                    guiWidth / 2, 56, -1, 0.0F, 0.0F, 0.0F, 0.4F);
        }
    }

    /**
     * 绘制cell纹理，带 superScale/subScale 降级。
     * <p>
     * 1. getOrRequest curScale → 命中直接绘制
     * 2. peek superScale (×4) → 全区域拉伸覆盖
     * 3. peek subScale (÷4) → 单个高精度覆盖
     * </p>
     */
    @Unique
    private boolean xsm$renderCellTexture(CellCache.CellKey key, Matrix4f matrix,
            MultiTextureRenderTypeRenderer renderer, double cameraX, double cameraZ) {
        GpuTextureAndView tex = CellCache.getOrRequest(key);
        if (tex != null) {
            drawQuad(tex, matrix, renderer,
                    (float) (key.worldX() - cameraX),
                    (float) (key.worldZ() - cameraZ),
                    key.blockSize(), key.blockSize(), 0, 1, 0, 1);
            return true;
        }

        int blockSize = key.blockSize();
        boolean drew = false;

        // superScale (×4): full coverage base
        if (key.scale() < 256) {
            int superScale = key.scale() * 4;
            if (CellCache.hasScaleCache(superScale)) {
                int superCX = Math.floorDiv(key.cellX(), 4);
                int superCZ = Math.floorDiv(key.cellZ(), 4);
                var superKey = new CellCache.CellKey(superScale, superCX, superCZ);
                GpuTextureAndView superTex = CellCache.peekGpuTexture(superKey);
                if (superTex != null) {
                    int sbSize = 64 * superScale;
                    float u0 = (float) (key.worldX() - superKey.worldX()) / sbSize;
                    float u1 = u0 + (float) blockSize / sbSize;
                    float v0 = (float) (key.worldZ() - superKey.worldZ()) / sbSize;
                    float v1 = v0 + (float) blockSize / sbSize;
                    drawQuad(superTex, matrix, renderer,
                            (float) (key.worldX() - cameraX),
                            (float) (key.worldZ() - cameraZ),
                            blockSize, blockSize, u0, u1, v0, v1);
                    drew = true;
                }
            }
        }

        // subScale (÷4): higher detail overlay
        if (key.scale() > 1) {
            int subScale = key.scale() / 4;
            if (CellCache.hasScaleCache(subScale)) {
                int subBlockSize = 64 * subScale;
                int perDim = 4;
                for (int i = 0; i < perDim; i++) {
                    for (int j = 0; j < perDim; j++) {
                        int subCX = key.cellX() * perDim + i;
                        int subCZ = key.cellZ() * perDim + j;
                        var subKey = new CellCache.CellKey(subScale, subCX, subCZ);
                        GpuTextureAndView subTex = CellCache.peekGpuTexture(subKey);
                        if (subTex != null) {
                            drawQuad(subTex, matrix, renderer,
                                    (float) (subKey.worldX() - cameraX),
                                    (float) (subKey.worldZ() - cameraZ),
                                    subBlockSize, subBlockSize, 0, 1, 0, 1);
                            drew = true;
                        }
                    }
                }
            }
        }

        return drew;
    }

    /**
     * 填充Xaero World Map 区域的主循环。
     * <p>
     * 遍历所有可见 Xaero LeveledRegion，根据是否有 Xaero 纹理数据
     * 分别走全region快速填充（fast path）或逐cell填缝（slow path）。
     * </p>
     */
    @Unique
    private void xsm$fillXwmRegion(int dim,
            double leftBorder, double rightBorder, double topBorder, double bottomBorder,
            Matrix4f matrix, MultiTextureRenderTypeRenderer renderer,
            double cameraX, double cameraZ, int caveLayer) {
        final int textureLevel = xsm$textureLevelForScale(this.userScale);
        final int cellScale = xsm$scaleForUserScale(this.userScale, dim);
        final int regBlockSize = 512 << textureLevel;
        final int cellBlockSize = 64 * cellScale;

        final int minRegX = (int) Math.floor(leftBorder) >> (9 + textureLevel);
        final int maxRegX = (int) Math.floor(rightBorder) >> (9 + textureLevel);
        final int minRegZ = (int) Math.floor(topBorder) >> (9 + textureLevel);
        final int maxRegZ = (int) Math.floor(bottomBorder) >> (9 + textureLevel);

        for (int regX = minRegX; regX <= maxRegX; regX++) {
            for (int regZ = minRegZ; regZ <= maxRegZ; regZ++) {
                final var region = this.mapProcessor.getLeveledRegion(caveLayer, regX, regZ, textureLevel);
                if (region == null || !region.hasTextures()) {
                    xsm$fillXwmLeveledRegionFull(regX, regZ, regBlockSize, cellScale,
                            matrix, renderer, cameraX, cameraZ);
                } else {
                    final int cellX0 = Math.floorDiv(regX * regBlockSize, cellBlockSize);
                    final int cellX1 = Math.floorDiv((regX + 1) * regBlockSize - 1, cellBlockSize);
                    final int cellZ0 = Math.floorDiv(regZ * regBlockSize, cellBlockSize);
                    final int cellZ1 = Math.floorDiv((regZ + 1) * regBlockSize - 1, cellBlockSize);
                    for (int cx = cellX0; cx <= cellX1; cx++) {
                        for (int cz = cellZ0; cz <= cellZ1; cz++) {
                            xsm$fillCellGaps(region, cellScale, cx, cz, textureLevel,
                                    matrix, renderer, cameraX, cameraZ, caveLayer);
                        }
                    }
                }
            }
        }
    }

    /**
     * 对于一个已经判定完全未加载的region，进行填充。
     * <p>
     * 不检查任何 sub-tile，直接用CellCache纹理覆盖。
     * </p>
     */
    @Unique
    private void xsm$fillXwmLeveledRegionFull(int regX, int regZ, int regBlockSize, int cellScale,
            Matrix4f matrix, MultiTextureRenderTypeRenderer renderer,
            double cameraX, double cameraZ) {
        final int cellBlockSize = 64 * cellScale;
        final int cellX0 = Math.floorDiv(regX * regBlockSize, cellBlockSize);
        final int cellX1 = Math.floorDiv((regX + 1) * regBlockSize - 1, cellBlockSize);
        final int cellZ0 = Math.floorDiv(regZ * regBlockSize, cellBlockSize);
        final int cellZ1 = Math.floorDiv((regZ + 1) * regBlockSize - 1, cellBlockSize);
        for (int cx = cellX0; cx <= cellX1; cx++) {
            for (int cz = cellZ0; cz <= cellZ1; cz++) {
                xsm$renderCellTexture(
                        new CellCache.CellKey(cellScale, cx, cz),
                        matrix, renderer, cameraX, cameraZ);
            }
        }
    }

    /**
     * 对有Xaero数据的region，逐cell填缝。
     * <p>
     * 包含3级决策树（L1 leaf MapRegion / L2 MapTileChunk / L3 getHeight）+ 扫描线合并。
     * </p>
     */
    @Unique
    private void xsm$fillCellGaps(LeveledRegion<?> region, int cellScale, int cellX, int cellZ, int textureLevel,
            Matrix4f matrix, MultiTextureRenderTypeRenderer renderer,
            double cameraX, double cameraZ, int caveLayer) {
        int cellBlockSize = 64 * cellScale;
        int cellWorldX = cellX * cellBlockSize;
        int cellWorldZ = cellZ * cellBlockSize;

        CellCache.CellKey key = new CellCache.CellKey(cellScale, cellX, cellZ);
        GpuTextureAndView tex = CellCache.getOrRequest(key);

        int subCount = cellBlockSize / 16;
        boolean superFallback = false;
        float superUVPerSub = 0;

        if (tex == null && cellScale < 256) {
            int superScale = cellScale * 4;
            int superCX = Math.floorDiv(cellX, 4);
            int superCZ = Math.floorDiv(cellZ, 4);
            tex = CellCache.peekGpuTexture(new CellCache.CellKey(superScale, superCX, superCZ));
            if (tex != null) {
                superFallback = true;
                superUVPerSub = 1.0f / (4 * subCount);
            }
        }

        // subScale fallback via renderCellTexture（full-cell draw，不做 fillGaps）
        if (tex == null) {
            xsm$renderCellTexture(key, matrix, renderer, cameraX, cameraZ);
        }

        if (tex == null)
            return;

        float subUV = 1.0f / subCount;
        float uvBaseU, uvBaseV, uvScale;
        if (superFallback) {
            uvBaseU = Math.floorMod(cellX, 4) / 4.0f;
            uvBaseV = Math.floorMod(cellZ, 4) / 4.0f;
            uvScale = superUVPerSub;
        } else {
            uvBaseU = uvBaseV = 0;
            uvScale = subUV;
        }

        int lastLeafRegX = Integer.MIN_VALUE;
        int lastLeafRegZ = Integer.MIN_VALUE;
        MapRegion lastLeafRegion = null;
        int lastLtX = -1;
        int lastLtZ = -1;
        RegionTexture<?> lastRtex = null;

        boolean isMouse = cellX == xsm$debugTileX && cellZ == xsm$debugTileZ
                && cellScale == xsm$debugScale;

        int drew = 0;
        int total = subCount * subCount;

        for (int sz = 0; sz < subCount; sz++) {
            int runStart = -1;
            for (int sx = 0; sx < subCount; sx++) {
                int wx = cellWorldX + sx * 16 + 8;
                int wz = cellWorldZ + sz * 16 + 8;

                // Level 1: leaf MapRegion (512 blocks)
                int leafRegX = wx >> 9;
                int leafRegZ = wz >> 9;
                if (leafRegX != lastLeafRegX || leafRegZ != lastLeafRegZ) {
                    lastLeafRegion = this.mapProcessor.getLeafMapRegion(caveLayer, leafRegX, leafRegZ, false);
                    lastLeafRegX = leafRegX;
                    lastLeafRegZ = leafRegZ;
                }

                boolean explored = false;

                if (lastLeafRegion != null && !lastLeafRegion.hasHadTerrain()) {
                    // L1: confirmed unexplored → will draw
                } else {
                    boolean skipCheck = false;
                    if (lastLeafRegion != null && lastLeafRegion.hasHadTerrain()) {
                        // Level 2: MapTileChunk (64 blocks)
                        int chunkLocalX = (wx >> 6) & 7;
                        int chunkLocalZ = (wz >> 6) & 7;
                        MapTileChunk chunk = lastLeafRegion.getChunk(chunkLocalX, chunkLocalZ);
                        if (chunk != null && !chunk.hasHadTerrain()) {
                            skipCheck = true;
                        }
                    }

                    if (!skipCheck) {
                        // Level 3: branch texture getHeight
                        // Use the already-resolved region directly (same brX/brZ as outer loop)
                        int ltX = (wx >> (6 + textureLevel)) & 7;
                        int ltZ = (wz >> (6 + textureLevel)) & 7;
                        if (ltX != lastLtX || ltZ != lastLtZ) {
                            lastRtex = region.getTexture(ltX, ltZ);
                            lastLtX = ltX;
                            lastLtZ = ltZ;
                        }
                        int lpX = (wx >> textureLevel) & 63;
                        int lpZ = (wz >> textureLevel) & 63;
                        explored = lastRtex != null && lastRtex.getHeight(lpX, lpZ) != 32767;
                    }
                }

                if (explored) {
                    if (runStart >= 0) {
                        float u0 = uvBaseU + (float) runStart * uvScale;
                        float u1 = uvBaseU + (float) sx * uvScale;
                        float v0 = uvBaseV + (float) sz * uvScale;
                        float v1 = uvBaseV + (float) (sz + 1) * uvScale;
                        float rx = (float) (cellWorldX + runStart * 16 - cameraX);
                        float rz = (float) (cellWorldZ + sz * 16 - cameraZ);
                        drawQuad(tex, matrix, renderer, rx, rz, (sx - runStart) * 16.0f, 16.0f, u0, u1, v0, v1);
                        drew += sx - runStart;
                        runStart = -1;
                    }
                } else if (runStart < 0) {
                    runStart = sx;
                }
            }
            if (runStart >= 0) {
                float u0 = uvBaseU + (float) runStart * uvScale;
                float u1 = uvBaseU + (float) subCount * uvScale;
                float v0 = uvBaseV + (float) sz * uvScale;
                float v1 = uvBaseV + (float) (sz + 1) * uvScale;
                float rx = (float) (cellWorldX + runStart * 16 - cameraX);
                float rz = (float) (cellWorldZ + sz * 16 - cameraZ);
                drawQuad(tex, matrix, renderer, rx, rz, (subCount - runStart) * 16.0f, 16.0f, u0, u1, v0, v1);
                drew += subCount - runStart;
            }
        }

        if (isMouse) {
            if (drew == 0) {
                xsm$debugDecision = "(" + cellX + "," + cellZ + ") S" + cellScale
                        + " 全部" + total + "个tile均已探索，跳过补绘";
            } else {
                xsm$debugDecision = "(" + cellX + "," + cellZ + ") S" + cellScale
                        + " 补绘" + drew + "/" + total + "个未探索tile";
            }
        }
    }

    @Unique
    private static void drawQuad(GpuTextureAndView tex, Matrix4f matrix,
            MultiTextureRenderTypeRenderer renderer,
            float x, float y, float w, float h,
            float u0, float u1, float v0, float v1) {
        BufferBuilder bb = renderer.begin(tex.view);
        bb.addVertex(matrix, x, y + h, 0.0F).setColor(-1).setUv(u0, v1);
        bb.addVertex(matrix, x + w, y + h, 0.0F).setColor(-1).setUv(u1, v1);
        bb.addVertex(matrix, x + w, y, 0.0F).setColor(-1).setUv(u1, v0);
        bb.addVertex(matrix, x, y, 0.0F).setColor(-1).setUv(u0, v0);
    }

}
