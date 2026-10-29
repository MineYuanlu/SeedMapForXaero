package bid.yuanlu.seedmap4xaero.client.cache;

import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import bid.yuanlu.seedmap4xaero.client.compat.CompatTextures;
import bid.yuanlu.seedmap4xaero.client.configs.perf.PerfConfig;
import bid.yuanlu.seedmap4xaero.client.nativeapi.Xsm;
import xaero.lib.client.graphics.GpuTextureAndView;

/**
 * 瓦片缓存：5 层 LOD（scale 1/4/16/64/256），每层 access-order LRU，
 * <b>全局条目预算</b>（{@code PerfConfig#cacheCapacityEntries}，1 条 ≈ 16KB
 * CPU 像素 ≈ 16KB GPU 纹理）超限时跨层驱逐最久未访问条目。
 * <p>
 * 与旧 TTL 方案的区别：同 seed+dim 会话内缓存常驻（关闭地图不再过期），
 * 内存上限恒定；拖拽尖峰由 pending backpressure（{@link #MAX_PENDING}）封顶。
 * <p>
 * 生成请求进入 {@link GenScheduler}（粗层优先/近相机优先/prefetch 最低）。
 * 渲染线程 API：{@link #updateCamera} / {@link #beginFrame} / {@link #endFrame}
 * 须按帧调用（相机驱动优先级；endFrame 关闭本帧被驱逐的 GPU 纹理）。
 */
public class CellCache {

    private static final Logger LOGGER = LoggerFactory.getLogger("seedmap4xaero/CellCache");

    public static final int TEXTURE_SIDE = 64;

    /** 调度优先级档位（{@link GenScheduler} 比较器主键）：可见 cell 请求（最高档）。 */
    public static final int KIND_VISIBLE = 0;
    /** 当前视口的粗层背景（fallback 源，prefetch 铺满链条）。 */
    public static final int KIND_BACKGROUND = 1;
    /** 视野外圈环 / 后台预生成（最低档）。 */
    public static final int KIND_PREFETCH = 2;
    /** 待生成瓦片上限（CPU 像素尖峰 ≤ 512×16KB = 8MB）；满时 getOrRequest/prefetch 均拒绝入队。 */
    static final int MAX_PENDING = 512;
    /** 每帧纹理上传上限（削平弹瓦片瞬间的主线程尖峰）。 */
    private static final int MAX_UPLOADS_PER_FRAME = 8;

    private static final CellLru CACHES[] = new CellLru[5];
    static {
        for (int i = 0; i < CACHES.length; i++) {
            CACHES[i] = new CellLru();
        }
    }

    /** 全局条目预算（跨 5 层共享；PerfConfig 变更实时生效）。 */
    private static volatile int capacityEntries = PerfConfig.cacheCapacityEntries();
    static {
        PerfConfig.addListener(() -> capacityEntries = PerfConfig.cacheCapacityEntries());
    }

    /** 跨层 LRU 驱逐用的全局访问序号（渲染线程独占）。 */
    private static long accessSeq;
    /** 当前缓存条目总数（渲染线程独占）。 */
    private static int totalEntries;
    /** 本帧被驱逐、延迟到 endFrame 关闭的 GPU 纹理（渲染线程独占）。 */
    private static final ArrayDeque<GpuTextureAndView> deferredClose = new ArrayDeque<>();
    /** 本帧已上传纹理数（渲染线程独占，beginFrame 清零）。 */
    private static int uploadsThisFrame;

    // ─── 统计（worker/渲染线程各自累加；debug HUD 读取） ─────────
    public static final AtomicLong STAT_GEN_COUNT = new AtomicLong();
    public static final AtomicLong STAT_GEN_NANOS = new AtomicLong();

    private static final @NotNull CellLru getCacheByScale(int scale) {
        return switch (scale) {
            case 1 -> CACHES[0];
            case 4 -> CACHES[1];
            case 16 -> CACHES[2];
            case 64 -> CACHES[3];
            case 256 -> CACHES[4];
            default -> throw new IllegalArgumentException("Invalid scale: " + scale);
        };
    }

    // ─── 帧钩子 ─────────────────────────────────────────────────

