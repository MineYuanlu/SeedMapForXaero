package bid.yuanlu.seedmap4xaero.client.cache;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class CacheHelper {

    static volatile long currentTick = 0;

    /** 渲染线程每帧调用，推进tick计数器供TTL使用 */
    public static void tick() {
        currentTick++;
    }

    private static long lastSeed = Long.MIN_VALUE;
    private static int lastDim = Integer.MIN_VALUE;

    /** 设置世界种子/维度（已缓存去重）。 */
    public static void setWorld(long seed, int dim) {
        if (seed == lastSeed && dim == lastDim)
            return;
        lastSeed = seed;
        lastDim = dim;
        CellCache.clear();
        QueryPointCache.clear();
    }

    static final ExecutorService CACHE_WORKER;
    static {
        CACHE_WORKER = Executors.newFixedThreadPool(Math.max(1, Runtime.getRuntime().availableProcessors() / 2), r -> {
            Thread t = new Thread(r, "xsm-cache");
            t.setDaemon(true);
            return t;
        });
    }
}
