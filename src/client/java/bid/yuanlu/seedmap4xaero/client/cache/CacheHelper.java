package bid.yuanlu.seedmap4xaero.client.cache;

import java.util.concurrent.Executor;

import bid.yuanlu.seedmap4xaero.client.configs.perf.PerfConfig;

public final class CacheHelper {

    static volatile long currentTick = 0;

    /** 帧计数（地图打开期间每帧 +1）；包外只读。 */
    public static long currentTick() {
        return currentTick;
    }

    /** 渲染线程每帧调用（地图打开期间），推进帧计数（prefetch 周期节流等使用）。 */
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
        clearAllCaches();
    }

    /** 强制清空全部查询缓存并重置去重哨兵（如 MC 版本切换后）。 */
    public static void invalidateAll() {
        lastSeed = Long.MIN_VALUE;
        lastDim = Integer.MIN_VALUE;
        clearAllCaches();
    }

    private static void clearAllCaches() {
        CellCache.clear();
        QueryPointCache.clear();
        StructureCache.clear();
        StrongholdCache.clear();
        bid.yuanlu.seedmap4xaero.client.structure.LootPreviewState.clearCache();
    }

    // ─── 生成线程池（瓦片 + 即时任务统一调度） ───────────────────

    static final GenScheduler SCHEDULER = new GenScheduler();

    private static volatile int poolGeneration = SCHEDULER.currentGeneration();

    static {
        startThreads();
        // 性能配置变更 → 线程数变化时重建池（其他配置项变化时空转）
        PerfConfig.addListener(CacheHelper::rebuildPoolIfResized);
    }

    private static volatile Thread[] genThreads = new Thread[0];

    private static void startThreads() {
        int n = PerfConfig.effectiveGenerationThreads();
        Thread[] threads = new Thread[n];
        for (int i = 0; i < n; i++) {
            Thread t = new Thread(CacheHelper::workerLoop, "xsm-gen-" + i);
            t.setDaemon(true);
            // 低于普通优先级: 生成不与渲染/服务端线程抢核（Windows 映射 OS 优先级，
            // Linux 受 ThreadPriorityPolicy 限制效果有限——主杠杆是线程数上限）
            t.setPriority(Thread.NORM_PRIORITY - 2);
            threads[i] = t;
            t.start();
        }
        genThreads = threads;
    }

    private static synchronized void rebuildPoolIfResized() {
        int target = PerfConfig.effectiveGenerationThreads();
        long alive = 0;
        for (Thread t : genThreads)
            if (t.isAlive())
                alive++;
        if (alive == target)
            return;
        poolGeneration = SCHEDULER.currentGeneration() + 1;
        SCHEDULER.bumpGeneration(); // 旧代线程唤醒后见代数不符退出
        startThreads();
    }

    private static void workerLoop() {
        final int myGen = poolGeneration;
        while (myGen == poolGeneration) {
            final Object work;
            try {
                work = SCHEDULER.take(myGen);
            } catch (InterruptedException e) {
                return;
            }
            if (work == null)
                return;
            // 先处理再退出：take 已把瓦片移出 pendingTiles，此时若因代数变化
            // 丢弃，会留下永久 pending 的占位（不再被补请求，灰格直到驱逐）。
            // generate/Job 自带 cancelled/world-switch 防护，多处理一件无害。
            if (work instanceof Runnable job) {
                job.run();
            } else {
                CellCache.generate((CellCache.CellData) work);
            }
        }
    }

    /**
     * 后台计算共用执行器（包外只读访问，如访问检测）：提交进
     * {@link GenScheduler} 的即时队列，由生成线程优先执行。
     */
    public static Executor worker() {
        return SCHEDULER::submitJob;
    }
}