    /** 渲染线程每帧开始（renderSeedMapTiles 顶部）调用。 */
    public static void beginFrame(double cameraX, double cameraZ) {
        uploadsThisFrame = 0;
        CacheHelper.SCHEDULER.updateCamera(cameraX, cameraZ);
    }

    /** 渲染线程每帧 draw 结束后调用：关闭本帧被驱逐的 GPU 纹理。 */
    public static void endFrame() {
        while (!deferredClose.isEmpty()) {
            deferredClose.poll().close();
        }
    }

    // ─── 查询/请求 ──────────────────────────────────────────────

    /**
     * 获取或请求瓦片 GPU 纹理。
     *
     * <p>
     * 渲染线程调用。若缓存中已有 GPU 纹理则直接返回；只有 CPU 像素则（限流地）
     * 上传后返回；未命中且 pending 未满则入队异步生成并返回 {@code null}
     * （调用方应使用 super fallback / 占位纹理）。pending 满时不入队（backpressure）。
     * </p>
     *
     * @return 就绪的 GPU 纹理，或 {@code null}
     */
    public static @Nullable GpuTextureAndView getOrRequest(CellKey key) {
        var cache = getCacheByScale(key.scale());
        var data = cache.getStamped(key);
        if (data == null) {
            if (CacheHelper.SCHEDULER.pendingCount() >= MAX_PENDING)
                return null;
            data = cache.computeIfAbsentStamped(key, CellData::new);
            evictOverBudget();
        }
        return data.getGpuTex();
    }

    /**
     * prefetch 请求：命中（含 pending）则忽略；pending 满则忽略；
     * 否则以 {@code kind}（{@link #KIND_BACKGROUND} 视口粗层背景 /
     * {@link #KIND_PREFETCH} 外圈环与预生成）入队。渲染线程调用。
     */
    public static void prefetch(CellKey key, int kind) {
        var cache = getCacheByScale(key.scale());
        if (cache.containsKey(key))
            return;
        if (CacheHelper.SCHEDULER.pendingCount() >= MAX_PENDING)
            return;
        cache.putStamped(key, new CellData(key, kind));
        evictOverBudget();
    }

    /**
     * 窥视 GPU 纹理（fallback 渲染用）：命中即返回（必要时先限流上传）。
     */
    public static @Nullable GpuTextureAndView peekGpuTexture(CellKey key) {
        final var data = getCacheByScale(key.scale()).getStamped(key);
        return data != null ? data.getGpuTex() : null;
    }

    /** 判断某一级缩放是否有任何缓存 */
    public static boolean hasScaleCache(int scale) {
        return !getCacheByScale(scale).isEmpty();
    }

    public static void clear() {
        for (final var cache : CACHES) {
            for (final var d : cache.values()) {
                d.cancelled = true;
                if (d.gpuTex != null) {
                    d.gpuTex.close();
                    d.gpuTex = null;
                }
            }
            cache.clear();
        }
        deferredClose.clear(); // 上一帧的待关纹理就地关闭
        totalEntries = 0;
        CacheHelper.SCHEDULER.clearTiles();
    }

    /** 当前缓存条目数（debug HUD）。 */
    public static int entries() {
        return totalEntries;
    }

    /** 待生成瓦片数（debug HUD）。 */
    public static int pending() {
        return CacheHelper.SCHEDULER.pendingCount();
    }

    // ─── 内部: LRU + 驱逐 ───────────────────────────────────────

    /** access-order LRU 层；插入/访问时盖章 {@code accessSeq} 供跨层比较。 */
    private static final class CellLru extends LinkedHashMap<CellKey, CellData> {
        private CellLru() {
            super(1024, 0.75f, true);
        }

        CellData getStamped(CellKey key) {
            var d = get(key);
            if (d != null)
                d.accessSeq = ++accessSeq;
            return d;
        }

        CellData computeIfAbsentStamped(CellKey key, java.util.function.Function<CellKey, CellData> fn) {
            var d = computeIfAbsent(key, fn);
            d.accessSeq = ++accessSeq;
            totalEntries++;
            return d;
        }

