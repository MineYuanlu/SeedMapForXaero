package bid.yuanlu.seedmap4xaero.client.cache;

import java.util.LinkedHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.TextureFormat;

import bid.yuanlu.seedmap4xaero.client.nativeapi.Xsm;
import xaero.lib.client.graphics.GpuTextureAndView;

public class CellCache {

    private static final Logger LOGGER = LoggerFactory.getLogger("seedmap4xaero/CellCache");

    private static final long TTL_TICK = 100;
    public static final int TEXTURE_SIDE = 64;

    private static long lastCleanTick = 0;

    private static final CellTTLCache CACHES[] = new CellTTLCache[5];
    static {
        for (int i = 0; i < CACHES.length; i++) {
            CACHES[i] = new CellTTLCache();
        }
    }

    private static final @NotNull CellTTLCache getCacheByScale(int scale) {
        return switch (scale) {
            case 1 -> CACHES[0];
            case 4 -> CACHES[1];
            case 16 -> CACHES[2];
            case 64 -> CACHES[3];
            case 256 -> CACHES[4];
            default -> throw new IllegalArgumentException("Invalid scale: " + scale);
        };
    }

    /**
     * 获取或请求瓦片 GPU 纹理。
     *
     * <p>
     * 渲染线程调用。若缓存中已有 GPU 纹理则直接返回；只有 CPU 像素则立即上传后返回；
     * 未命中则提交异步生成任务并返回 {@code null}（调用方应使用占位纹理）。
     * </p>
     *
     * @return 就绪的 GPU 纹理，或 {@code null}
     */
    public static @Nullable GpuTextureAndView getOrRequest(CellKey key) {
        var data = getCacheByScale(key.scale).computeIfAbsent(key, CellData::new);
        data.lastPrimaryTick = CacheHelper.currentTick;
        return data.getGpuTex();
    }

    /** 取消所有本帧未被访问的 pending 任务 */
    public static void cancelStalePending() {
        for (final var cache : CACHES)
            cache.cancelStalePending();
    }

    /** 所有scale统一做cleanByTTL。在computeIfAbsent之前调用，避免mapping function内修改map导致CME。 */
    public static void cleanByTTL() {
        if (lastCleanTick == CacheHelper.currentTick)
            return;
        lastCleanTick = CacheHelper.currentTick;
        for (final var cache : CACHES)
            cache.cleanByTTL();
    }

    /**
     * 窥视 GPU 纹理（不触发生成）。
     *
     * <p>
     * 用于跨 scale fallback 渲染，仅返回已就绪的纹理。
     * 若只有 CPU 像素则上传后再返回。
     * </p>
     *
     * @return 就绪的 GPU 纹理，或 {@code null}
     */
    public static @Nullable GpuTextureAndView peekGpuTexture(CellKey key) {
        final var data = getCacheByScale(key.scale).get(key);
        if (data == null)
            return null;
        return data.getGpuTex();
    }

    /** 判断某一级缩放是否有任何缓存 */
    public static boolean hasScaleCache(int scale) {
        return !getCacheByScale(scale).isEmpty();
    }

    public static void clear() {
        for (final var cache : CACHES)
            cache.clear();
    }

    private static volatile @Nullable GpuTextureAndView placeholderTexture;

    public static @NotNull GpuTextureAndView getPlaceholderTexture() {
        GpuTextureAndView p = placeholderTexture;
        if (p != null)
            return p;
        try {
            var dev = RenderSystem.getDevice();
            NativeImage img = new NativeImage(NativeImage.Format.RGBA, 1, 1, false);
            img.setPixelABGR(0, 0, 0xFF808080);
            GpuTexture gpuTex = dev.createTexture(
                    "xsm_placeholder", 1, TextureFormat.RGBA8, 1, 1, 1, 1);
            var encoder = dev.createCommandEncoder();
            encoder.writeToTexture(gpuTex, img);
            img.close();
            var view = dev.createTextureView(gpuTex);
            placeholderTexture = new GpuTextureAndView(gpuTex, view);
            return placeholderTexture;
        } catch (Exception e) {
            LOGGER.error("Failed to create placeholder texture", e);
            throw e;
        }
    }

