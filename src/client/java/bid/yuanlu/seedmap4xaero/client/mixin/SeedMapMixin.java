package bid.yuanlu.seedmap4xaero.client.mixin;

import java.util.concurrent.atomic.AtomicBoolean;

import com.mojang.blaze3d.vertex.BufferBuilder;

import bid.yuanlu.seedmap4xaero.client.accessor.SeedMapToggleAccessor;
import bid.yuanlu.seedmap4xaero.client.cache.CacheHelper;
import bid.yuanlu.seedmap4xaero.client.cache.CellCache;
import bid.yuanlu.seedmap4xaero.client.configs.basic.ServerConfig;
import bid.yuanlu.seedmap4xaero.client.configs.perf.PerfConfig;
import bid.yuanlu.seedmap4xaero.client.configs.structure.StructureDataConfig;
import bid.yuanlu.seedmap4xaero.client.nativeapi.Xsm;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.resources.language.I18n;
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

    /** 性能统计 HUD 开关（perf_config.json debugOverlay）。 */
    @Unique
    private static boolean xsm$debug() {
        return PerfConfig.debugOverlay();
    }

    // ─── 性能统计 (debug HUD 采样, 每 ~1s 汇总) ─────────────────
    @Unique
    private static long xsm$lastSampleNanos;
    @Unique
    private static long xsm$lastGenCount;
    @Unique
    private static long xsm$lastGenNanos;
    @Unique
    private static double xsm$statTps;
    @Unique
    private static double xsm$statAvgMs;
    /** 本 mod 渲染路径耗时 (beginFrame→endFrame, 上一帧)。 */
    @Unique
    private static long xsm$frameNanos;

    /** 每 ~1s 汇总一次生成速率与均耗时（渲染线程）。 */
    @Unique
    private static void xsm$updateStats() {
        long now = System.nanoTime();
        if (xsm$lastSampleNanos == 0) {
            xsm$lastSampleNanos = now;
            xsm$lastGenCount = CellCache.STAT_GEN_COUNT.get();
            xsm$lastGenNanos = CellCache.STAT_GEN_NANOS.get();
            return;
        }
        long dt = now - xsm$lastSampleNanos;
        if (dt < 1_000_000_000L)
            return;
        long count = CellCache.STAT_GEN_COUNT.get();
        long nanos = CellCache.STAT_GEN_NANOS.get();
        long dc = count - xsm$lastGenCount;
        long dn = nanos - xsm$lastGenNanos;
        xsm$statTps = dc * 1_000_000_000.0 / dt;
        xsm$statAvgMs = dc > 0 ? dn / (double) dc / 1_000_000.0 : 0;
        xsm$lastSampleNanos = now;
        xsm$lastGenCount = count;
        xsm$lastGenNanos = nanos;
    }

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
            StructureDataConfig.activate(this.mapProcessor);
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

        // 世界生成 MC 版本：单机固定客户端版本；多人按 per-world 配置（null=跟随客户端）
        var wc = ServerConfig.getActiveWorldConfig();
        final String version = (Minecraft.getInstance().getSingleplayerServer() != null || wc == null)
                ? null
                : wc.mcVersion();
        if (!Xsm.applyGameVersion(version)) {
            // 拒绝详情由 Xsm 首次拒绝时记录；此处静默回退客户端版本
            Xsm.applyGameVersion(null);
        }

        Xsm.setWorld(seed, dim);
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
        if (xsm$debug()) {
            this.xsm$debugScale = curScale;
            this.xsm$debugDecision = null;
        }

        final Minecraft mc = Minecraft.getInstance();
        final int windowW = mc.getWindow().getWidth();
        final int windowH = mc.getWindow().getHeight();
        final double leftBorder = this.cameraX - (double) (windowW / 2) / this.scale;
        final double rightBorder = leftBorder + (double) windowW / this.scale;
        final double topBorder = this.cameraZ - (double) (windowH / 2) / this.scale;
        final double bottomBorder = topBorder + (double) windowH / this.scale;

        if (xsm$debug()) {
            this.xsm$debugTileX = Math.floorDiv(this.mouseBlockPosX, blockSize);
            this.xsm$debugTileZ = Math.floorDiv(this.mouseBlockPosZ, blockSize);
        }

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

        CellCache.beginFrame(flooredCameraX, flooredCameraZ); // 相机 → 调度优先级; 重置本帧上传额度

        long frameStart = xsm$debug() ? System.nanoTime() : 0;

        xsm$fillXwmRegion(dim, leftBorder, rightBorder, topBorder, bottomBorder,
                matrix, renderer, flooredCameraX, flooredCameraZ, caveLayer);

        xsm$prefetchAround(dim, leftBorder, rightBorder, topBorder, bottomBorder, curScale);

        rendererProvider.draw(renderer);
        CellCache.endFrame(); // 关闭本帧被驱逐的 GPU 纹理 (draw 之后才安全)
        if (frameStart != 0)
            xsm$frameNanos = System.nanoTime() - frameStart;

        if (xsm$debug()) {
            xsm$updateStats();
            int guiWidth = mc.getWindow().getGuiScaledWidth();
            String line1 = I18n.get("xsm.debug.scale", curScale, mouseBlockPosX, mouseBlockPosY, mouseBlockPosZ);
            MapRenderHelper.drawCenteredStringWithBackground(guiGraphics, mc.font, line1, guiWidth / 2, 40, -1, 0.0F, 0.0F,
                    0.0F, 0.4F);
            String decision = this.xsm$debugDecision;
            if (decision != null) {
                MapRenderHelper.drawCenteredStringWithBackground(guiGraphics, mc.font,
                        I18n.get("xsm.debug.fill_gap", decision),
                        guiWidth / 2, 56, -1, 0.0F, 0.0F, 0.0F, 0.4F);
            }
            String perf = I18n.get("xsm.debug.perf",
                    (long) xsm$statTps, xsm$statAvgMs,
                    CellCache.pending(), CellCache.entries(), PerfConfig.cacheCapacityEntries(),
                    xsm$frameNanos / 1_000_000.0);
            MapRenderHelper.drawCenteredStringWithBackground(guiGraphics, mc.font, perf,
                    guiWidth / 2, 72, -1, 0.0F, 0.0F, 0.0F, 0.4F);
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

        // superScale (×4 起逐级上溯): 就近取已缓存的粗层做全区域覆盖
        // （背景先行 + prefetch 粗层使任意细度缺失都有放大兜底）
        for (int superScale = key.scale() * 4; superScale <= 256; superScale *= 4) {
            if (!CellCache.hasScaleCache(superScale))
                continue;
            int div = superScale / key.scale();
            int superCX = Math.floorDiv(key.cellX(), div);
            int superCZ = Math.floorDiv(key.cellZ(), div);
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
                break;
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

    // ─── Prefetch (性能配置开关, 最低优先级入队) ────────────────

    /** 上次 prefetch 的矩形 + 尺度 + 维度 + 帧号（相机/缩放不动时跳过）。 */
    @Unique
    private static int xsm$pfDim = Integer.MIN_VALUE;
    @Unique
    private static int xsm$pfScale = -1;
    @Unique
    private static int xsm$pfMinX, xsm$pfMaxX, xsm$pfMinZ, xsm$pfMaxZ;
    @Unique
    private static long xsm$pfFrame = -1;
    /** 矩形不变时的周期性补 prefetch 间隔（回填被 LRU 驱逐的瓦片）。 */
    private static final long PREFETCH_REFRESH_INTERVAL = 100;

    /**
     * 视野外圈预取（受 pending backpressure 约束）:
     * <ol>
     * <li><b>粗层背景链（BACKGROUND）</b>：从 ×4 逐级铺到维度最大 scale，每级覆盖
     * (视口 + 半视口环)——层级越高瓦片数 16× 递减（合计仅几十张），直接成为细瓦片
     * 多级 SuperScale fallback 的源；冷缓存打开地图先出全屏粗背景，
     * 细瓦片再由近及远填充</li>
     * <li><b>curScale 外扩半视口环（PREFETCH）</b>：拖拽进入时相邻 cell 已就绪</li>
     * </ol>
     * 更粗层之外的后台补充由"后台预生成"负责（PREFETCH 档）；细层（tier 4/1）不
     * 主动 prefetch（面积成本平方涨，粗层兜底已消除缺卡感知）。
     */
    @Unique
    private void xsm$prefetchAround(int dim, double leftBorder, double rightBorder,
            double topBorder, double bottomBorder, int cellScale) {
        if (!PerfConfig.prefetchEnabled())
            return;
        int cellBlockSize = 64 * cellScale;
        int minX = Math.floorDiv((int) Math.floor(leftBorder), cellBlockSize);
        int maxX = Math.floorDiv((int) Math.floor(rightBorder) - 1, cellBlockSize);
        int minZ = Math.floorDiv((int) Math.floor(topBorder), cellBlockSize);
        int maxZ = Math.floorDiv((int) Math.floor(bottomBorder) - 1, cellBlockSize);
        int ringX = (maxX - minX + 1) / 2;
        int ringZ = (maxZ - minZ + 1) / 2;
        int exMinX = minX - ringX, exMaxX = maxX + ringX;
        int exMinZ = minZ - ringZ, exMaxZ = maxZ + ringZ;

        long frame = CacheHelper.currentTick();
        if (dim == xsm$pfDim && cellScale == xsm$pfScale
                && minX == xsm$pfMinX && maxX == xsm$pfMaxX
                && minZ == xsm$pfMinZ && maxZ == xsm$pfMaxZ
                && frame - xsm$pfFrame < PREFETCH_REFRESH_INTERVAL)
            return;
        xsm$pfDim = dim;
        xsm$pfScale = cellScale;
        xsm$pfMinX = minX;
        xsm$pfMaxX = maxX;
        xsm$pfMinZ = minZ;
        xsm$pfMaxZ = maxZ;
        xsm$pfFrame = frame;

        int exWorldMinX = (int) Math.floor(leftBorder) - ringX * cellBlockSize;
        int exWorldMaxX = (int) Math.floor(rightBorder) - 1 + ringX * cellBlockSize;
        int exWorldMinZ = (int) Math.floor(topBorder) - ringZ * cellBlockSize;
        int exWorldMaxZ = (int) Math.floor(bottomBorder) - 1 + ringZ * cellBlockSize;

        // (a) 粗层背景链: ×4 逐级铺到维度最大 scale（BACKGROUND 档，fallback 源）
        int maxScale = dim == 0 ? 256 : 64;
        for (int s = cellScale * 4; s <= maxScale; s *= 4) {
            int sb = 64 * s;
            int sMinX = Math.floorDiv(exWorldMinX, sb);
            int sMaxX = Math.floorDiv(exWorldMaxX, sb);
            int sMinZ = Math.floorDiv(exWorldMinZ, sb);
            int sMaxZ = Math.floorDiv(exWorldMaxZ, sb);
            for (int cx = sMinX; cx <= sMaxX; cx++)
                for (int cz = sMinZ; cz <= sMaxZ; cz++)
                    CellCache.prefetch(new CellCache.CellKey(s, cx, cz),
                            CellCache.KIND_BACKGROUND);
        }

        // (b) curScale 扩展矩形环（PREFETCH 档，不含视口本身——可见 cell 走正常请求）
        for (int cx = exMinX; cx <= exMaxX; cx++)
            for (int cz = exMinZ; cz <= exMaxZ; cz++) {
                if (cx >= minX && cx <= maxX && cz >= minZ && cz <= maxZ)
                    continue;
                CellCache.prefetch(new CellCache.CellKey(cellScale, cx, cz),
                        CellCache.KIND_PREFETCH);
            }
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
        int superDiv = 1;

        if (tex == null) {
            // superScale (×4 起逐级上溯): 就近取已缓存粗层做全 cell 覆盖
            for (int superScale = cellScale * 4; superScale <= 256; superScale *= 4) {
                int div = superScale / cellScale;
                int superCX = Math.floorDiv(cellX, div);
                int superCZ = Math.floorDiv(cellZ, div);
                tex = CellCache.peekGpuTexture(new CellCache.CellKey(superScale, superCX, superCZ));
                if (tex != null) {
                    superFallback = true;
                    superDiv = div;
                    superUVPerSub = 1.0f / (subCount * div);
                    break;
                }
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
            uvBaseU = Math.floorMod(cellX, superDiv) / (float) superDiv;
            uvBaseV = Math.floorMod(cellZ, superDiv) / (float) superDiv;
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

        boolean isMouse = xsm$debug() && cellX == xsm$debugTileX && cellZ == xsm$debugTileZ
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

        if (isMouse && xsm$debug()) {
            if (drew == 0) {
                xsm$debugDecision = I18n.get("xsm.debug.all_explored", cellX, cellZ, cellScale, total);
            } else {
                xsm$debugDecision = I18n.get("xsm.debug.filled_tiles", cellX, cellZ, cellScale, drew, total);
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