        CellData putStamped(CellKey key, CellData d) {
            var prev = put(key, d);
            d.accessSeq = ++accessSeq;
            if (prev == null)
                totalEntries++;
            return prev;
        }

        /** 最久未访问条目（access-order: 迭代首项）；空层返回 null。 */
        @Nullable CellData eldest() {
            return isEmpty() ? null : values().iterator().next();
        }
    }

    /** 全局预算驱逐：跨层挑 accessSeq 最小者；pending 条目标记 cancelled。 */
    private static void evictOverBudget() {
        while (totalEntries > capacityEntries) {
            CellData victim = null;
            for (final var c : CACHES) {
                var e = c.eldest();
                if (e != null && (victim == null || e.accessSeq < victim.accessSeq))
                    victim = e;
            }
            if (victim == null)
                break;
            getCacheByScale(victim.key.scale()).remove(victim.key);
            totalEntries--;
            victim.cancelled = true;
            if (victim.gpuTex != null) {
                deferredClose.add(victim.gpuTex); // 本帧可能仍被 blit 引用, draw 后再关
                victim.gpuTex = null;
            }
        }
    }

    // ─── 内部: 生成与上传 ───────────────────────────────────────

    public static @NotNull GpuTextureAndView getPlaceholderTexture() {
        return CompatTextures.placeholderTexture();
    }

    private static GpuTextureAndView uploadTexture(CellData data) {
        final int[] pixels = data.pixels;
        data.pixels = null; // 释放 CPU 像素数据
        return data.gpuTex = CompatTextures.createUploadTexture("xsm", 15, TEXTURE_SIDE, pixels);
    }

    /**
     * 生成一个瓦片（生成线程调用，由 {@link GenScheduler} 分发）。
     * cancelled 检查在生成前后各一次；结果统计进 {@code STAT_GEN_*}。
     */
    static void generate(CellData d) {
        if (d.cancelled)
            return;
        long t0 = System.nanoTime();
        int[] result;
        try {
            result = Xsm.genCellImg(d.key.scale(), d.key.worldX(), d.key.worldZ(), absY, true);
        } catch (Exception ex) {
            if (!d.cancelled) {
                LOGGER.error("genCellImg failed for {}", d.key, ex);
                d.failed = true;
            }
            return;
        }
        STAT_GEN_COUNT.incrementAndGet();
        STAT_GEN_NANOS.addAndGet(System.nanoTime() - t0);
        if (d.cancelled)
            return;
        if (result == null)
            d.failed = true;
        else
            d.pixels = result;
    }

    private static final int absY = 63;

    /**
     * 瓦片数据。状态机：pending（无 pixels 无 gpuTex 未取消未失败）→
     * pixels 就绪（渲染线程限流上传）→ gpuTex 就绪；cancelled/failed 为终态。
     */
    static final class CellData {
        final CellKey key;
        /** 调度优先级档位（GenScheduler KIND_*，final）。 */
        final byte priorityKind;
        volatile int[] pixels;
        GpuTextureAndView gpuTex;
        volatile boolean failed;
        volatile boolean cancelled;
        /** 入队序号（GenScheduler FIFO tiebreak）。 */
        long seq;
        /** 跨层 LRU 驱逐比较用访问序号。 */
        long accessSeq;

        CellData(CellKey key) {
            this(key, KIND_VISIBLE);
        }

        CellData(CellKey key, int priorityKind) {
            this.key = key;
            this.priorityKind = (byte) priorityKind;
            CacheHelper.SCHEDULER.enqueueTile(this);
        }

        boolean isPending() {
            return !this.cancelled && this.gpuTex == null && this.pixels == null && !this.failed;
        }

        /**
         * 渲染线程：取 GPU 纹理；pixels 就绪时限流上传。
         *
         * @return 就绪纹理，或 {@code null}（pending / 本帧上传额度用尽）
         */
        @Nullable GpuTextureAndView getGpuTex() {
            if (this.gpuTex != null)
                return this.gpuTex;
            if (this.pixels != null) {
                if (uploadsThisFrame >= MAX_UPLOADS_PER_FRAME)
                    return null;
                uploadsThisFrame++;
                return uploadTexture(this);
            }
            return null;
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
}