    private static GpuTextureAndView uploadTexture(CellData data) {
        NativeImage img = new NativeImage(NativeImage.Format.RGBA, TEXTURE_SIDE, TEXTURE_SIDE, false);
        for (int i = 0; i < data.pixels.length; i++) {
            img.setPixelABGR(i % TEXTURE_SIDE, i / TEXTURE_SIDE, data.pixels[i]);
        }
        data.pixels = null; // 释放 CPU 像素数据

        GpuTexture gpuTex = RenderSystem.getDevice().createTexture(
                "xsm",
                15,
                TextureFormat.RGBA8,
                TEXTURE_SIDE, TEXTURE_SIDE,
                1, 1);
        var encoder = RenderSystem.getDevice().createCommandEncoder();
        encoder.writeToTexture(gpuTex, img);
        img.close();

        return data.gpuTex = new GpuTextureAndView(gpuTex, RenderSystem.getDevice().createTextureView(gpuTex));
    }

    private static final class CellTTLCache extends LinkedHashMap<CellKey, CellData> {
        private CellTTLCache() {
            super(1024, 0.75f, true);
        }

        /** 取消本帧未访问的 pending 条目并立即从缓存移除 */
        private void cancelStalePending() {
            if (this.isEmpty())
                return;
            var it = this.entrySet().iterator();
            while (it.hasNext()) {
                var entry = it.next();
                CellData data = entry.getValue();
                if (data.lastPrimaryTick != CacheHelper.currentTick && data.isPending()) {
                    data.cancelled = true;
                    it.remove();
                }
            }
        }

        /** 根据当前时间tick清除过期缓存 */
        private void cleanByTTL() {
            if (isEmpty())
                return;
            final var it = this.entrySet().iterator();
            while (it.hasNext()) {
                final var v = it.next().getValue();
                if (CacheHelper.currentTick > v.lastAccessTick + TTL_TICK) {
                    it.remove();
                } else {
                    break;
                }
            }
        }
    }

    public record CellKey(int scale, int cellX, int cellZ) {
        public int worldX() {
            return cellX * 64 * scale;
        }

        public int worldZ() {
            return cellZ * 64 * scale;
        }

        /** 该 cell 覆盖的方块边长。 */
        public int blockSize() {
            return 64 * scale;
        }
    }

    private static final class CellData {
        private static final int absY = 63;

        volatile int[] pixels;
        GpuTextureAndView gpuTex;
        long lastAccessTick;
        long lastPrimaryTick;
        volatile boolean failed;
        volatile boolean cancelled;

        public boolean isPending() {
            return !this.cancelled && this.gpuTex == null && this.pixels == null && !this.failed;
        }

        public @Nullable GpuTextureAndView getGpuTex() {
            this.lastAccessTick = CacheHelper.currentTick;

            if (this.gpuTex != null) {
                return this.gpuTex;
            }

            if (this.pixels != null) {
                return uploadTexture(this);
            }

            return null;
        }

        private CellData(CellKey key) {
            final int fScale = key.scale();
            final int fWorldX = key.worldX();
            final int fWorldZ = key.worldZ();
            CacheHelper.CACHE_WORKER.execute(() -> {
                if (this.cancelled)
                    return;
                try {
                    int[] result = Xsm.genCellImg(fScale, fWorldX, fWorldZ, absY, true);
                    if (this.cancelled)
                        return;
                    if (result == null) {
                        this.failed = true;
                    } else {
                        this.pixels = result;
                    }
                } catch (Exception ex) {
                    if (!this.cancelled) {
                        LOGGER.error("genCellImg failed for {}", key, ex);
                        this.failed = true;
                    }
                }
            });
        }
    }
}
